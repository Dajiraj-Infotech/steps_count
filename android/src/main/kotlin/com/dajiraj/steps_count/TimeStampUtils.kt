package com.dajiraj.steps_count

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Timestamp helpers. IMPORTANT: all timestamps in this plugin are epoch milliseconds, which are
 * timezone-independent, so [convertLocalTimestampToUtc] and [convertUtcTimestampToLocal] are
 * intentional IDENTITY functions (same instant in, same value out). They exist only to make call
 * sites read clearly; do NOT "fix" them to shift by the zone offset, which would desync every stored
 * row from the query bounds by the offset (EC-47). Timezone is applied only at display time, in the
 * Dart layer (see TimelineModel.getDateTime).
 */
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
         * Get today's timestamp (start or end) in local time
         * @param isStartOfDay If true, returns start of day (00:00:00), if false returns end of day (23:59:59.999)
         * @return Local timestamp for start or end of today in milliseconds
         */
        fun getTodaysTimestamp(isStartOfDay: Boolean): Long {
            val todayLocal = LocalDate.now()
            return if (isStartOfDay) {
                todayLocal.atTime(0, 0, 0, 0).atZone(ZoneId.systemDefault()).toInstant()
                    .toEpochMilli()
            } else {
                todayLocal.atTime(23, 59, 59, 999_000_000).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }
        }

        /**
         * Convert local timestamp to UTC timestamp
         * @param localTimestamp Local timestamp in milliseconds
         * @return UTC timestamp in milliseconds
         */
        fun convertLocalTimestampToUtc(localTimestamp: Long): Long {
            val zone = ZoneId.systemDefault()
            val localZoned = Instant.ofEpochMilli(localTimestamp).atZone(zone)
            return localZoned.withZoneSameInstant(ZoneId.of("UTC")).toInstant().toEpochMilli()
        }

        /**
         * Convert UTC timestamp to Local timestamp
         * @param utcTimestamp UTC timestamp in milliseconds
         * @return Local timestamp in milliseconds
         */
        fun convertUtcTimestampToLocal(utcTimestamp: Long): Long {
            val zone = ZoneId.systemDefault()
            val utcZoned = Instant.ofEpochMilli(utcTimestamp).atZone(ZoneId.of("UTC"))
            return utcZoned.withZoneSameInstant(zone).toInstant().toEpochMilli()
        }
    }
}