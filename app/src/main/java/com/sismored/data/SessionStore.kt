package com.sismored.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SessionStore(context: Context) : SQLiteOpenHelper(context, "sismored_local.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                anomaly_count INTEGER NOT NULL,
                max_amplitude REAL NOT NULL,
                baseline REAL NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                elapsed_ms INTEGER NOT NULL,
                captured_at_ms INTEGER NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                amplitude REAL NOT NULL,
                baseline REAL NOT NULL,
                anomaly INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX samples_session_idx ON samples(session_id, elapsed_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun saveSession(
        startedAtMs: Long,
        durationMs: Long,
        anomalyCount: Int,
        maxAmplitude: Float,
        baseline: Float,
        points: List<MeasurementPoint>
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val sessionId = db.insertOrThrow("sessions", null, ContentValues().apply {
                put("started_at_ms", startedAtMs)
                put("duration_ms", durationMs)
                put("anomaly_count", anomalyCount)
                put("max_amplitude", maxAmplitude)
                put("baseline", baseline)
            })
            val statement = db.compileStatement(
                "INSERT INTO samples(session_id, elapsed_ms, captured_at_ms, x, y, z, amplitude, baseline, anomaly) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
            points.forEach { point ->
                statement.clearBindings()
                statement.bindLong(1, sessionId)
                statement.bindLong(2, point.elapsedMs)
                statement.bindLong(3, point.capturedAtMs)
                statement.bindDouble(4, point.x.toDouble())
                statement.bindDouble(5, point.y.toDouble())
                statement.bindDouble(6, point.z.toDouble())
                statement.bindDouble(7, point.amplitude.toDouble())
                statement.bindDouble(8, point.baseline.toDouble())
                statement.bindLong(9, if (point.anomaly) 1 else 0)
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
            return sessionId
        } finally {
            db.endTransaction()
        }
    }

    fun getSessions(): List<SessionSummary> = readableDatabase.rawQuery(
        "SELECT id, started_at_ms, duration_ms, anomaly_count, max_amplitude, baseline FROM sessions ORDER BY started_at_ms DESC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(SessionSummary(
                    id = cursor.getLong(0),
                    startedAtMs = cursor.getLong(1),
                    durationMs = cursor.getLong(2),
                    anomalyCount = cursor.getInt(3),
                    maxAmplitude = cursor.getFloat(4),
                    baseline = cursor.getFloat(5)
                ))
            }
        }
    }

    fun getPoints(sessionId: Long): List<MeasurementPoint> = readableDatabase.rawQuery(
        "SELECT elapsed_ms, captured_at_ms, x, y, z, amplitude, baseline, anomaly FROM samples WHERE session_id = ? ORDER BY elapsed_ms",
        arrayOf(sessionId.toString())
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(MeasurementPoint(
                    elapsedMs = cursor.getLong(0),
                    capturedAtMs = cursor.getLong(1),
                    x = cursor.getFloat(2),
                    y = cursor.getFloat(3),
                    z = cursor.getFloat(4),
                    amplitude = cursor.getFloat(5),
                    baseline = cursor.getFloat(6),
                    anomaly = cursor.getInt(7) != 0
                ))
            }
        }
    }
}
