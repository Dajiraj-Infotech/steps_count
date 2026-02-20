package com.dajiraj.steps_count

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.UUID

/**
 * SQLite database helper for storing step count data.
 *
 * Schema history:
 *  v1 — id INTEGER PRIMARY KEY AUTOINCREMENT, step_count INTEGER, timestamp INTEGER
 *  v2 — uuid TEXT PRIMARY KEY, step_count INTEGER, timestamp INTEGER
 *       (aligned with Google Health Connect and Apple HealthKit UUID identifiers)
 */
class StepCountDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "StepCountDatabase"
        private const val DATABASE_NAME = "step_count.db"
        private const val DATABASE_VERSION = 2

        // Table and column names
        private const val TABLE_STEPS = "steps"
        private const val COLUMN_UUID = "uuid"
        private const val COLUMN_STEP_COUNT = "step_count"
        private const val COLUMN_TIMESTAMP = "timestamp"

        // SQL statements
        private const val CREATE_TABLE_STEPS = """
            CREATE TABLE $TABLE_STEPS (
                $COLUMN_UUID TEXT PRIMARY KEY,
                $COLUMN_STEP_COUNT INTEGER NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL
            )
        """

        private const val CREATE_INDEX_TIMESTAMP = """
            CREATE INDEX idx_timestamp ON $TABLE_STEPS($COLUMN_TIMESTAMP)
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        try {
            db.execSQL(CREATE_TABLE_STEPS)
            db.execSQL(CREATE_INDEX_TIMESTAMP)
            Log.d(TAG, "Database created successfully (v$DATABASE_VERSION)")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating database: ${e.message}")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.d(TAG, "Upgrading database from v$oldVersion to v$newVersion")
        try {
            if (oldVersion < 2) {
                migrateV1ToV2(db)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error upgrading database: ${e.message}")
            // Last-resort fallback: recreate from scratch
            db.execSQL("DROP TABLE IF EXISTS $TABLE_STEPS")
            onCreate(db)
        }
    }

    /**
     * Migration v1 → v2:
     * Replaces INTEGER AUTOINCREMENT `id` column with TEXT `uuid` PRIMARY KEY.
     *
     * Uses the safe rename+copy+drop pattern because SQLite (pre-API 35)
     * does not support DROP COLUMN. Existing rows get fresh UUIDs via
     * SQLite's hex(randomblob()) to avoid needing a Kotlin cursor loop.
     */
    private fun migrateV1ToV2(db: SQLiteDatabase) {
        // 1. Rename old table
        db.execSQL("ALTER TABLE $TABLE_STEPS RENAME TO ${TABLE_STEPS}_old")

        // 2. Create new table with uuid TEXT PRIMARY KEY
        db.execSQL(CREATE_TABLE_STEPS)

        // 3. Copy existing rows, generating a UUID for each via SQLite's randomblob.
        //    Format: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx  (RFC 4122 v4 shape)
        db.execSQL(
            """
            INSERT INTO $TABLE_STEPS ($COLUMN_UUID, $COLUMN_STEP_COUNT, $COLUMN_TIMESTAMP)
            SELECT
                lower(
                    hex(randomblob(4)) || '-' ||
                    hex(randomblob(2)) || '-' ||
                    '4' || substr(hex(randomblob(2)), 2) || '-' ||
                    substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' ||
                    hex(randomblob(6))
                ),
                $COLUMN_STEP_COUNT,
                $COLUMN_TIMESTAMP
            FROM ${TABLE_STEPS}_old
            """.trimIndent()
        )

        // 4. Drop old table
        db.execSQL("DROP TABLE ${TABLE_STEPS}_old")

        // 5. Recreate index
        db.execSQL("DROP INDEX IF EXISTS idx_timestamp")
        db.execSQL(CREATE_INDEX_TIMESTAMP)

        Log.d(TAG, "Migration v1→v2 complete: id INTEGER replaced with uuid TEXT")
    }

    /**
     * Insert a new step count entry.
     * Generates a UUID automatically if none is provided.
     *
     * @param stepCount Number of steps to record
     * @param timestamp UTC timestamp in milliseconds
     * @param uuid Optional UUID string; a random UUID is generated if omitted
     * @return The UUID string of the inserted record, or null on error
     */
    fun insertStepCount(
        stepCount: Int,
        timestamp: Long,
        uuid: String = UUID.randomUUID().toString()
    ): String? {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COLUMN_UUID, uuid)
                put(COLUMN_STEP_COUNT, stepCount)
                put(COLUMN_TIMESTAMP, timestamp)
            }

            val rowId = db.insert(TABLE_STEPS, null, values)

            if (rowId != -1L) {
                Log.d(TAG, "Inserted $stepCount steps at $timestamp (UUID: $uuid)")
                uuid
            } else {
                Log.e(TAG, "Failed to insert step count")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting step count: ${e.message}")
            null
        }
    }

    /**
     * Get total step count for a date range.
     *
     * @param startDate Start date in milliseconds (nullable)
     * @param endDate End date in milliseconds (nullable)
     * @return Total steps in the specified range
     */
    fun getStepCount(startDate: Long? = null, endDate: Long? = null): Int {
        return try {
            val db = readableDatabase
            val (selection, selectionArgs) = buildDateQuery(startDate, endDate)

            val cursor = db.query(
                TABLE_STEPS,
                arrayOf("SUM($COLUMN_STEP_COUNT) AS total_steps"),
                selection,
                selectionArgs,
                null,
                null,
                null
            )

            var totalSteps = 0
            if (cursor.moveToFirst()) {
                totalSteps = cursor.getInt(0)
            }
            cursor.close()
            Log.d(TAG, "Query result: $totalSteps steps (start: $startDate, end: $endDate)")
            totalSteps
        } catch (e: Exception) {
            Log.e(TAG, "Error getting step count: ${e.message}")
            0
        }
    }

    /**
     * Get timeline data — list of step entries with uuid, step_count, and timestamp.
     *
     * @param startDate Start date in milliseconds (nullable)
     * @param endDate End date in milliseconds (nullable)
     * @return List of maps containing uuid, step_count, and timestamp
     */
    fun getTimelineData(startDate: Long? = null, endDate: Long? = null): List<Map<String, Any>> {
        return try {
            val db = readableDatabase
            val timelineData = mutableListOf<Map<String, Any>>()
            val (selection, selectionArgs) = buildDateQuery(startDate, endDate)

            val cursor = db.query(
                TABLE_STEPS,
                arrayOf(COLUMN_UUID, COLUMN_STEP_COUNT, COLUMN_TIMESTAMP),
                selection,
                selectionArgs,
                null,
                null,
                "$COLUMN_TIMESTAMP ASC"
            )

            while (cursor.moveToNext()) {
                val uuid = cursor.getString(0)
                val stepCount = cursor.getInt(1)
                val timestamp = cursor.getLong(2)

                timelineData.add(
                    mapOf(
                        "uuid" to uuid,
                        "step_count" to stepCount,
                        "timestamp" to timestamp
                    )
                )
            }
            cursor.close()

            Log.d(TAG, "Timeline query: ${timelineData.size} entries (start: $startDate, end: $endDate)")
            timelineData
        } catch (e: Exception) {
            Log.e(TAG, "Error getting timeline data: ${e.message}")
            emptyList()
        }
    }

    /**
     * Build SQL query components for date filtering.
     */
    private fun buildDateQuery(startDate: Long?, endDate: Long?): Pair<String?, Array<String>?> {
        return when {
            startDate != null && endDate != null -> {
                Pair(
                    "$COLUMN_TIMESTAMP >= ? AND $COLUMN_TIMESTAMP <= ?",
                    arrayOf(startDate.toString(), endDate.toString())
                )
            }
            startDate != null -> {
                Pair("$COLUMN_TIMESTAMP >= ?", arrayOf(startDate.toString()))
            }
            endDate != null -> {
                Pair("$COLUMN_TIMESTAMP <= ?", arrayOf(endDate.toString()))
            }
            else -> {
                Pair(null, null)
            }
        }
    }
}