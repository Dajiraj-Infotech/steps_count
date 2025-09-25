import Flutter
import UIKit
import HealthKit

public class StepsCountPlugin: NSObject, FlutterPlugin {
    private let healthKitManager = HealthKitManager()
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "steps_count", binaryMessenger: registrar.messenger())
        let instance = StepsCountPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {            
        case "isHealthKitAvailable":
            result(HealthKitManager.isHealthKitAvailable())
            
        case "requestHealthKitPermissions":
            handleRequestPermissions(call: call, result: result)
            
        case "checkHealthKitPermissionStatus":
            handleCheckPermissionStatus(call: call, result: result)
            
        case "checkSingleHealthKitPermissionStatus":
            handleCheckSinglePermissionStatus(call: call, result: result)
            
        case "getStepCounts":
            handleGetStepCount(call: call, result: result)
            
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    // MARK: - Permission Methods
    private func handleRequestPermissions(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let dataTypes = arguments["dataTypes"] as? [String] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", 
                              message: "dataTypes parameter is required and must be an array of strings", 
                              details: nil))
            return
        }
        
        healthKitManager.requestPermissions(for: dataTypes) { success, error in
            if let error = error {
                result(FlutterError(code: "PERMISSION_ERROR", 
                                  message: error, 
                                  details: nil))
            } else {
                result(success)
            }
        }
    }
    
    private func handleCheckPermissionStatus(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let dataTypes = arguments["dataTypes"] as? [String] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", 
                              message: "dataTypes parameter is required and must be an array of strings", 
                              details: nil))
            return
        }
        
        let permissions = healthKitManager.checkPermissionStatus(for: dataTypes)
        result(permissions)
    }
    
    private func handleCheckSinglePermissionStatus(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let dataType = arguments["dataType"] as? String else {
            result(FlutterError(code: "INVALID_ARGUMENTS", 
                              message: "dataType parameter is required and must be a string", 
                              details: nil))
            return
        }
        
        let isAuthorized = healthKitManager.checkSinglePermissionStatus(for: dataType)
        result(isAuthorized)
    }
    
    // MARK: - Data Retrieval Methods    
    private func handleGetStepCount(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let startDateMs = arguments["startDate"] as? Int64,
              let endDateMs = arguments["endDate"] as? Int64 else {
            result(FlutterError(code: "INVALID_ARGUMENTS", 
                              message: "startDate and endDate parameters are required and must be timestamps in milliseconds", 
                              details: nil))
            return
        }
        
        let startDate = Date(timeIntervalSince1970: TimeInterval(startDateMs) / 1000.0)
        let endDate = Date(timeIntervalSince1970: TimeInterval(endDateMs) / 1000.0)
        
        healthKitManager.getStepCount(from: startDate, to: endDate) { stepCount, error in
            if let error = error {
                result(FlutterError(code: "DATA_ERROR", 
                                  message: error, 
                                  details: nil))
            } else {
                result(stepCount ?? 0)
            }
        }
    }
}
