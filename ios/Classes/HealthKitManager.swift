import Foundation
import HealthKit

public class HealthKitManager: NSObject {
    private let healthStore = HKHealthStore()
    
    // MARK: - HealthKit Availability
    
    public static func isHealthKitAvailable() -> Bool {
        return HKHealthStore.isHealthDataAvailable()
    }
    
    // MARK: - Permission Types
    public enum HealthDataType: String, CaseIterable {
        case stepCount = "stepCount"
        case distanceWalkingRunning = "distanceWalkingRunning"
        case activeEnergyBurned = "activeEnergyBurned"
        
        var hkQuantityType: HKQuantityType? {
            switch self {
            case .stepCount:
                return HKQuantityType.quantityType(forIdentifier: .stepCount)
            case .distanceWalkingRunning:
                return HKQuantityType.quantityType(forIdentifier: .distanceWalkingRunning)
            case .activeEnergyBurned:
                return HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned)
            default:
                return nil
            }
        }
        
        var hkObjectType: HKObjectType? {
            if let quantityType = hkQuantityType {
                return quantityType
            } else if let categoryType = hkCategoryType {
                return categoryType
            } else if let workoutType = hkWorkoutType {
                return workoutType
            }
            return nil
        }
    }
    
    // MARK: - Permission Status
    public enum PermissionStatus: String {
        case notDetermined = "notDetermined"
        case denied = "denied"
        case authorized = "authorized"
        case restricted = "restricted"
        case unknown = "unknown"
        
        init(from hkAuthorizationStatus: HKAuthorizationStatus) {
            switch hkAuthorizationStatus {
            case .notDetermined:
                self = .notDetermined
            case .sharingDenied:
                self = .denied
            case .sharingAuthorized:
                self = .authorized
            @unknown default:
                self = .unknown
            }
        }
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
            guard let dataType = HealthDataType(rawValue: dataTypeString),
                  let hkObjectType = dataType.hkObjectType else {
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
    public func checkPermissionStatus(for dataTypes: [String]) -> [String: String] {
        var permissionStatuses: [String: String] = [:]
        
        for dataTypeString in dataTypes {
            guard let dataType = HealthDataType(rawValue: dataTypeString),
                  let hkObjectType = dataType.hkObjectType else {
                permissionStatuses[dataTypeString] = PermissionStatus.unknown.rawValue
                continue
            }
            
            let authStatus = healthStore.authorizationStatus(for: hkObjectType)
            let permissionStatus = PermissionStatus(from: authStatus)
            permissionStatuses[dataTypeString] = permissionStatus.rawValue
        }
        
        return permissionStatuses
    }
    
    // MARK: - Single Permission Check
    public func checkPermissionStatus(for dataType: String) -> String {
        guard let healthDataType = HealthDataType(rawValue: dataType),
              let hkObjectType = healthDataType.hkObjectType else {
            return PermissionStatus.unknown.rawValue
        }
        
        let authStatus = healthStore.authorizationStatus(for: hkObjectType)
        let permissionStatus = PermissionStatus(from: authStatus)
        return permissionStatus.rawValue
    }
    
    // MARK: - Data Retrieval
    public func getStepCount(from startDate: Date, to endDate: Date, completion: @escaping (Double?, String?) -> Void) {
        guard let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            completion(nil, "Step count type not available")
            return
        }
        
        let predicate = HKQuery.predicateForSamples(withStart: startDate, end: endDate, options: .strictStartDate)
        
        let query = HKStatisticsQuery(quantityType: stepCountType,
                                    quantitySamplePredicate: predicate,
                                    options: .cumulativeSum) { _, result, error in
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
                
                let stepCount = sum.doubleValue(for: HKUnit.count())
                completion(stepCount, nil)
            }
        }
        
        healthStore.execute(query)
    }
    
    // MARK: - Available Data Types
    public static func getAvailableDataTypes() -> [String] {
        return HealthDataType.allCases.map { $0.rawValue }
    }
}
