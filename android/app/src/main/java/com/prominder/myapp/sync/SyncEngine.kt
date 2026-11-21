package com.prominder.myapp.sync

import androidx.room.withTransaction
import com.prominder.myapp.data.AppDatabase
import com.prominder.myapp.data.EventRow
import com.prominder.myapp.data.FocusRow
import com.prominder.myapp.data.MessageRow
import com.prominder.myapp.data.MetaRow
import com.prominder.myapp.data.PreferenceRow
import com.prominder.myapp.data.SessionRow
import com.prominder.myapp.data.SettingRow
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.data.linkMessage
import com.prominder.myapp.diag.Diag
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * The phone's side of the backup. The phone's database is the live copy and the only one that changes;
 * the backup (the backend's DynamoDB table) gets the changes once a day, and when you leave the app on Wi-Fi
 * (SyncScheduler), or right away with Diagnostics → Back up now.
 *
 * - [restore]: an empty phone downloads everything, page by page (one answer can't be over 6 MB, and the chat keeps
 *   growing), and saves it all at once. With handover=true that's the handover: from then on the phone owns the
 *   data. A reinstall restores the same way.
 * - [upload]: every row changed since the last upload (by change number), and the rows deleted since, in requests
 *   of at most [BATCH] in change-number order. Each request that gets through is remembered, so after a long time
 *   offline a slow backup continues where it stopped instead of starting over.
 *   Nothing changed for [HEARTBEAT] → an empty upload anyway, so the server knows the phone is alive (Phase C
 *   emails you if it goes quiet).
 */
class SyncEngine(
    private val db: AppDatabase,
    private val api: SyncApi,
    private val device: String,
    private val clock: () -> Instant = Instant::now,
) {
    private val dao = db.dao()
    /** One upload at a time (Sync now and the scheduled one may start together). */
    private val uploading = Mutex()

    data class RestoreSummary(val tasks: Int, val messages: Int, val rows: Int, val handedOverAt: String?)
    data class UploadSummary(val rows: Int, val deleted: Int, val heartbeat: Boolean)

    /** The backup's state on the server (handed over yet? last upload?). */
    suspend fun status(): SyncStatus = api.status()

    /** Changes not yet in the backup. */
    suspend fun pending(): Int = dao.countChangedAfter(uploadedSeq())

    suspend fun lastUploadAt(): Instant? = Times.parseOrNull(dao.meta(Store.META_LAST_UPLOAD_AT))

    /** Downloads everything into the (empty) phone. Refuses when the phone already has data, so nothing is overwritten. */
    suspend fun restore(handover: Boolean): RestoreSummary {
        if (dao.taskCount() > 0 || dao.messageCount() > 0) {
            throw SyncException("This phone already has data, so nothing was downloaded.", network = false)
        }
        // Every page first, then one transaction: a restore that fails half-way leaves the phone empty, ready to try again.
        val pages = mutableListOf(api.restore(handover, device))
        while (true) {
            val cursor = pages.last().next ?: break
            if (pages.size >= MAX_PAGES) throw SyncException("The backup didn't finish sending (over $MAX_PAGES pages).", network = false)
            pages += api.restore(handover, device, cursor)
        }
        val t = Tables(
            tasks = pages.flatMap { it.tables.tasks }, events = pages.flatMap { it.tables.events },
            messages = pages.flatMap { it.tables.messages }, sessions = pages.flatMap { it.tables.sessions },
            preferences = pages.flatMap { it.tables.preferences }, settings = pages.flatMap { it.tables.settings },
            focus = pages.flatMap { it.tables.focus },
        )
        val first = pages.first()
        val last = pages.last()
        db.withTransaction {
            // Rows from the backup are unchanged (seq 0): they aren't uploaded again.
            dao.upsertTasks(t.tasks.map { it.copy(seq = 0) })
            dao.upsertEvents(t.events.map { it.copy(seq = 0) })
            dao.upsertMessages(t.messages.map { it.copy(seq = 0) })
            t.messages.forEach { dao.linkMessage(it) }
            dao.upsertSessions(t.sessions.map { it.copy(seq = 0) })
            dao.upsertPreferences(t.preferences.map { it.copy(seq = 0) })
            dao.upsertSettings(t.settings.map { it.copy(seq = 0) })
            dao.upsertFocus(t.focus.map { it.copy(seq = 0) })
            for ((name, key) in COUNTERS) {
                last.counters[name]?.let { dao.setMeta(MetaRow(key, it.toString())) }
            }
            dao.setMeta(MetaRow(Store.META_RESTORED_AT, first.serverTime))
            dao.setMeta(MetaRow(Store.META_UPLOADED_SEQ, (dao.meta(Store.META_SEQ) ?: "0")))
            // The backup is as fresh as can be (on the phone's clock, which the daily "alive" upload is timed by).
            dao.setMeta(MetaRow(Store.META_LAST_UPLOAD_AT, Times.format(clock())))
            last.status.handedOverAt?.let { dao.setMeta(MetaRow(Store.META_HANDED_OVER_AT, it)) }
        }
        Diag.log("sync", "Restored ${t.tasks.size} tasks, ${t.messages.size} messages (${t.size} rows" +
            (if (pages.size > 1) " in ${pages.size} pages)" else ")") + if (handover) "; the phone owns the data now" else "")
        return RestoreSummary(t.tasks.size, t.messages.size, t.size, last.status.handedOverAt)
    }

    /** Sends what changed since the last upload. Throws [SyncException]; what got through before it is kept. */
    suspend fun upload(force: Boolean = false): UploadSummary = uploading.withLock { uploadLocked(force) }

    private suspend fun uploadLocked(force: Boolean): UploadSummary {
        val upTo = dao.meta(Store.META_SEQ)?.toLongOrNull() ?: 0
        val after = uploadedSeq()
        val last = lastUploadAt()
        val heartbeatDue = last == null || Duration.between(last, clock()) >= HEARTBEAT
        if (upTo <= after && !heartbeatDue && !force) return UploadSummary(0, 0, heartbeat = false)

        val changes = buildList {
            dao.changedTasks(after, upTo).forEach { add(Change.Row(it.seq, it)) }
            dao.changedEvents(after, upTo).forEach { add(Change.Row(it.seq, it)) }
            dao.changedMessages(after, upTo).forEach { add(Change.Row(it.seq, it)) }
            dao.changedSessions(after, upTo).forEach { add(Change.Row(it.seq, it)) }
            dao.changedPreferences(after, upTo).forEach { add(Change.Row(it.seq, it)) }
            dao.changedSettings(after, upTo).forEach { add(Change.Row(it.seq, it)) }
            dao.changedFocus(after, upTo).forEach { add(Change.Row(it.seq, it)) }
            dao.changedTombstones(after, upTo).forEach {
                add(Change.Delete(it.seq, it.table, SyncApi.JSON.parseToJsonElement(it.key)))
            }
        }
        val batches = batches(changes, after)
        if (batches.isEmpty()) api.upload(UploadBody(device, Tables(), emptyMap()))
        for (batch in batches) {
            api.upload(UploadBody(device, batch.tables, batch.deletes))
            // Got through: everything up to batch.through is in the backup, even if a later request fails.
            db.withTransaction {
                dao.setMeta(MetaRow(Store.META_UPLOADED_SEQ, batch.through.toString()))
                dao.dropTombstones(batch.through)
            }
        }
        db.withTransaction {
            dao.setMeta(MetaRow(Store.META_UPLOADED_SEQ, upTo.toString()))
            dao.dropTombstones(upTo)
            dao.setMeta(MetaRow(Store.META_LAST_UPLOAD_AT, Times.format(clock())))
            dao.setMeta(MetaRow(Store.META_LAST_SYNC_ERROR, ""))
        }
        val deleted = changes.count { it is Change.Delete }
        val summary = UploadSummary(changes.size - deleted, deleted, heartbeat = changes.isEmpty())
        Diag.log("sync", if (summary.heartbeat) "Backup: nothing changed (told the server the phone is alive)"
            else "Backup: sent ${summary.rows} changed rows" + (if (summary.deleted > 0) ", ${summary.deleted} deleted" else "") +
                if (batches.size > 1) " in ${batches.size} requests" else "")
        return summary
    }

    suspend fun recordError(message: String) {
        dao.setMeta(MetaRow(Store.META_LAST_SYNC_ERROR, "${Times.format(clock())} $message"))
    }

    private suspend fun uploadedSeq(): Long = dao.meta(Store.META_UPLOADED_SEQ)?.toLongOrNull() ?: 0

    /** One thing to send: a changed row, or a deleted row's key. */
    internal sealed interface Change {
        val seq: Long

        data class Row(override val seq: Long, val row: Any) : Change
        data class Delete(override val seq: Long, val table: String, val key: JsonElement) : Change
    }

    /** One request: its rows and deletes, and the change number everything up to which is sent once it gets through. */
    internal data class Batch(val tables: Tables, val deletes: Map<String, List<JsonElement>>, val through: Long) {
        val size: Int get() = tables.size + deletes.values.sumOf { it.size }
    }

    companion object {
        val HEARTBEAT: Duration = Duration.ofHours(20)
        /**
         * Rows per request. The backend writes about 11-25 rows a second on DynamoDB's free capacity and has 40 s
         * before it gives up, so a request this size gets through even with long messages.
         */
        const val BATCH = 200
        /** A restore longer than this many pages is a bug, not a backup (each page is about 1.5 MB). */
        private const val MAX_PAGES = 1000

        private val COUNTERS = mapOf(
            "task" to Store.META_TASK_COUNTER, "message" to Store.META_MESSAGE_COUNTER,
            "session" to Store.META_SESSION_COUNTER, "preference" to Store.META_PREFERENCE_COUNTER,
        )

        /**
         * Packs the changes into requests of at most [size], in change-number order, cutting between change numbers
         * where it can. Each batch's `through` is the highest change number whose rows are all in it or before it
         * ([after] until the first one is complete), so a batch that gets through can be remembered as sent.
         */
        internal fun batches(changes: List<Change>, after: Long = 0, size: Int = BATCH): List<Batch> {
            val out = mutableListOf<Batch>()
            var current = mutableListOf<Change>()
            var complete = after
            fun flush() {
                if (current.isNotEmpty()) out += pack(current, complete)
                current = mutableListOf()
            }
            val bySeq = changes.sortedBy { it.seq }.groupBy { it.seq }   // groupBy keeps the sorted order
            for ((seq, group) in bySeq) {
                if (current.size + group.size > size) flush()
                for (change in group) {
                    if (current.size == size) flush()   // one change larger than a request: it spans several
                    current += change
                }
                complete = seq
            }
            flush()
            return out
        }

        private fun pack(changes: List<Change>, through: Long): Batch {
            val rows = changes.filterIsInstance<Change.Row>().map { it.row }
            val tables = Tables(
                tasks = rows.filterIsInstance<TaskRow>(), events = rows.filterIsInstance<EventRow>(),
                messages = rows.filterIsInstance<MessageRow>(), sessions = rows.filterIsInstance<SessionRow>(),
                preferences = rows.filterIsInstance<PreferenceRow>(), settings = rows.filterIsInstance<SettingRow>(),
                focus = rows.filterIsInstance<FocusRow>(),
            )
            val deletes = changes.filterIsInstance<Change.Delete>().groupBy({ it.table }, { it.key })
            return Batch(tables, deletes, through)
        }
    }
}
