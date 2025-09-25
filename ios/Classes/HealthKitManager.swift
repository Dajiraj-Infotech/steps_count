import Foundation
import HealthKit

public class HealthKitManager: NSObject {
    private let healthStore = HKHealthStore()
    
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
    public func getStepCount(from startDate: Date, to endDate: Date, completion: @escaping (Int?, String?) -> Void) {
        guard let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            completion(nil, "Step count type not available")
            return
        }
        
        let predicate = HKQuery.predicateForSamples(withStart: startDate, end: endDate, options: .strictStartDate)
        
        let query = HKStatisticsQuery(
            quantityType: stepCountType,
            quantitySamplePredicate: predicate,
            options: .cumulativeSum
        ) { _, result, error in
            DispatchQueue.main.async {
                if let error = error {
                    completion(nil, error.localizedDescription)
                    return
                }
                
                guard let result = result,
                      let sum = result.sumQuantity() else {
                    completion(0, nil)
                    return
                }
                
                let stepCount = Int(sum.doubleValue(for: HKUnit.count()))
                completion(stepCount, nil)
            }
        }
        
        healthStore.execute(query)
    }
    
    public func getTodaysCount(completion: @escaping (Int?, String?) -> Void) {
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
        
        getStepCount(from: startOfDay, to: endOfDay, completion: completion)
    }
    
    // MARK: - Timeline Data Retrieval
    public func getTimeline(from startDate: Date, to endDate: Date, completion: @escaping ([[String: Any]]?, String?) -> Void) {
        guard let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            completion(nil, "Step count type not available")
            return
        }
        
        let predicate = HKQuery.predicateForSamples(withStart: startDate, end: endDate, options: .strictStartDate)
        let calendar = Calendar.current
        
        // Create interval components for hourly data
        var interval = DateComponents()
        interval.hour = 1
        
        let query = HKStatisticsCollectionQuery(
            quantityType: stepCountType,
            quantitySamplePredicate: predicate,
            options: .cumulativeSum,
            anchorDate: startDate,
            intervalComponents: interval
        )
        
        query.initialResultsHandler = { _, results, error in
            DispatchQueue.main.async {
                if let error = error {
                    completion(nil, error.localizedDescription)
                    return
                }
                
                guard let results = results else {
                    completion([], nil)
                    return
                }
                
                var timelineData: [[String: Any]] = []
                results.enumerateStatistics(from: startDate, to: endDate) { statistics, _ in
                    let stepCount = statistics.sumQuantity()?.doubleValue(for: HKUnit.count()) ?? 0
                    
                    // Only add entries where step count is not 0
                    if stepCount > 0 {
                        let timestamp = Int(statistics.startDate.timeIntervalSince1970 * 1000) // Convert to milliseconds
                        
                        let entry: [String: Any] = [
                            "step_count": Int(stepCount),
                            "timestamp": timestamp
                        ]
                        timelineData.append(entry)
                    }
                }         
                completion(timelineData, nil)
            }
        }
        
        healthStore.execute(query)
    }
}
