package com.prominder.myapp.sync

import androidx.room.withTransaction
import com.prominder.myapp.data.AppDatabase
import com.prominder.myapp.data.MetaRow
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.Times
import com.prominder.myapp.diag.Diag
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * The phone's side of the backup. The phone's database is the live copy and the only one that changes;
 * the backup (the backend's DynamoDB table) gets every change a few minutes after it happens.
 *
 * - [restore]: an empty phone downloads everything. With handover=true that's the handover: from then on the
 *   web app is switched off and the phone owns the data. A reinstall restores the same way.
 * - [upload]: every row changed since the last upload (by change number), and the rows deleted since.
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
        val result = api.restore(handover, device)
        val t = result.tables
        db.withTransaction {
            // Rows from the backup are unchanged (seq 0): they aren't uploaded again.
            dao.upsertTasks(t.tasks.map { it.copy(seq = 0) })
            dao.upsertEvents(t.events.map { it.copy(seq = 0) })
            dao.upsertMessages(t.messages.map { it.copy(seq = 0) })
            dao.upsertSessions(t.sessions.map { it.copy(seq = 0) })
            dao.upsertPreferences(t.preferences.map { it.copy(seq = 0) })
            dao.upsertSettings(t.settings.map { it.copy(seq = 0) })
            dao.upsertFocus(t.focus.map { it.copy(seq = 0) })
            for ((name, key) in COUNTERS) {
                result.counters[name]?.let { dao.setMeta(MetaRow(key, it.toString())) }
            }
            dao.setMeta(MetaRow(Store.META_RESTORED_AT, result.serverTime))
            dao.setMeta(MetaRow(Store.META_UPLOADED_SEQ, (dao.meta(Store.META_SEQ) ?: "0")))
            // The backup is as fresh as can be (on the phone's clock, which the daily "alive" upload is timed by).
            dao.setMeta(MetaRow(Store.META_LAST_UPLOAD_AT, Times.format(clock())))
            result.status.handedOverAt?.let { dao.setMeta(MetaRow(Store.META_HANDED_OVER_AT, it)) }
        }
        Diag.log("sync", "Restored ${t.tasks.size} tasks, ${t.messages.size} messages (${t.size} rows)" +
            if (handover) "; the phone owns the data now" else "")
        return RestoreSummary(t.tasks.size, t.messages.size, t.size, result.status.handedOverAt)
    }

    /** Sends what changed since the last upload. Throws [SyncException]; nothing is marked as sent unless all of it was. */
    suspend fun upload(force: Boolean = false): UploadSummary = uploading.withLock { uploadLocked(force) }

    private suspend fun uploadLocked(force: Boolean): UploadSummary {
        val upTo = dao.meta(Store.META_SEQ)?.toLongOrNull() ?: 0
        val after = uploadedSeq()
        val last = lastUploadAt()
        val heartbeatDue = last == null || Duration.between(last, clock()) >= HEARTBEAT
        if (upTo <= after && !heartbeatDue && !force) return UploadSummary(0, 0, heartbeat = false)

        val changed = Tables(
            tasks = dao.changedTasks(after, upTo),
            events = dao.changedEvents(after, upTo),
            messages = dao.changedMessages(after, upTo),
            sessions = dao.changedSessions(after, upTo),
            preferences = dao.changedPreferences(after, upTo),
            settings = dao.changedSettings(after, upTo),
            focus = dao.changedFocus(after, upTo),
        )
        val tombstones = dao.changedTombstones(after, upTo)
        val deletes: Map<String, List<JsonElement>> = tombstones.groupBy { it.table }
            .mapValues { (_, rows) -> rows.map { SyncApi.JSON.parseToJsonElement(it.key) } }

        val batches = batches(changed)
        if (batches.isEmpty()) {
            api.upload(UploadBody(device, Tables(), deletes))
        } else {
            batches.forEachIndexed { i, batch ->
                api.upload(UploadBody(device, batch, if (i == batches.lastIndex) deletes else emptyMap()))
            }
        }
        db.withTransaction {
            dao.setMeta(MetaRow(Store.META_UPLOADED_SEQ, upTo.toString()))
            dao.dropTombstones(upTo)
            dao.setMeta(MetaRow(Store.META_LAST_UPLOAD_AT, Times.format(clock())))
            dao.setMeta(MetaRow(Store.META_LAST_SYNC_ERROR, ""))
        }
        val summary = UploadSummary(changed.size, tombstones.size, heartbeat = changed.size == 0 && tombstones.isEmpty())
        Diag.log("sync", if (summary.heartbeat) "Backup: nothing changed (told the server the phone is alive)"
            else "Backup: sent ${summary.rows} changed rows" + if (summary.deleted > 0) ", ${summary.deleted} deleted" else "")
        return summary
    }

    suspend fun recordError(message: String) {
        dao.setMeta(MetaRow(Store.META_LAST_SYNC_ERROR, "${Times.format(clock())} $message"))
    }

    private suspend fun uploadedSeq(): Long = dao.meta(Store.META_UPLOADED_SEQ)?.toLongOrNull() ?: 0

    companion object {
        val HEARTBEAT: Duration = Duration.ofHours(20)
        /** Rows per request (the server takes up to 2000). */
        const val BATCH = 1500

        private val COUNTERS = mapOf(
            "task" to Store.META_TASK_COUNTER, "message" to Store.META_MESSAGE_COUNTER,
            "session" to Store.META_SESSION_COUNTER, "preference" to Store.META_PREFERENCE_COUNTER,
        )

        /** Packs the changes into as few requests as possible, each at most [size] rows. */
        internal fun batches(t: Tables, size: Int = BATCH): List<Tables> {
            val pieces: List<Tables> = t.tasks.chunked(size).map { Tables(tasks = it) } +
                t.events.chunked(size).map { Tables(events = it) } +
                t.messages.chunked(size).map { Tables(messages = it) } +
                t.sessions.chunked(size).map { Tables(sessions = it) } +
                t.preferences.chunked(size).map { Tables(preferences = it) } +
                t.settings.chunked(size).map { Tables(settings = it) } +
                t.focus.chunked(size).map { Tables(focus = it) }
            val out = mutableListOf<Tables>()
            for (piece in pieces) {
                val last = out.lastOrNull()
                if (last != null && last.size + piece.size <= size) out[out.lastIndex] = last + piece else out += piece
            }
            return out
        }

        private operator fun Tables.plus(o: Tables) = Tables(tasks + o.tasks, events + o.events, messages + o.messages,
            sessions + o.sessions, preferences + o.preferences, settings + o.settings, focus + o.focus)
    }
}
