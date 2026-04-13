import Flutter
import UIKit
import HealthKit

public class StepsCountPlugin: NSObject, FlutterPlugin {
    private let healthKitManager = HealthKitManager()
    private var methodChannel: FlutterMethodChannel?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "steps_count", binaryMessenger: registrar.messenger())
        let instance = StepsCountPlugin()
        instance.methodChannel = channel
        registrar.addMethodCallDelegate(instance, channel: channel)
        // Do NOT start HealthKit observer here - it can block ~156s on iOS 26 when authorization is not determined.
        // Observer is started when the app calls "startStepObserver" after permission is granted.
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

        case "getTimelineAfter":
            handleGetTimelineAfter(call: call, result: result)

        case "getStepSources":
            handleGetStepSources(call: call, result: result)
            
        case "exportStepsDatabase":
            result(nil)

        case "startStepObserver":
            HealthKitStepObserver.shared.startObserving(callback: { [weak self] in
                self?.methodChannel?.invokeMethod("onSensorChanged", arguments: nil)
            })
            result(true)
            
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
        let args = call.arguments as? [String: Any]
        let mode = HealthKitManager.stepSourceFilterMode(from: args)
        healthKitManager.getTodaysCount(mode: mode) { stepCount, error in
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
        let mode = HealthKitManager.stepSourceFilterMode(from: arguments as? [String: Any])
        
        healthKitManager.getStepCount(from: dateFrom, to: dateTo, mode: mode) { stepCount, error in
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
        let mode = HealthKitManager.stepSourceFilterMode(from: arguments as? [String: Any])
        
        healthKitManager.getTimeline(from: dateFrom, to: dateTo, mode: mode) { timelineData, error in
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

    private func handleGetTimelineAfter(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? NSDictionary
        // nil / absent → use epoch 0 so HealthKit returns all records
        let lastSyncMs = (arguments?["lastSyncTimestamp"] as? NSNumber)?.doubleValue ?? 0
        let afterDate = HealthUtilities.dateFromMilliseconds(lastSyncMs)
        let mode = HealthKitManager.stepSourceFilterMode(from: arguments as? [String: Any])

        healthKitManager.getTimelineAfter(afterTimestamp: afterDate, mode: mode) { timelineData, error in
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

    private func handleGetStepSources(call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? [String: Any]
        let startMs = (arguments?["startDate"] as? NSNumber)?.doubleValue ?? 0
        let endMs = (arguments?["endDate"] as? NSNumber)?.doubleValue ?? 0
        let startDate: Date?
        let endDate: Date?
        if startMs > 0 && endMs > 0 {
            startDate = HealthUtilities.dateFromMilliseconds(startMs)
            endDate = HealthUtilities.dateFromMilliseconds(endMs)
        } else {
            startDate = nil
            endDate = nil
        }

        healthKitManager.getStepSources(from: startDate, to: endDate) { list, error in
            if let error = error {
                result(
                    FlutterError(
                        code: "DATA_ERROR",
                        message: error,
                        details: nil
                    )
                )
            } else {
                result(list ?? [])
            }
        }
    }
}
