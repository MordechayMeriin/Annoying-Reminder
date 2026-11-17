package com.prominder.myapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/*
 * The phone's tables. They mirror the server's items (backend/app/tasks.py, thread.py, sessions.py,
 * preferences.py, user_settings.py) field for field, with the same names, so a row travels to and from the
 * backup as it is (see sync/). Times are text in the stored format (Times.kt).
 *
 * `seq` is the phone's own bookkeeping, never sent: the change number of the last local write (0 = as it
 * came from the backup). The upload sends rows whose seq is newer than the last upload.
 */

const val OPEN = "open"
const val DONE = "done"
const val CANCELLED = "cancelled"

@Serializable
@Entity(tableName = "tasks", indices = [Index("status"), Index("parent_id")])
data class TaskRow(
    @PrimaryKey val id: Long,
    @SerialName("parent_id") @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    val title: String,
    val description: String? = null,
    /** task | meeting | fence (a hard stop) */
    val kind: String = "task",
    /** 1 normal, 2 important, 3 critical */
    val priority: Int = 1,
    /** open | done | cancelled */
    val status: String = OPEN,
    @SerialName("due_at") @ColumnInfo(name = "due_at") val dueAt: String? = null,
    @SerialName("remind_at") @ColumnInfo(name = "remind_at") val remindAt: String? = null,
    @SerialName("remind_requested") @ColumnInfo(name = "remind_requested") val remindRequested: Boolean = false,
    /** daily | weekdays | weekly | monthly */
    val recurrence: String? = null,
    @SerialName("nag_count") @ColumnInfo(name = "nag_count") val nagCount: Int = 0,
    val escalated: Boolean = false,
    @SerialName("blocked_reason") @ColumnInfo(name = "blocked_reason") val blockedReason: String? = null,
    @SerialName("blocked_until") @ColumnInfo(name = "blocked_until") val blockedUntil: String? = null,
    @SerialName("last_notified_at") @ColumnInfo(name = "last_notified_at") val lastNotifiedAt: String? = null,
    @SerialName("created_at") @ColumnInfo(name = "created_at") val createdAt: String,
    @SerialName("completed_at") @ColumnInfo(name = "completed_at") val completedAt: String? = null,
    @SerialName("next_action") @ColumnInfo(name = "next_action") val nextAction: String? = null,
    @SerialName("estimate_min") @ColumnInfo(name = "estimate_min") val estimateMin: Int? = null,
    @SerialName("focus_date") @ColumnInfo(name = "focus_date") val focusDate: String? = null,
    @SerialName("focus_rank") @ColumnInfo(name = "focus_rank") val focusRank: Int? = null,
    @SerialName("resume_note") @ColumnInfo(name = "resume_note") val resumeNote: String? = null,
    @SerialName("parked_until") @ColumnInfo(name = "parked_until") val parkedUntil: String? = null,
    @SerialName("stale_offered_at") @ColumnInfo(name = "stale_offered_at") val staleOfferedAt: String? = null,
    @SerialName("avoid_asked_at") @ColumnInfo(name = "avoid_asked_at") val avoidAskedAt: String? = null,
    @SerialName("avoid_asks") @ColumnInfo(name = "avoid_asks") val avoidAsks: Int = 0,
    @SerialName("avoid_reset_at") @ColumnInfo(name = "avoid_reset_at") val avoidResetAt: String? = null,
    @Transient val seq: Long = 0,
)

/** What happened to a task (postponed, done, …): its history, and what the coach's rules count. */
@Serializable
@Entity(tableName = "events", primaryKeys = ["task_id", "created_at", "rid"], indices = [Index("kind", "created_at")])
data class EventRow(
    @SerialName("task_id") @ColumnInfo(name = "task_id") val taskId: Long,
    val kind: String,
    val note: String? = null,
    @SerialName("avoid_reason") @ColumnInfo(name = "avoid_reason") val avoidReason: String? = null,
    @SerialName("created_at") @ColumnInfo(name = "created_at") val createdAt: String,
    /** A short random suffix: two events of a task at the same moment stay two rows. */
    val rid: String,
    @Transient val seq: Long = 0,
)

/** The chat. */
@Serializable
@Entity(tableName = "messages", indices = [Index("kind")])
data class MessageRow(
    @PrimaryKey val id: Long,
    /** user | assistant */
    val role: String,
    val content: String,
    /** chat | morning | checkin | evening | nudge | reminder | meeting | event | session | reentry */
    val kind: String = "chat",
    @SerialName("task_id") @ColumnInfo(name = "task_id") val taskId: Long? = null,
    @SerialName("task_ids") @ColumnInfo(name = "task_ids") val taskIds: List<Long> = emptyList(),
    /** The reply buttons under the message, as JSON text (as the server stores them). */
    val chips: String? = null,
    /** The LLM that wrote it; null when code did. */
    val model: String? = null,
    @SerialName("created_at") @ColumnInfo(name = "created_at") val createdAt: String,
    @Transient val seq: Long = 0,
)

/** A focus session (▶ Start). Phase E runs them on the phone; until then they're kept and backed up. */
@Serializable
@Entity(tableName = "sessions", indices = [Index("task_id")])
data class SessionRow(
    @PrimaryKey val id: Long,
    @SerialName("task_id") @ColumnInfo(name = "task_id") val taskId: Long,
    @SerialName("started_at") @ColumnInfo(name = "started_at") val startedAt: String,
    @SerialName("planned_min") @ColumnInfo(name = "planned_min") val plannedMin: Int,
    @SerialName("ended_at") @ColumnInfo(name = "ended_at") val endedAt: String? = null,
    val outcome: String? = null,
    @SerialName("end_sent_at") @ColumnInfo(name = "end_sent_at") val endSentAt: String? = null,
    @SerialName("note_closed_at") @ColumnInfo(name = "note_closed_at") val noteClosedAt: String? = null,
    @Transient val seq: Long = 0,
)

/** Something you asked of the assistant in the chat ("shorter messages"). */
@Serializable
@Entity(tableName = "preferences")
data class PreferenceRow(
    @PrimaryKey val id: Long,
    val text: String,
    @SerialName("created_at") @ColumnInfo(name = "created_at") val createdAt: String,
    @Transient val seq: Long = 0,
)

/** A setting you changed (⚙). Settings at their default have no row. */
@Serializable
@Entity(tableName = "settings")
data class SettingRow(
    @PrimaryKey val key: String,
    val value: String,
    @SerialName("updated_at") @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @Transient val seq: Long = 0,
)

/** One day's Today's 3, in order. */
@Serializable
@Entity(tableName = "focus")
data class FocusRow(
    @PrimaryKey val date: String,
    val ids: List<Long> = emptyList(),
    @Transient val seq: Long = 0,
)

/** A deleted row, remembered until the next upload so the backup deletes it too. */
@Entity(tableName = "tombstones", primaryKeys = ["table_name", "row_key"])
data class Tombstone(
    @ColumnInfo(name = "table_name") val table: String,
    /** The row's key as JSON: an id, a text key, or an event's [task_id, created_at, rid]. */
    @ColumnInfo(name = "row_key") val key: String,
    val seq: Long,
)

/** The phone's own bookkeeping: change numbers, id counters, sync times. */
@Entity(tableName = "meta")
data class MetaRow(
    @PrimaryKey val key: String,
    val value: String,
)
