package com.dajiraj.steps_count

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class TimeStampUtils {
    companion object {
        /**
         * Get current UTC timestamp in milliseconds
         * @return UTC timestamp in milliseconds since epoch
         */
        fun getCurrentUtcTimestamp(): Long {
            return Instant.now().toEpochMilli()
        }

        fun getTodaysDate(): String {
            val todayUtc = LocalDate.now(ZoneOffset.UTC)
            return todayUtc.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        }

        /**
         * Get today's start timestamp (00:00:00 UTC) in milliseconds
         * @return UTC timestamp for start of today
         */
        fun getTodaysStartTimestamp(): Long {
            val todayUtc = LocalDate.now(ZoneOffset.UTC)
            return todayUtc.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }

        /**
         * Get today's end timestamp (23:59:59.999 UTC) in milliseconds
         * @return UTC timestamp for end of today
         */
        fun getTodaysEndTimestamp(): Long {
            val todayUtc = LocalDate.now(ZoneOffset.UTC)
            return todayUtc.atTime(23, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC).toEpochMilli()
        }

        /**
         * Format UTC timestamp for logging
         * @param timestamp UTC timestamp in milliseconds
         * @return Formatted UTC timestamp string
         */
        fun formatUtcTimestamp(timestamp: Long): String {
            return Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT)
        }
    }
}