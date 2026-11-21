package com.prominder.myapp.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * The phone's database: the live copy of your data. Schema changes are Room migrations (each version's
 * schema is kept in android/app/schemas/), never a wipe: the data here is the real thing.
 */
@Database(
    entities = [TaskRow::class, EventRow::class, MessageRow::class, SessionRow::class, PreferenceRow::class,
        SettingRow::class, FocusRow::class, Tombstone::class, MetaRow::class, MessageTask::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "prominder.db").addMigrations(*MIGRATIONS).build()

        /** For tests: a database in memory. */
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}

/**
 * 1 → 2: indexes for the queries that read growing tables (seq on every table: what to back up; messages by time:
 * the guardrails' counts), the message ↔ task links, filled from each message's task_ids, and no tombstone left for a
 * setting that has a row (a setting set, reset and set again kept one, which stopped every backup).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("tasks", "events", "messages", "sessions", "preferences", "settings", "focus", "tombstones")) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_seq` ON `$table` (`seq`)")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_created_at` ON `messages` (`created_at`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `message_tasks` (`task_id` INTEGER NOT NULL, `message_id` INTEGER NOT NULL, " +
                "PRIMARY KEY(`task_id`, `message_id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_tasks_message_id` ON `message_tasks` (`message_id`)")
        val converters = Converters()
        db.query("SELECT id, task_ids FROM messages").use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                for (taskId in converters.textToIds(c.getString(1)).distinct()) {
                    db.execSQL("INSERT OR IGNORE INTO message_tasks (task_id, message_id) VALUES (?, ?)", arrayOf<Any>(taskId, id))
                }
            }
        }
        db.execSQL("DELETE FROM tombstones WHERE table_name = 'settings' AND row_key IN (SELECT '\"' || `key` || '\"' FROM settings)")
    }
}

/** A list of ids is stored as JSON text (SQLite has no arrays), as the plan says. */
class Converters {
    @TypeConverter
    fun idsToText(ids: List<Long>): String = Json.encodeToString(ids)

    @TypeConverter
    fun textToIds(text: String): List<Long> = runCatching { Json.decodeFromString<List<Long>>(text) }.getOrDefault(emptyList())
}

@Dao
interface AppDao {
    // ------------------------------------------------------------ tasks

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: Long): TaskRow?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun watchTask(id: Long): Flow<TaskRow?>

    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun tasks(ids: List<Long>): List<TaskRow>

    @Query("SELECT * FROM tasks WHERE status = 'open'")
    suspend fun openTasks(): List<TaskRow>

    @Query("SELECT * FROM tasks WHERE status = 'open'")
    fun watchOpenTasks(): Flow<List<TaskRow>>

    @Query("SELECT * FROM tasks WHERE parent_id = :parentId AND status != 'cancelled' ORDER BY created_at, id")
    fun watchSubtasks(parentId: Long): Flow<List<TaskRow>>

    @Query("SELECT * FROM tasks WHERE parent_id = :parentId")
    suspend fun children(parentId: Long): List<TaskRow>

    @Query("SELECT * FROM tasks WHERE focus_date = :date AND status != 'cancelled'")
    fun watchFocusTasks(date: String): Flow<List<TaskRow>>

    @Query("SELECT * FROM tasks WHERE focus_date = :date")
    suspend fun focusTasks(date: String): List<TaskRow>

    @Query("SELECT COALESCE(MAX(id), 0) FROM tasks")
    suspend fun maxTaskId(): Long

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun taskCount(): Int

    @Query("SELECT * FROM tasks WHERE status = 'done' AND completed_at >= :since ORDER BY completed_at")
    suspend fun doneSince(since: String): List<TaskRow>

    @Upsert
    suspend fun upsertTasks(rows: List<TaskRow>)

    // ------------------------------------------------------------ events

    @Query("SELECT * FROM events WHERE task_id = :taskId ORDER BY created_at DESC LIMIT :limit")
    fun watchEvents(taskId: Long, limit: Int): Flow<List<EventRow>>

    @Query("SELECT * FROM events WHERE kind = 'done' AND created_at >= :since")
    fun watchDoneSince(since: String): Flow<List<EventRow>>

    @Upsert
    suspend fun upsertEvents(rows: List<EventRow>)

    /** A task's events since a moment (text times compare in time order). */
    @Query("SELECT * FROM events WHERE task_id = :taskId AND created_at >= :since ORDER BY created_at DESC")
    suspend fun eventsSince(taskId: Long, since: String): List<EventRow>

    @Query("SELECT * FROM events WHERE kind = :kind AND created_at >= :since ORDER BY created_at")
    suspend fun eventsOfKindSince(kind: String, since: String): List<EventRow>

    // ------------------------------------------------------------ messages

    @Query("SELECT * FROM (SELECT * FROM messages ORDER BY id DESC LIMIT :limit) ORDER BY id")
    fun watchLatestMessages(limit: Int): Flow<List<MessageRow>>

    @Query("SELECT COALESCE(MAX(id), 0) FROM messages")
    suspend fun maxMessageId(): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: Long): MessageRow?

    @Query("SELECT * FROM messages WHERE id = :id")
    fun watchMessage(id: Long): Flow<MessageRow?>

    /** The latest messages about a task, newest first (through the message ↔ task links). */
    @Query(
        "SELECT messages.* FROM message_tasks JOIN messages ON messages.id = message_tasks.message_id" +
            " WHERE message_tasks.task_id = :taskId ORDER BY messages.id DESC LIMIT :limit",
    )
    suspend fun messagesForTask(taskId: Long, limit: Int): List<MessageRow>

    @Query("DELETE FROM message_tasks WHERE message_id = :messageId")
    suspend fun unlinkMessage(messageId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageTasks(rows: List<MessageTask>)

    /** The latest messages, newest first. */
    @Query("SELECT * FROM messages ORDER BY id DESC LIMIT :limit")
    suspend fun latestMessages(limit: Int): List<MessageRow>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun messageCount(): Int

    @Upsert
    suspend fun upsertMessages(rows: List<MessageRow>)

    /** Messages since a moment, newest first (the guardrails' counts, today's chat). */
    @Query("SELECT * FROM messages WHERE created_at >= :since ORDER BY id DESC")
    suspend fun messagesSince(since: String): List<MessageRow>

    @Query("SELECT * FROM messages WHERE kind IN (:kinds) ORDER BY id DESC LIMIT 1")
    suspend fun latestOfKinds(kinds: List<String>): MessageRow?

    @Query("SELECT * FROM messages WHERE kind IN (:kinds) AND role = :role ORDER BY id DESC LIMIT 1")
    suspend fun latestOfKindsBy(kinds: List<String>, role: String): MessageRow?

    // ------------------------------------------------------------ sessions, preferences, settings, focus

    @Query("SELECT * FROM sessions WHERE task_id = :taskId AND ended_at IS NULL")
    suspend fun openSessions(taskId: Long): List<SessionRow>

    @Upsert
    suspend fun upsertSessions(rows: List<SessionRow>)

    @Query("SELECT * FROM sessions ORDER BY id DESC LIMIT 1")
    suspend fun latestSession(): SessionRow?

    @Query("SELECT * FROM sessions WHERE ended_at IS NULL")
    suspend fun allOpenSessions(): List<SessionRow>

    @Query("SELECT * FROM sessions WHERE started_at >= :since ORDER BY id DESC")
    suspend fun sessionsSince(since: String): List<SessionRow>

    @Query("SELECT * FROM sessions WHERE task_id = :taskId")
    suspend fun sessionsOf(taskId: Long): List<SessionRow>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: Long): SessionRow?

    @Query("SELECT COALESCE(MAX(id), 0) FROM sessions")
    suspend fun maxSessionId(): Long

    /** The latest session, open or closed: the timer, the session bar and "Where did you stop?" watch it. */
    @Query("SELECT * FROM sessions ORDER BY id DESC LIMIT 1")
    fun watchLatestSession(): Flow<SessionRow?>

    @Query("SELECT COUNT(*) FROM sessions WHERE started_at >= :since")
    fun watchSessionCountSince(since: String): Flow<Int>

    @Query("SELECT * FROM preferences ORDER BY id")
    suspend fun preferences(): List<PreferenceRow>

    @Query("SELECT COALESCE(MAX(id), 0) FROM preferences")
    suspend fun maxPreferenceId(): Long

    @Query("SELECT * FROM preferences ORDER BY id")
    fun watchPreferences(): Flow<List<PreferenceRow>>

    @Query("SELECT * FROM preferences WHERE id = :id")
    suspend fun preference(id: Long): PreferenceRow?

    @Query("DELETE FROM preferences WHERE id = :id")
    suspend fun deletePreference(id: Long)

    @Upsert
    suspend fun upsertPreferences(rows: List<PreferenceRow>)

    @Query("SELECT * FROM settings")
    suspend fun settings(): List<SettingRow>

    @Query("SELECT * FROM settings")
    fun watchSettings(): Flow<List<SettingRow>>

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)

    @Upsert
    suspend fun upsertSettings(rows: List<SettingRow>)

    @Query("SELECT * FROM focus WHERE date = :date")
    suspend fun focus(date: String): FocusRow?

    @Upsert
    suspend fun upsertFocus(rows: List<FocusRow>)

    // ------------------------------------------------------------ what changed since the last upload

    @Query("SELECT * FROM tasks WHERE seq > :after AND seq <= :upTo")
    suspend fun changedTasks(after: Long, upTo: Long): List<TaskRow>

    @Query("SELECT * FROM events WHERE seq > :after AND seq <= :upTo")
    suspend fun changedEvents(after: Long, upTo: Long): List<EventRow>

    @Query("SELECT * FROM messages WHERE seq > :after AND seq <= :upTo")
    suspend fun changedMessages(after: Long, upTo: Long): List<MessageRow>

    @Query("SELECT * FROM sessions WHERE seq > :after AND seq <= :upTo")
    suspend fun changedSessions(after: Long, upTo: Long): List<SessionRow>

    @Query("SELECT * FROM preferences WHERE seq > :after AND seq <= :upTo")
    suspend fun changedPreferences(after: Long, upTo: Long): List<PreferenceRow>

    @Query("SELECT * FROM settings WHERE seq > :after AND seq <= :upTo")
    suspend fun changedSettings(after: Long, upTo: Long): List<SettingRow>

    @Query("SELECT * FROM focus WHERE seq > :after AND seq <= :upTo")
    suspend fun changedFocus(after: Long, upTo: Long): List<FocusRow>

    @Query("SELECT * FROM tombstones WHERE seq > :after AND seq <= :upTo")
    suspend fun changedTombstones(after: Long, upTo: Long): List<Tombstone>

    @Query(
        "SELECT (SELECT COUNT(*) FROM tasks WHERE seq > :after) + (SELECT COUNT(*) FROM events WHERE seq > :after)" +
            " + (SELECT COUNT(*) FROM messages WHERE seq > :after) + (SELECT COUNT(*) FROM sessions WHERE seq > :after)" +
            " + (SELECT COUNT(*) FROM preferences WHERE seq > :after) + (SELECT COUNT(*) FROM settings WHERE seq > :after)" +
            " + (SELECT COUNT(*) FROM focus WHERE seq > :after) + (SELECT COUNT(*) FROM tombstones WHERE seq > :after)",
    )
    suspend fun countChangedAfter(after: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(row: Tombstone)

    @Query("DELETE FROM tombstones WHERE table_name = :table AND row_key = :key")
    suspend fun deleteTombstone(table: String, key: String)

    @Query("DELETE FROM tombstones WHERE seq <= :upTo")
    suspend fun dropTombstones(upTo: Long)

    // ------------------------------------------------------------ meta

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun watchMeta(key: String): Flow<String?>

    @Upsert
    suspend fun setMeta(row: MetaRow)
}

/** Keeps a message's ↔ task links in step with its task_ids (a write or a restore). */
suspend fun AppDao.linkMessage(row: MessageRow) {
    unlinkMessage(row.id)
    insertMessageTasks(row.taskIds.distinct().map { MessageTask(it, row.id) })
}
