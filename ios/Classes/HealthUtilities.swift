struct HealthUtilities {
    static func dateFromMilliseconds(_ milliseconds: Double) -> Date {
        return Date(timeIntervalSince1970: milliseconds / 1000.0)
    }
}