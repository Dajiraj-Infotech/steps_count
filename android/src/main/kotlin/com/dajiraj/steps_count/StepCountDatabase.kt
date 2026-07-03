package com.dajiraj.steps_count

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * On corruption, RENAME the database file instead of deleting it (which the default handler does),
 * so the data is recoverable for forensics and the loss is visible rather than silent (EC-21). A
 * fresh empty DB is then created; its anchor is null, so the first sensor event re-anchors cleanly.
 */
private class RenameOnCorruptionHandler : DatabaseErrorHandler {
    override fun onCorruption(db: SQLiteDatabase) {
        val path = db.path
        try {
            db.close()
        } catch (_: Exception) {
        }
        if (path != null) {
            val src = File(path)
            val dest = File("$path.corrupt-${System.currentTimeMillis()}")
            val renamed = runCatching { src.renameTo(dest) }.getOrDefault(false)
            Log.e("StepCountDatabase", "db_corrupt: renamed to ${dest.name} (ok=$renamed)")
        }
    }
}

/**
 * Durable anchor recovered from the DB on start (Phase 2). The hardware cumulative counter is the
 * write-ahead log within a boot session; this row is the single durable cursor into it.
 *
 * @param anchorCounter Last cumulative sensor value durably written to `steps` (steps are credited
 *   up to here). NaN means "no anchor yet" (fresh install / voided baseline).
 * @param anchorElapsedMs elapsedRealtime of the event that set the anchor (for the rate-gate dt).
 * @param anchorWallMs Wall-clock time the anchor was set (forensics; interval start in Phase 3).
 * @param bootId BOOT_COUNT + ANDROID_ID fingerprint the anchor was recorded under.
 * @param lastRowEndMs Timestamp of the most recent step row (monotone watermark; used from Phase 3).
 */
data class AnchorState(
    val anchorCounter: Double,
    val anchorElapsedMs: Long,
    val anchorWallMs: Long,
    val bootId: String,
    val lastRowEndMs: Long
)

/**
 * One step interval to persist (Phase 3). Steps happened between [startTs] and [endTs] (UTC ms);
 * [endTs] is stored in the legacy `timestamp` column so existing readers keep working.
 *
 * @param source "live" (attributed to real event times), "gap"/"boot_gap" (a recovered downtime
 *   window, spread across the interval), or "legacy" (a pre-Phase-3 point row).
 * @param flags bit 1 = CLOCK_CLAMPED (timestamp adjusted for a clock change), bit 2 = LOW_ACCURACY.
 */
data class StepRow(
    val stepCount: Int,
    val startTs: Long,
    val endTs: Long,
    val source: String,
    val flags: Int
)

/**
 * SQLite database helper for storing step count data.
 *
 * Schema history:
 *  v1: id INTEGER PRIMARY KEY AUTOINCREMENT, step_count INTEGER, timestamp INTEGER
 *  v2: uuid TEXT PRIMARY KEY, step_count INTEGER, timestamp INTEGER
 *      (aligned with Google Health Connect and Apple HealthKit UUID identifiers)
 *  v3: adds tracker_state (single-row durable anchor) so steps and the anchor advance in one
 *      transaction (exactly-once accounting). WAL enabled.
 *  v4: adds interval columns to steps (start_timestamp, source, flags) so a row records the window
 *      the steps happened in, not just the flush instant.
 *
 * Use [getInstance] to obtain the process-wide singleton; it is never closed, which removes the
 * close/reopen/straggler-flush race entirely (EC-40).
 */
class StepCountDatabase(context: Context) :
    // Pass the context DIRECTLY (do NOT call .applicationContext): on a device-protected-storage
    // context, .applicationContext returns the credential-encrypted Application, which would silently
    // open the DB in the wrong storage. [getInstance] always passes an app-scoped DPS context.
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION, RenameOnCorruptionHandler()) {

    companion object {
        private const val TAG = "StepCountDatabase"
        const val DATABASE_NAME = "step_count.db"
        private const val DATABASE_VERSION = 4

        // Table and column names
        private const val TABLE_STEPS = "steps"
        private const val COLUMN_UUID = "uuid"
        private const val COLUMN_STEP_COUNT = "step_count"
        private const val COLUMN_TIMESTAMP = "timestamp"          // interval END (legacy name kept)
        private const val COLUMN_START_TIMESTAMP = "start_timestamp"
        private const val COLUMN_SOURCE = "source"
        private const val COLUMN_FLAGS = "flags"

        // Tracker-state table (single row, id = 1): the durable anchor.
        private const val TABLE_TRACKER = "tracker_state"
        private const val COL_ID = "id"
        private const val COL_ANCHOR_COUNTER = "anchor_counter"
        private const val COL_ANCHOR_ELAPSED = "anchor_elapsed_ms"
        private const val COL_ANCHOR_WALL = "anchor_wall_ms"
        private const val COL_BOOT_ID = "boot_id"
        private const val COL_LAST_ROW_END = "last_row_end_ms"

        // SQL statements
        private const val CREATE_TABLE_STEPS = """
            CREATE TABLE $TABLE_STEPS (
                $COLUMN_UUID TEXT PRIMARY KEY,
                $COLUMN_STEP_COUNT INTEGER NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_START_TIMESTAMP INTEGER NOT NULL DEFAULT 0,
                $COLUMN_SOURCE TEXT NOT NULL DEFAULT 'live',
                $COLUMN_FLAGS INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val CREATE_INDEX_TIMESTAMP = """
            CREATE INDEX idx_timestamp ON $TABLE_STEPS($COLUMN_TIMESTAMP)
        """

        private const val CREATE_TABLE_TRACKER = """
            CREATE TABLE $TABLE_TRACKER (
                $COL_ID INTEGER PRIMARY KEY CHECK ($COL_ID = 1),
                $COL_ANCHOR_COUNTER REAL,
                $COL_ANCHOR_ELAPSED INTEGER NOT NULL DEFAULT 0,
                $COL_ANCHOR_WALL INTEGER NOT NULL DEFAULT 0,
                $COL_BOOT_ID TEXT NOT NULL DEFAULT '',
                $COL_LAST_ROW_END INTEGER NOT NULL DEFAULT 0
            )
        """

        // Device-protected-storage flags (config only; never counting state).
        private const val FLAGS_PREFS = "steps_count_flags"
        private const val KEY_MOVED_TO_DPS = "moved_to_dps"
        private const val LEGACY_PREFS = "steps_count_prefs"

        @Volatile
        private var instance: StepCountDatabase? = null

        /**
         * Process-wide singleton opened on DEVICE-PROTECTED storage so the DB is readable before first
         * unlock and is excluded from Auto Backup (EC-2/EC-29). Never closed (EC-40). The one-time move
         * of the DB + legacy prefs from credential-encrypted storage happens here, so every caller
         * (the service manager AND service-less reads) sees the migrated data.
         */
        fun getInstance(context: Context): StepCountDatabase =
            instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }

        private fun create(context: Context): StepCountDatabase {
            val app = context.applicationContext
            val dps = app.createDeviceProtectedStorageContext()
            val flags = dps.getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE)
            if (!flags.getBoolean(KEY_MOVED_TO_DPS, false)) {
                try {
                    dps.moveDatabaseFrom(app, DATABASE_NAME)
                    dps.moveSharedPreferencesFrom(app, LEGACY_PREFS)
                    Log.d(TAG, "Moved DB + prefs to device-protected storage")
                } catch (e: Exception) {
                    Log.e(TAG, "DPS move failed (continuing on device-protected storage): ${e.message}")
                }
                flags.edit().putBoolean(KEY_MOVED_TO_DPS, true).apply()
            }
            return StepCountDatabase(dps)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL: a lost tail rolls back rows AND the anchor together (one transaction), so recovery is
        // always consistent (never a duplicate). synchronous=NORMAL is the standard WAL durability.
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=NORMAL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        // No catch: a throw rolls back the transaction SQLiteOpenHelper wraps onCreate in, so the
        // version is NOT committed and creation retries on next open, rather than silently leaving a
        // versioned-but-tableless DB that fails every insert forever (EC-22).
        db.execSQL(CREATE_TABLE_STEPS)
        db.execSQL(CREATE_INDEX_TIMESTAMP)
        db.execSQL(CREATE_TABLE_TRACKER)
        seedEmptyTracker(db)
        Log.d(TAG, "Database created successfully (v$DATABASE_VERSION)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No catch / no DROP fallback: a failed migration rolls back and retries next open, never
        // wipes real history (EC-22). onDowngrade is a data-preserving no-op (EC-60).
        Log.d(TAG, "Upgrading database from v$oldVersion to v$newVersion")
        if (oldVersion < 2) migrateV1ToV2(db)
        if (oldVersion < 3) migrateV2ToV3(db)
        if (oldVersion < 4) migrateV3ToV4(db)
    }

    /** v3 -> v4: add interval columns to steps; existing point rows become zero-width 'legacy' rows. */
    private fun migrateV3ToV4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE $TABLE_STEPS ADD COLUMN $COLUMN_START_TIMESTAMP INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE $TABLE_STEPS ADD COLUMN $COLUMN_SOURCE TEXT NOT NULL DEFAULT 'legacy'")
        db.execSQL("ALTER TABLE $TABLE_STEPS ADD COLUMN $COLUMN_FLAGS INTEGER NOT NULL DEFAULT 0")
        // Backfill: an old point row is a zero-width interval ending (and starting) at its timestamp.
        db.execSQL("UPDATE $TABLE_STEPS SET $COLUMN_START_TIMESTAMP = $COLUMN_TIMESTAMP WHERE $COLUMN_START_TIMESTAMP = 0")
        Log.d(TAG, "Migration v3->v4 complete: interval columns added")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Preserve data on an APK rollback instead of throwing "Can't downgrade" on every open (EC-60).
        Log.w(TAG, "Downgrade $oldVersion -> $newVersion ignored; data preserved")
    }

    /** v2 -> v3: add the single-row tracker_state anchor table. Steps table is unchanged. */
    private fun migrateV2ToV3(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_TRACKER)
        seedEmptyTracker(db)
        Log.d(TAG, "Migration v2->v3 complete: tracker_state added")
    }

    private fun seedEmptyTracker(db: SQLiteDatabase) {
        // anchor_counter NULL = "no anchor yet"; the first sensor event anchors with zero credit.
        val values = ContentValues().apply {
            put(COL_ID, 1)
            putNull(COL_ANCHOR_COUNTER)
            put(COL_ANCHOR_ELAPSED, 0L)
            put(COL_ANCHOR_WALL, 0L)
            put(COL_BOOT_ID, "")
            put(COL_LAST_ROW_END, 0L)
        }
        db.insertWithOnConflict(TABLE_TRACKER, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // Timeline column order for [cursorRowToMap]. `source`/`flags` are additive (readers may ignore).
    private val TIMELINE_PROJECTION = arrayOf(
        COLUMN_UUID, COLUMN_STEP_COUNT, COLUMN_TIMESTAMP, COLUMN_START_TIMESTAMP, COLUMN_SOURCE, COLUMN_FLAGS
    )

    private fun cursorRowToMap(c: Cursor): Map<String, Any> = mapOf(
        "uuid" to c.getString(0),
        "step_count" to c.getInt(1),
        "timestamp" to c.getLong(2),          // interval end (legacy key)
        "start_timestamp" to c.getLong(3),
        "source" to (c.getString(4) ?: "live"),
        "flags" to c.getInt(5)
    )

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

        // 2. Create the ERA-CORRECT v2 table (3 columns). It must NOT include the v4 interval columns,
        //    or the later v3->v4 ALTER ADD COLUMN would fail with "duplicate column" on a v1->v4 upgrade.
        db.execSQL(
            """
            CREATE TABLE $TABLE_STEPS (
                $COLUMN_UUID TEXT PRIMARY KEY,
                $COLUMN_STEP_COUNT INTEGER NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL
            )
            """.trimIndent()
        )

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

    /** Read the durable anchor, or null if the tracker row is missing. */
    fun readTrackerState(): AnchorState? {
        return try {
            readableDatabase.query(
                TABLE_TRACKER, null, "$COL_ID = 1", null, null, null, null
            ).use { c ->
                if (!c.moveToFirst()) return null
                val counterIdx = c.getColumnIndexOrThrow(COL_ANCHOR_COUNTER)
                AnchorState(
                    anchorCounter = if (c.isNull(counterIdx)) Double.NaN else c.getDouble(counterIdx),
                    anchorElapsedMs = c.getLong(c.getColumnIndexOrThrow(COL_ANCHOR_ELAPSED)),
                    anchorWallMs = c.getLong(c.getColumnIndexOrThrow(COL_ANCHOR_WALL)),
                    bootId = c.getString(c.getColumnIndexOrThrow(COL_BOOT_ID)) ?: "",
                    lastRowEndMs = c.getLong(c.getColumnIndexOrThrow(COL_LAST_ROW_END))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "readTrackerState failed: ${e.message}")
            null
        }
    }

    /** Update the anchor with NO step rows (re-anchor with zero credit). */
    fun setAnchor(anchor: AnchorState): Boolean {
        return try {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                upsertTracker(db, anchor)
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "setAnchor failed: ${e.message}")
            false
        }
    }

    /**
     * Insert [rows] step intervals AND advance the anchor to [anchor] in ONE transaction. Either all
     * rows plus the anchor commit, or nothing does. This is the exactly-once guarantee: the anchor
     * never advances past steps that were not durably written, and never lags behind steps that were
     * (EC-4/EC-5/EC-6/EC-12/EC-14). On failure the caller keeps the derived credit and retries.
     *
     * @return true on commit, false on any failure (rolled back).
     */
    fun commitFlush(rows: List<StepRow>, anchor: AnchorState): Boolean {
        if (rows.all { it.stepCount <= 0 }) return setAnchor(anchor)
        return try {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                for (r in rows) {
                    if (r.stepCount <= 0) continue
                    val values = ContentValues().apply {
                        put(COLUMN_UUID, UUID.randomUUID().toString())
                        put(COLUMN_STEP_COUNT, r.stepCount)
                        put(COLUMN_TIMESTAMP, r.endTs)
                        put(COLUMN_START_TIMESTAMP, r.startTs)
                        put(COLUMN_SOURCE, r.source)
                        put(COLUMN_FLAGS, r.flags)
                    }
                    if (db.insert(TABLE_STEPS, null, values) == -1L) {
                        throw IllegalStateException("step insert returned -1")
                    }
                }
                upsertTracker(db, anchor)
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "commitFlush failed (rolled back): ${e.message}")
            false
        }
    }

    private fun upsertTracker(db: SQLiteDatabase, a: AnchorState) {
        val values = ContentValues().apply {
            put(COL_ID, 1)
            if (a.anchorCounter.isNaN()) putNull(COL_ANCHOR_COUNTER) else put(COL_ANCHOR_COUNTER, a.anchorCounter)
            put(COL_ANCHOR_ELAPSED, a.anchorElapsedMs)
            put(COL_ANCHOR_WALL, a.anchorWallMs)
            put(COL_BOOT_ID, a.bootId)
            put(COL_LAST_ROW_END, a.lastRowEndMs)
        }
        db.insertWithOnConflict(TABLE_TRACKER, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Run a full WAL checkpoint so all WAL pages are merged into the main DB file.
     * Call before copying the database file for export. Uses this helper's connection.
     *
     * @return true if checkpoint ran successfully, false otherwise
     */
    fun runWalCheckpointFull(): Boolean {
        return try {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                cursor.moveToFirst()
            }
            Log.d(TAG, "WAL checkpoint (FULL) completed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WAL checkpoint failed: ${e.message}")
            false
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
     * Get timeline data: list of step entries with uuid, step_count, and timestamp.
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

            db.query(
                TABLE_STEPS, TIMELINE_PROJECTION, selection, selectionArgs, null, null,
                "$COLUMN_TIMESTAMP ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) timelineData.add(cursorRowToMap(cursor))
            }

            Log.d(TAG, "Timeline query: ${timelineData.size} entries (start: $startDate, end: $endDate)")
            timelineData
        } catch (e: Exception) {
            Log.e(TAG, "Error getting timeline data: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get timeline data after a specific timestamp.
     * Queries the indexed `timestamp` column directly with `WHERE timestamp > afterTimestamp`.
     *
     * @param afterTimestamp UTC timestamp in milliseconds; only entries strictly after this are returned
     * @return List of maps containing uuid, step_count, and timestamp
     */
    fun getTimelineDataAfter(afterTimestamp: Long): List<Map<String, Any>> {
        return try {
            val db = readableDatabase
            val timelineData = mutableListOf<Map<String, Any>>()

            db.query(
                TABLE_STEPS, TIMELINE_PROJECTION, "$COLUMN_TIMESTAMP > ?",
                arrayOf(afterTimestamp.toString()), null, null, "$COLUMN_TIMESTAMP ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) timelineData.add(cursorRowToMap(cursor))
            }

            Log.d(TAG, "Timeline-after query: ${timelineData.size} entries after $afterTimestamp")
            timelineData
        } catch (e: Exception) {
            Log.e(TAG, "Error getting timeline data after timestamp: ${e.message}")
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