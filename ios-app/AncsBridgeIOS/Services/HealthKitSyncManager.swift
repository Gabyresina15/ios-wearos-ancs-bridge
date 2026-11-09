import Foundation
import HealthKit

@MainActor
final class HealthKitSyncManager: ObservableObject {
    static let shared = HealthKitSyncManager()

    private let healthStore = HKHealthStore()
    private let defaults = UserDefaults.standard

    @Published var isAuthorized: Bool = false
    @Published var lastHealthKitSyncDate: Date? = nil

    private let keyWatermarkDate = "hk_step_watermark_date"
    private let keyWatermarkSteps = "hk_step_watermark_total"
    private let keyLastSyncedHr = "hk_last_hr_bpm"
    private let keyLastSyncedHrTs = "hk_last_hr_ts"
    private let keyLastSyncedWeight = "hk_last_weight_kg"
    private let keyLastSyncedFat = "hk_last_body_fat_pct"

    private init() {}

    func requestAuthorizationIfNeeded() {
        guard HKHealthStore.isHealthDataAvailable() else { return }

        var writeTypes = Set<HKSampleType>()
        if let steps = HKObjectType.quantityType(forIdentifier: .stepCount) { writeTypes.insert(steps) }
        if let hr = HKObjectType.quantityType(forIdentifier: .heartRate) { writeTypes.insert(hr) }
        if let weight = HKObjectType.quantityType(forIdentifier: .bodyMass) { writeTypes.insert(weight) }
        if let bodyFat = HKObjectType.quantityType(forIdentifier: .bodyFatPercentage) { writeTypes.insert(bodyFat) }

        healthStore.requestAuthorization(toShare: writeTypes, read: writeTypes) { [weak self] success, _ in
            Task { @MainActor in
                self?.isAuthorized = success
            }
        }
    }

    /// Sincroniza el snapshot recibido por BLE directamente en Apple Health usando la zona horaria local
    /// para calcular el delta exacto de pasos sin duplicar registros.
    func ingestWatchTelemetry(_ payload: WatchTelemetryPayload) {
        guard HKHealthStore.isHealthDataAvailable() else { return }

        let now = Date()
        let todayKey = Self.localDateKey(for: now)
        let savedDateKey = defaults.string(forKey: keyWatermarkDate) ?? ""
        let lastStepsTotal = defaults.integer(forKey: keyWatermarkSteps)

        // 1. Delta de Pasos en Hora Local
        let deltaSteps: Int
        if savedDateKey != todayKey || payload.steps < lastStepsTotal {
            deltaSteps = payload.steps
        } else {
            deltaSteps = max(0, payload.steps - lastStepsTotal)
        }

        if deltaSteps > 0, let stepType = HKQuantityType.quantityType(forIdentifier: .stepCount) {
            let quantity = HKQuantity(unit: HKUnit.count(), doubleValue: Double(deltaSteps))
            let startDate = now.addingTimeInterval(-60)
            let sample = HKQuantitySample(type: stepType, quantity: quantity, start: startDate, end: now)
            healthStore.save(sample) { [weak self] success, _ in
                if success {
                    self?.defaults.set(todayKey, forKey: self?.keyWatermarkDate ?? "")
                    self?.defaults.set(payload.steps, forKey: self?.keyWatermarkSteps ?? "")
                }
            }
        } else if savedDateKey != todayKey {
            defaults.set(todayKey, forKey: keyWatermarkDate)
            defaults.set(payload.steps, forKey: keyWatermarkSteps)
        }

        // 2. Frecuencia Cardíaca (si es válida y han pasado al menos 2 min o cambió el valor)
        let lastHrTs = defaults.double(forKey: keyLastSyncedHrTs)
        let lastHrVal = defaults.integer(forKey: keyLastSyncedHr)
        if payload.hr >= 30 && (payload.hr != lastHrVal || now.timeIntervalSince1970 - lastHrTs > 120),
           let hrType = HKQuantityType.quantityType(forIdentifier: .heartRate) {
            let bpmUnit = HKUnit.count().unitDivided(by: HKUnit.minute())
            let quantity = HKQuantity(unit: bpmUnit, doubleValue: Double(payload.hr))
            let sample = HKQuantitySample(type: hrType, quantity: quantity, start: now, end: now)
            healthStore.save(sample) { [weak self] success, _ in
                if success {
                    self?.defaults.set(payload.hr, forKey: self?.keyLastSyncedHr ?? "")
                    self?.defaults.set(now.timeIntervalSince1970, forKey: self?.keyLastSyncedHrTs ?? "")
                }
            }
        }

        // 3. Peso Corporal (kg)
        if let weightKg = payload.wt, weightKg > 20.0 {
            let prevWeight = defaults.double(forKey: keyLastSyncedWeight)
            if abs(weightKg - prevWeight) >= 0.05,
               let weightType = HKQuantityType.quantityType(forIdentifier: .bodyMass) {
                let quantity = HKQuantity(unit: HKUnit.gramUnit(with: .kilo), doubleValue: weightKg)
                let sample = HKQuantitySample(type: weightType, quantity: quantity, start: now, end: now)
                healthStore.save(sample) { [weak self] success, _ in
                    if success {
                        self?.defaults.set(weightKg, forKey: self?.keyLastSyncedWeight ?? "")
                    }
                }
            }
        }

        // 4. Porcentaje de Grasa Corporal (sensor BIA del Galaxy Watch 6)
        if let fatPct = payload.fat, fatPct > 1.0 && fatPct < 80.0 {
            let prevFat = defaults.double(forKey: keyLastSyncedFat)
            if abs(fatPct - prevFat) >= 0.05,
               let fatType = HKQuantityType.quantityType(forIdentifier: .bodyFatPercentage) {
                // HealthKit almacena porcentajes como fracción decimal (0.182 para 18.2%)
                let quantity = HKQuantity(unit: HKUnit.percent(), doubleValue: fatPct / 100.0)
                let sample = HKQuantitySample(type: fatType, quantity: quantity, start: now, end: now)
                healthStore.save(sample) { [weak self] success, _ in
                    if success {
                        self?.defaults.set(fatPct, forKey: self?.keyLastSyncedFat ?? "")
                    }
                }
            }
        }

        lastHealthKitSyncDate = now
    }

    private static func localDateKey(for date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar.current
        formatter.timeZone = TimeZone.current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }
}
