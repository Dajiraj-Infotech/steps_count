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
            
        case "getTodaysCount":
            handleGetTodaysCount(call: call, result: result)

        case "getStepCount":
            handleGetStepCount(call: call, result: result)
            
        case "getTimeline":
            handleGetTimeline(call: call, result: result)
            
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
    private func handleGetTodaysCount(call: FlutterMethodCall, result: @escaping FlutterResult) {
        healthKitManager.getTodaysCount() { stepCount, error in
            if let error = error {
                result(
                    FlutterError(
                        code: "DATA_ERROR", 
                        message: error, 
                        details: nil
                    )
                )
            } else {
                result(stepCount ?? 0)
            }
        }
    }

    private func handleGetStepCount(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? NSDictionary
        let startDate = (arguments?["startDate"] as? NSNumber)?.doubleValue ?? 0
        let endDate = (arguments?["endDate"] as? NSNumber)?.doubleValue ?? 0
        guard startDate > 0 && endDate > 0 else {
            result(
                FlutterError(
                    code: "INVALID_ARGUMENTS", 
                    message: "startDate and endDate parameters are required and must be timestamps in milliseconds", 
                    details: nil
                )
            )
            return
        }
        
        // Convert dates from milliseconds to Date()
        let dateFrom = HealthUtilities.dateFromMilliseconds(startDate)
        let dateTo = HealthUtilities.dateFromMilliseconds(endDate)
        
        healthKitManager.getStepCount(from: dateFrom, to: dateTo) { stepCount, error in
            if let error = error {
                result(
                    FlutterError(
                        code: "DATA_ERROR", 
                        message: error, 
                        details: nil
                    )
                )
            } else {
                result(stepCount ?? 0)
            }
        }
    }
    
    private func handleGetTimeline(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? NSDictionary
        let startDate = (arguments?["startDate"] as? NSNumber)?.doubleValue ?? 0
        let endDate = (arguments?["endDate"] as? NSNumber)?.doubleValue ?? 0
        
        guard startDate > 0 && endDate > 0 else {
            result(
                FlutterError(
                    code: "INVALID_ARGUMENTS", 
                    message: "startDate and endDate parameters are required and must be timestamps in milliseconds", 
                    details: nil
                )
            )
            return
        }
        
        // Convert dates from milliseconds to Date()
        let dateFrom = HealthUtilities.dateFromMilliseconds(startDate)
        let dateTo = HealthUtilities.dateFromMilliseconds(endDate)
        
        healthKitManager.getTimeline(from: dateFrom, to: dateTo) { timelineData, error in
            if let error = error {
                result(
                    FlutterError(
                        code: "DATA_ERROR", 
                        message: error, 
                        details: nil
                    )
                )
            } else {
                result(timelineData ?? [])
            }
        }
    }
}
