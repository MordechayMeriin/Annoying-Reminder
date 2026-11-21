package com.prominder.myapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's data survives every schema change. A database is made exactly as an older version made it (from the
 * schema Room exported for that version, android/app/schemas/), then opened by the current version: Room runs the
 * migrations and then checks every table and index against the current schema, failing if one is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    /** A database as version [version] of the app created it (Gradle runs the tests in android/app/). */
    private fun createAt(version: Int): SQLiteDatabase {
        val schema = Json.parseToJsonElement(File("schemas/${AppDatabase::class.qualifiedName}/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    @Test
    fun `1 to 2 keeps the data, links messages to their tasks and drops a setting's stale tombstone`() = runTest {
        createAt(1).apply {
            execSQL("INSERT INTO tasks (id, title, kind, priority, status, remind_requested, nag_count, escalated, created_at, avoid_asks, seq) " +
                "VALUES (5, 'Report', 'task', 1, 'open', 0, 0, 0, '2026-11-17T08:00:00.000000Z', 0, 3)")
            for ((id, ids) in listOf(1 to "[5]", 2 to "[12,5]", 3 to "[]", 4 to "[15]")) {
                execSQL("INSERT INTO messages (id, role, content, kind, task_ids, created_at, seq) " +
                    "VALUES ($id, 'user', 'm$id', 'chat', '$ids', '2026-11-17T08:00:00.000000Z', 3)")
            }
            execSQL("INSERT INTO settings (`key`, value, seq) VALUES ('morning_time', '07:45', 4)")
            execSQL("INSERT INTO tombstones (table_name, row_key, seq) VALUES ('settings', '\"morning_time\"', 2)")
            execSQL("INSERT INTO tombstones (table_name, row_key, seq) VALUES ('settings', '\"quiet_start\"', 2)")
            close()
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries().build()
        val dao = db.dao()
        assertEquals("Report", dao.task(5)!!.title)   // opening it ran the migration and checked the schema
        assertEquals(listOf(2L, 1L), dao.messagesForTask(5, 10).map { it.id })
        assertEquals(listOf(4L), dao.messagesForTask(15, 10).map { it.id })
        // The tombstone for the setting that has a row is gone; the other stays.
        assertEquals(listOf("\"quiet_start\""), dao.changedTombstones(0, 10).map { it.key })
        assertEquals("07:45", dao.settings().single().value)
        db.close()
    }
}
