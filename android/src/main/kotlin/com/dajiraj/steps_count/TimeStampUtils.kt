package com.dajiraj.steps_count

import java.time.Instant
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