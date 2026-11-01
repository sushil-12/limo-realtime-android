package com.limo1800.realtime.outbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The outbox on plain SQLite. Room would need kapt in one app and ksp in the other; one
 * table doesn't justify that. Survives process death; ordered by the autoincrement id.
 */
class SqliteOutboxStore(context: Context) : OutboxStore {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, "limo_rt_outbox.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE outbox (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ride_id INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    op_key TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL DEFAULT 'PENDING',
                    last_error TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX outbox_ride ON outbox(ride_id, id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun insert(rideId: Long, kind: OutboxKind, payload: JsonObject, key: String, createdAtMs: Long): Long {
        val values = ContentValues().apply {
            put("ride_id", rideId)
            put("kind", kind.name)
            put("payload", payload.toString())
            put("op_key", key)
            put("created_at", createdAtMs)
        }
        val id = helper.writableDatabase.insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id != -1L) return id
        helper.readableDatabase.rawQuery("SELECT id FROM outbox WHERE op_key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else -1
        }
    }

    @Synchronized
    override fun all(): List<OutboxEntry> {
        val out = mutableListOf<OutboxEntry>()
        helper.readableDatabase.rawQuery(
            "SELECT id, ride_id, kind, payload, op_key, created_at, attempts, state, last_error FROM outbox ORDER BY id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val payload = runCatching { json.parseToJsonElement(c.getString(3)) as? JsonObject }.getOrNull() ?: continue
                out += OutboxEntry(
                    id = c.getLong(0),
                    rideId = c.getLong(1),
                    kind = runCatching { OutboxKind.valueOf(c.getString(2)) }.getOrNull() ?: continue,
                    payload = payload,
                    key = c.getString(4),
                    createdAtMs = c.getLong(5),
                    attempts = c.getInt(6),
                    state = runCatching { OutboxState.valueOf(c.getString(7)) }.getOrDefault(OutboxState.PENDING),
                    lastError = if (c.isNull(8)) null else c.getString(8),
                )
            }
        }
        return out
    }

    @Synchronized
    override fun delete(id: Long) {
        helper.writableDatabase.delete("outbox", "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    override fun update(id: Long, attempts: Int, state: OutboxState, lastError: String?) {
        val values = ContentValues().apply {
            put("attempts", attempts)
            put("state", state.name)
            put("last_error", lastError)
        }
        helper.writableDatabase.update("outbox", values, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    override fun deleteRide(rideId: Long, kind: OutboxKind?) {
        if (kind == null) {
            helper.writableDatabase.delete("outbox", "ride_id = ?", arrayOf(rideId.toString()))
        } else {
            helper.writableDatabase.delete("outbox", "ride_id = ? AND kind = ?", arrayOf(rideId.toString(), kind.name))
        }
    }

    @Synchronized
    override fun clear() {
        helper.writableDatabase.delete("outbox", null, null)
    }
}

/** For tests and previews. */
class InMemoryOutboxStore : OutboxStore {
    private val rows = linkedMapOf<Long, OutboxEntry>()
    private var nextId = 1L

    @Synchronized
    override fun insert(rideId: Long, kind: OutboxKind, payload: JsonObject, key: String, createdAtMs: Long): Long {
        rows.values.firstOrNull { it.key == key }?.let { return it.id }
        val id = nextId++
        rows[id] = OutboxEntry(id, rideId, kind, payload, key, createdAtMs, 0, OutboxState.PENDING, null)
        return id
    }

    @Synchronized override fun all(): List<OutboxEntry> = rows.values.sortedBy { it.id }
    @Synchronized override fun delete(id: Long) { rows.remove(id) }

    @Synchronized
    override fun update(id: Long, attempts: Int, state: OutboxState, lastError: String?) {
        rows[id]?.let { rows[id] = it.copy(attempts = attempts, state = state, lastError = lastError) }
    }

    @Synchronized
    override fun deleteRide(rideId: Long, kind: OutboxKind?) {
        rows.values.removeAll { it.rideId == rideId && (kind == null || it.kind == kind) }
    }

    @Synchronized override fun clear() = rows.clear()
}
