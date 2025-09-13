package com.dajiraj.steps_count

/**
 * Enum representing timezone types for timeline data
 */
enum class TimeZoneType {
    LOCAL,
    UTC;

    /**
     * Returns true if this is local timezone
     */
    val isLocal: Boolean
        get() = this == LOCAL

    /**
     * Returns true if this is UTC timezone
     */
    val isUtc: Boolean
        get() = this == UTC

    /**
     * Converts the enum to a boolean for backward compatibility
     * Returns true for local, false for UTC
     */
    fun toBool(): Boolean = isLocal

    /**
     * Returns a human-readable string representation
     */
    val displayName: String
        get() = when (this) {
            LOCAL -> "Local"
            UTC -> "UTC"
        }

    companion object {
        /**
         * Creates TimeZoneType from boolean
         * true = local, false = UTC
         */
        fun fromBool(isLocal: Boolean): TimeZoneType {
            return if (isLocal) LOCAL else UTC
        }

        /**
         * Creates TimeZoneType from string (case-insensitive)
         * Supports: "local", "utc", "LOCAL", "UTC"
         */
        fun fromString(value: String?): TimeZoneType {
            return when (value?.lowercase()) {
                "local" -> LOCAL
                "utc" -> UTC
                else -> LOCAL // Default to local
            }
        }
    }

    override fun toString(): String = displayName
}
