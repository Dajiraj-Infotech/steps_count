import Foundation
import HealthKit

class HealthKitStepObserver: NSObject {
    static let shared = HealthKitStepObserver()
    
    private let healthStore = HKHealthStore()
    private var observerQuery: HKObserverQuery?
    private var stepCountCallback: (() -> Void)?
    
    private override init() {
        super.init()
    }
    
    func startObserving(callback: @escaping () -> Void) {
        self.stepCountCallback = callback
        
        guard let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            print("Step count type not available")
            return
        }
        
        // Enable background delivery
        healthStore.enableBackgroundDelivery(for: stepCountType, frequency: .immediate) { [weak self] success, error in
            if success {
                print("✅ Background delivery enabled for steps")
                self?.createObserverQuery()
            } else {
                print("❌ Failed to enable background delivery: \(String(describing: error))")
            }
        }
    }
    
    private func createObserverQuery() {
        guard let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            return
        }
        
        observerQuery = HKObserverQuery(sampleType: stepCountType, predicate: nil) { [weak self] query, completionHandler, error in
            if let error = error {
                print("ObserverQuery error: \(error.localizedDescription)")
                completionHandler()
                return
            }
            
            print("🔔 New step data detected in HealthKit - calling callback")
            
            // Call the callback function
            DispatchQueue.main.async {
                self?.stepCountCallback?()
            }
            
            completionHandler()
        }
        
        if let query = observerQuery {
            healthStore.execute(query)
            print("✅ Observer query started")
        }
    }
    
    func stopObserving() {
        if let query = observerQuery {
            healthStore.stop(query)
            observerQuery = nil
            print("🛑 Observer query stopped")
        }
        
        // Disable background delivery
        if let stepCountType = HKQuantityType.quantityType(forIdentifier: .stepCount) {
            healthStore.disableBackgroundDelivery(for: stepCountType) { success, error in
                if success {
                    print("✅ Background delivery disabled")
                } else {
                    print("❌ Failed to disable background delivery: \(String(describing: error))")
                }
            }
        }
        
        stepCountCallback = nil
    }
}
