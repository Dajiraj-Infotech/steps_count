import Foundation
import HealthKit

/// How step samples are filtered when reading from HealthKit.
enum StepSourceFilterMode {
    /// Steps from Apple system sources (`com.apple.*`), excluding user-entered samples.
    case appleDevicesOnly
    /// Legacy behavior: sum every step sample in the date range.
    case allSources
    /// Only samples whose `HKSource.bundleIdentifier` is in this set.
    case bundleIdentifiers(Set<String>)
}

public class HealthKitManager: NSObject {
    private let healthStore = HKHealthStore()

    /// Parses filter from method-channel arguments. Omitted keys keep the default:
    /// **Apple devices only** (matches existing apps after upgrade without Dart changes).
    static func stepSourceFilterMode(from arguments: [String: Any]?) -> StepSourceFilterMode {
        guard let arguments = arguments else {
            return .appleDevicesOnly
        }
        if arguments["includeAllSources"] as? Bool == true {
            return .allSources
        }
        if let ids = arguments["sourceBundleIdentifiers"] as? [String] {
            let trimmed = Set(ids.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })
            if !trimmed.isEmpty {
                return .bundleIdentifiers(trimmed)
            }
        }
        return .appleDevicesOnly
    }

    /// `true` for samples recorded by Apple system apps/sources, excluding user-entered Health data.
    static func isAppleDeviceStepSample(_ sample: HKQuantitySample) -> Bool {
        if let userEntered = sample.metadata?[HKMetadataKeyWasUserEntered] as? Bool, userEntered {
            return false
        }
        if let num = sample.metadata?[HKMetadataKeyWasUserEntered] as? NSNumber, num.boolValue {
            return false
        }
        let bid = sample.sourceRevision.source.bundleIdentifier
        return bid.hasPrefix("com.apple.")
    }

    static func filterStepSamples(_ samples: [HKQuantitySample], mode: StepSourceFilterMode) -> [HKQuantitySample] {
        switch mode {
        case .allSources:
            return samples
        case .appleDevicesOnly:
            return samples.filter { isAppleDeviceStepSample($0) }
        case .bundleIdentifiers(let ids):
            return samples.filter { ids.contains($0.sourceRevision.source.bundleIdentifier) }
        }
    }
    
    // MARK: - HealthKit Availability
    public static func isHealthKitAvailable() -> Bool {
        return HKHealthStore.isHealthDataAvailable()
    }
    
    // MARK: - Health Data Type Mapping
    /// Maps Flutter health data type strings to HealthKit types
    private func getHKQuantityType(for identifier: String) -> HKQuantityType? {
        switch identifier {
        case "stepCount":
            return HKQuantityType.quantityType(forIdentifier: .stepCount)
        case "distanceWalkingRunning":
            return HKQuantityType.quantityType(forIdentifier: .distanceWalkingRunning)
        case "activeEnergyBurned":
            return HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned)
        default:
            return nil
        }
    }
    
    private func getHKObjectType(for identifier: String) -> HKObjectType? {
        if let quantityType = getHKQuantityType(for: identifier) {
            return quantityType
        }
        return nil
    }
    
    // MARK: - Permission Status Helper
    private func isAuthorized(status: HKAuthorizationStatus) -> Bool {
        return status == .sharingAuthorized
    }
    
    // MARK: - Permission Request
    public func requestPermissions(for dataTypes: [String], completion: @escaping (Bool, String?) -> Void) {
        guard Self.isHealthKitAvailable() else {
            completion(false, "HealthKit is not available on this device")
            return
        }
        
        var readTypes = Set<HKObjectType>()
        var writeTypes = Set<HKSampleType>()
        
        for dataTypeString in dataTypes {
            guard let hkObjectType = getHKObjectType(for: dataTypeString) else {
                completion(false, "Invalid data type: \(dataTypeString)")
                return
            }
            
            readTypes.insert(hkObjectType)
            
            // Add to write types if it's a sample type (for recording data)
            if let sampleType = hkObjectType as? HKSampleType {
                writeTypes.insert(sampleType)
            }
        }
        
        healthStore.requestAuthorization(toShare: writeTypes, read: readTypes) { [weak self] success, error in
            DispatchQueue.main.async {
                if let error = error {
                    completion(false, error.localizedDescription)
                } else {
                    completion(success, nil)
                }
            }
        }
    }
    
    // MARK: - Permission Check  
    public func checkPermissionStatus(for dataTypes: [String]) -> [String: Bool] {
        var permissions: [String: Bool] = [:]
        
        for dataTypeString in dataTypes {
            guard let hkObjectType = getHKObjectType(for: dataTypeString) else {
                permissions[dataTypeString] = false
                continue
            }
            
            let authStatus = healthStore.authorizationStatus(for: hkObjectType)
            permissions[dataTypeString] = isAuthorized(status: authStatus)
        }
        
        return permissions
    }
    
    // MARK: - Single Permission Check
    public func checkSinglePermissionStatus(for dataType: String) -> Bool {
        guard let hkObjectType = getHKObjectType(for: dataType) else {
            return false
        }
        
        let authStatus = healthStore.authorizationStatus(for: hkObjectType)
        return isAuthorized(status: authStatus)
    }
    
    // MARK: - Data Retrieval
    public func getStepCount(
        from startDate: Date,
        to endDate: Date,
        mode: StepSourceFilterMode = .appleDevicesOnly,
        completion: @escaping (Int?, String?) -> Void
    ) {
        guard let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            completion(nil, "Step count type not available")
            return
        }
        
        let predicate = HKQuery.predicateForSamples(withStart: startDate, end: endDate, options: .strictStartDate)

        let query = HKSampleQuery(sampleType: stepCountType, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: nil) { _, samples, error in
            DispatchQueue.main.async {
                if let error = error {
                    completion(nil, error.localizedDescription)
                    return
                }
                guard let samples = samples as? [HKQuantitySample] else {
                    completion(0, nil)
                    return
                }

                let filtered = Self.filterStepSamples(samples, mode: mode)
                let totalSteps = filtered.reduce(0) { sum, sample in
                    sum + Int(sample.quantity.doubleValue(for: HKUnit.count()))
                }
                completion(totalSteps, nil)
            }
        }

        healthStore.execute(query)
    }

    
    public func getTodaysCount(
        mode: StepSourceFilterMode = .appleDevicesOnly,
        completion: @escaping (Int?, String?) -> Void
    ) {
        let calendar = Calendar.current
        let now = Date()
        
        guard let startOfDay = calendar.startOfDay(for: now) as Date? else {
            completion(nil, "Failed to get start of day")
            return
        }
        
        guard let endOfDay = calendar.date(bySettingHour: 23, minute: 59, second: 59, of: now) else {
            completion(nil, "Failed to get end of day")
            return
        }
        
        getStepCount(from: startOfDay, to: endOfDay, mode: mode, completion: completion)
    }
    
    // MARK: - Timeline Data Retrieval
    public func getTimeline(
        from startDate: Date,
        to endDate: Date,
        mode: StepSourceFilterMode = .appleDevicesOnly,
        completion: @escaping ([[String: Any]]?, String?) -> Void
    ) {
        guard let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            completion(nil, "Step count type not available")
            return
        }

        let predicate = HKQuery.predicateForSamples(withStart: startDate, end: endDate, options: .strictStartDate)

        let query = HKSampleQuery(
            sampleType: stepCountType, 
            predicate: predicate, 
            limit: HKObjectQueryNoLimit, 
            sortDescriptors: nil
        ) { query, samples, error in
            DispatchQueue.main.async {
                if let error = error {
                    completion(nil, error.localizedDescription)
                    return
                }

                guard let samples = samples as? [HKQuantitySample] else {
                    completion([], nil)
                    return
                }

                let filtered = Self.filterStepSamples(samples, mode: mode)
                var timelineData: [[String: Any]] = []

                for sample in filtered {
                    let stepCount = sample.quantity.doubleValue(for: HKUnit.count())
                    let timestamp = Int(sample.startDate.timeIntervalSince1970 * 1000)

                    timelineData.append([
                        "uuid": sample.uuid.uuidString,
                        "step_count": Int(stepCount),
                        "timestamp": timestamp
                    ])
                }

                completion(timelineData, nil)
            }
        }

        healthStore.execute(query)
    }

    // MARK: - Timeline After Timestamp
    /// Returns all step timeline samples recorded strictly after [afterTimestamp].
    /// Uses `Date.distantFuture` as the upper bound so no end date is required.
    public func getTimelineAfter(
        afterTimestamp: Date,
        mode: StepSourceFilterMode = .appleDevicesOnly,
        completion: @escaping ([[String: Any]]?, String?) -> Void
    ) {
        guard let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            completion(nil, "Step count type not available")
            return
        }

        let predicate = HKQuery.predicateForSamples(
            withStart: afterTimestamp,
            end: Date.distantFuture,
            options: .strictStartDate
        )

        let sortByDate = NSSortDescriptor(
            key: HKSampleSortIdentifierStartDate,
            ascending: true
        )

        let query = HKSampleQuery(
            sampleType: stepCountType,
            predicate: predicate,
            limit: HKObjectQueryNoLimit,
            sortDescriptors: [sortByDate]
        ) { _, samples, error in
            DispatchQueue.main.async {
                if let error = error {
                    completion(nil, error.localizedDescription)
                    return
                }

                guard let samples = samples as? [HKQuantitySample] else {
                    completion([], nil)
                    return
                }

                let filtered = Self.filterStepSamples(samples, mode: mode)
                let timelineData: [[String: Any]] = filtered.map { sample in
                    [
                        "uuid": sample.uuid.uuidString,
                        "step_count": Int(sample.quantity.doubleValue(for: HKUnit.count())),
                        "timestamp": Int(sample.startDate.timeIntervalSince1970 * 1000)
                    ]
                }

                completion(timelineData, nil)
            }
        }

        healthStore.execute(query)
    }

    // MARK: - Step sources (for UI selection)
    /// Lists `HKSource` entries that have step-count data. Optional date range limits which samples are considered.
    public func getStepSources(
        from startDate: Date?,
        to endDate: Date?,
        completion: @escaping ([[String: Any]]?, String?) -> Void
    ) {
        guard let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            completion(nil, "Step count type not available")
            return
        }

        let samplePredicate: NSPredicate?
        if let start = startDate, let end = endDate {
            samplePredicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        } else {
            samplePredicate = nil
        }

        let query = HKSourceQuery(sampleType: stepCountType, samplePredicate: samplePredicate) { _, sources, error in
            DispatchQueue.main.async {
                if let error = error {
                    completion(nil, error.localizedDescription)
                    return
                }
                guard let sources = sources else {
                    completion([], nil)
                    return
                }
                let list: [[String: Any]] = sources.map { source in
                    [
                        "name": source.name,
                        "bundleIdentifier": source.bundleIdentifier
                    ]
                }
                .sorted {
                    let n0 = $0["name"] as? String ?? ""
                    let n1 = $1["name"] as? String ?? ""
                    return n0.localizedCaseInsensitiveCompare(n1) == .orderedAscending
                }
                completion(list, nil)
            }
        }

        healthStore.execute(query)
    }
}
