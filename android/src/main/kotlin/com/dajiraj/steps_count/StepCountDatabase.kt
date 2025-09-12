package com.dajiraj.steps_count

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite database helper for storing step count data
 */
class StepCountDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "StepCountDatabase"
        private const val DATABASE_NAME = "step_count.db"
        private const val DATABASE_VERSION = 1

        // Table and column names
        private const val TABLE_STEPS = "steps"
        private const val COLUMN_ID = "id"
        private const val COLUMN_STEP_COUNT = "step_count"
        private const val COLUMN_TIMESTAMP = "timestamp"

        // SQL statements
        private const val CREATE_TABLE_STEPS = """
            CREATE TABLE $TABLE_STEPS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
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
            Log.d(TAG, "Database created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating database: ${e.message}")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            // For now, simple upgrade strategy - drop and recreate
            db.execSQL("DROP TABLE IF EXISTS $TABLE_STEPS")
            onCreate(db)
            Log.d(TAG, "Database upgraded from version $oldVersion to $newVersion")
        } catch (e: Exception) {
            Log.e(TAG, "Error upgrading database: ${e.message}")
        }
    }

    /**
     * Insert a new step count entry
     * @param stepCount Number of steps to add
     * @param timestamp UTC timestamp in milliseconds
     * @return Row ID of inserted record, or -1 if error
     */
    fun insertStepCount(stepCount: Int, timestamp: Long): Long {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COLUMN_STEP_COUNT, stepCount)
                put(COLUMN_TIMESTAMP, timestamp)
            }

            val rowId = db.insert(TABLE_STEPS, null, values)

            if (rowId != -1L) {
                Log.d(
                    TAG, "Inserted $stepCount steps at $timestamp (ID: $rowId)"
                )
            } else {
                Log.e(TAG, "Failed to insert step count")
            }

            rowId
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting step count: ${e.message}")
            -1L
        }
    }

    /**
     * Get total step count for a date range
     * @param startDate Start date in milliseconds (nullable)
     * @param endDate End date in milliseconds (nullable)
     * @return Total steps in the specified range
     */
    fun getStepCount(startDate: Long? = null, endDate: Long? = null): Int {
        return try {
            val db = readableDatabase

            // Build query based on date parameters
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
            Log.d(
                TAG, "Query result: $totalSteps steps (start: $startDate, end: $endDate)"
            )
            totalSteps
        } catch (e: Exception) {
            Log.e(TAG, "Error getting step count: ${e.message}")
            0
        }
    }

    /**
     * Build SQL query components for date filtering
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