import Foundation
import HealthKit

/// Gestor unificado de Salud (Apple HealthKit + Balanza Inteligente Femmto):
///  • Lee automáticamente Peso, % Grasa Corporal, IMC y Masa Magra de Apple Salud (escritos por la app Femmto).
///  • Permite registrar o actualizar tu pesada de Femmto directamente en la app o vía URL Scheme (`watchbridge://femmto?wt=...&fat=...`)
///    para funcionar al 100% incluso cuando Sideloadly firma con cuenta gratuita de Apple.
///  • Escribe en Apple Salud los pasos y la frecuencia cardíaca medidos por el Galaxy Watch 6 Classic.
@MainActor
final class HealthKitSyncManager: ObservableObject {
    static let shared = HealthKitSyncManager()

    @Published private(set) var isAvailable: Bool = HKHealthStore.isHealthDataAvailable()
    @Published private(set) var authorizationRequested: Bool = false
    @Published private(set) var bodyComposition = BodyComposition()
    @Published private(set) var lastWatchSyncDate: Date?
    @Published var userHeightCm: Double = 175.0
    @Published var dailyStepGoal: Int = 8000

    /// Se dispara cada vez que cambia la composición corporal de Femmto para enviarla al reloj por BLE.
    var onBodyCompositionChanged: ((BodyComposition) -> Void)?

    private let healthStore = HKHealthStore()
    private let defaults = UserDefaults.standard
    private var observerQueries: [HKObserverQuery] = []

    private enum Keys {
        static let authRequested = "hk_auth_requested_v3"
        static let stepDay = "hk_step_watermark_date"
        static let stepTotal = "hk_step_watermark_total"
        static let lastHr = "hk_last_hr_bpm"
        static let lastHrTs = "hk_last_hr_ts"

        static let cachedWeightKg = "femmto_cached_weight_kg"
        static let cachedFatPct = "femmto_cached_fat_pct"
        static let cachedBmi = "femmto_cached_bmi"
        static let cachedLeanKg = "femmto_cached_lean_kg"
        static let cachedMeasuredTs = "femmto_cached_ts"
        static let cachedSource = "femmto_cached_source"
        static let userHeightCm = "femmto_user_height_cm"
        static let stepGoal = "watch_daily_step_goal"
    }

    private var bodyTypes: [HKQuantityTypeIdentifier] {
        [.bodyMass, .bodyFatPercentage, .bodyMassIndex, .leanBodyMass]
    }

    private init() {
        authorizationRequested = defaults.bool(forKey: Keys.authRequested)
        let savedHeight = defaults.double(forKey: Keys.userHeightCm)
        if savedHeight >= 100 && savedHeight <= 240 {
            userHeightCm = savedHeight
        }
        let savedGoal = defaults.integer(forKey: Keys.stepGoal)
        if savedGoal >= 1000 {
            dailyStepGoal = savedGoal
        }
        loadCachedFemmtoComposition()
    }

    func updateStepGoal(_ newGoal: Int) {
        let clamped = max(2000, min(30000, newGoal))
        dailyStepGoal = clamped
        defaults.set(clamped, forKey: Keys.stepGoal)
    }

    // MARK: - Caché Local y Actualización Directa de Balanza Femmto

    private func loadCachedFemmtoComposition() {
        let w = defaults.double(forKey: Keys.cachedWeightKg)
        let f = defaults.double(forKey: Keys.cachedFatPct)
        let b = defaults.double(forKey: Keys.cachedBmi)
        let l = defaults.double(forKey: Keys.cachedLeanKg)
        let ts = defaults.double(forKey: Keys.cachedMeasuredTs)
        let src = defaults.string(forKey: Keys.cachedSource) ?? "Femmto"

        var comp = BodyComposition()
        if w > 15.0 { comp.weightKg = w }
        if f > 1.0 { comp.bodyFatPercent = f }
        if b > 5.0 { comp.bmi = b }
        if l > 5.0 { comp.leanMassKg = l }
        if ts > 0 { comp.measuredAt = Date(timeIntervalSince1970: ts) }
        comp.sourceName = src
        bodyComposition = comp
    }

    private func persistComposition(_ comp: BodyComposition) {
        if let w = comp.weightKg { defaults.set(w, forKey: Keys.cachedWeightKg) }
        if let f = comp.bodyFatPercent { defaults.set(f, forKey: Keys.cachedFatPct) }
        if let b = comp.bmi { defaults.set(b, forKey: Keys.cachedBmi) }
        if let l = comp.leanMassKg { defaults.set(l, forKey: Keys.cachedLeanKg) }
        if let d = comp.measuredAt { defaults.set(d.timeIntervalSince1970, forKey: Keys.cachedMeasuredTs) }
        if let s = comp.sourceName { defaults.set(s, forKey: Keys.cachedSource) }
    }

    /// Registra o actualiza los datos de tu balanza inteligente Femmto, calcula IMC y Masa Magra
    /// automáticamente y los envía en tiempo real al Galaxy Watch 6 Classic.
    func saveFemmtoMeasurement(
        weightKg: Double,
        bodyFatPercent: Double?,
        heightCm: Double?,
        source: String = "Femmto"
    ) {
        guard weightKg > 15.0 && weightKg < 300.0 else { return }
        if let h = heightCm, h >= 100.0 && h <= 240.0 {
            userHeightCm = h
            defaults.set(h, forKey: Keys.userHeightCm)
        }

        let heightMeters = userHeightCm / 100.0
        let computedBmi = heightMeters > 0 ? (weightKg / (heightMeters * heightMeters)) : nil
        let validFat = bodyFatPercent.flatMap { ($0 > 1.0 && $0 < 75.0) ? $0 : nil }
        let computedLean = validFat.map { weightKg * (1.0 - ($0 / 100.0)) }

        var comp = BodyComposition()
        comp.weightKg = (weightKg * 10).rounded() / 10
        comp.bodyFatPercent = validFat.map { ($0 * 10).rounded() / 10 }
        comp.bmi = computedBmi.map { ($0 * 10).rounded() / 10 }
        comp.leanMassKg = computedLean.map { ($0 * 10).rounded() / 10 }
        comp.measuredAt = Date()
        comp.sourceName = source

        bodyComposition = comp
        persistComposition(comp)
        onBodyCompositionChanged?(comp)

        // También intentamos guardarlo en Apple Salud si el permiso está activo
        saveBodyCompositionToHealthKitIfAuthorized(comp)
    }

    /// Soporta Deep Links locales desde Atajos de iOS: `watchbridge://femmto?wt=78.4&fat=18.2&ht=176`
    func handleIncomingUrl(_ url: URL) {
        guard url.scheme?.lowercased() == "watchbridge",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }

        let items = components.queryItems ?? []
        func doubleParam(_ name: String) -> Double? {
            guard let raw = items.first(where: { $0.name.lowercased() == name })?.value?
                .replacingOccurrences(of: ",", with: ".") else { return nil }
            return Double(raw)
        }

        if let wt = doubleParam("wt") ?? doubleParam("weight") {
            let fat = doubleParam("fat") ?? doubleParam("bodyfat")
            let ht = doubleParam("ht") ?? doubleParam("height")
            saveFemmtoMeasurement(weightKg: wt, bodyFatPercent: fat, heightCm: ht, source: "Femmto")
        }
    }

    // MARK: - Autorización HealthKit

    func requestAuthorizationIfNeeded() {
        guard isAvailable else { return }

        let readTypes: Set<HKObjectType> = Set(
            (bodyTypes + [.stepCount, .heartRate]).compactMap { HKObjectType.quantityType(forIdentifier: $0) as HKObjectType? }
        )
        let writeTypes: Set<HKSampleType> = Set(
            (bodyTypes + [.stepCount, .heartRate]).compactMap { HKObjectType.quantityType(forIdentifier: $0) as HKSampleType? }
        )

        healthStore.requestAuthorization(toShare: writeTypes, read: readTypes) { [weak self] _, _ in
            Task { @MainActor in
                guard let self else { return }
                self.authorizationRequested = true
                self.defaults.set(true, forKey: Keys.authRequested)
                self.startObservingBodyComposition()
                await self.refreshBodyComposition()
            }
        }
    }

    // MARK: - Lectura de composición corporal desde Apple Salud (Femmto → Salud → Watch Bridge)

    func refreshBodyComposition() async {
        guard isAvailable else { return }

        async let weight = latestSample(.bodyMass)
        async let fat = latestSample(.bodyFatPercentage)
        async let bmi = latestSample(.bodyMassIndex)
        async let lean = latestSample(.leanBodyMass)

        let (w, f, b, l) = await (weight, fat, bmi, lean)
        guard w != nil || f != nil else { return }

        let reference = [w, f].compactMap { $0 }.max(by: { $0.endDate < $1.endDate })
        // Si ya tenemos una medición manual más reciente en caché, la respetamos
        if let cachedDate = bodyComposition.measuredAt,
           let hkDate = reference?.endDate,
           cachedDate > hkDate {
            return
        }

        var comp = BodyComposition()
        let wKg = w?.quantity.doubleValue(for: .gramUnit(with: .kilo)) ?? bodyComposition.weightKg
        let fPct = f.map { $0.quantity.doubleValue(for: .percent()) * 100.0 } ?? bodyComposition.bodyFatPercent
        let heightMeters = userHeightCm / 100.0

        comp.weightKg = wKg
        comp.bodyFatPercent = fPct
        comp.bmi = b?.quantity.doubleValue(for: .count())
            ?? wKg.flatMap { heightMeters > 0 ? ($0 / (heightMeters * heightMeters)) : nil }
        comp.leanMassKg = l?.quantity.doubleValue(for: .gramUnit(with: .kilo))
            ?? (wKg != nil && fPct != nil ? wKg! * (1.0 - fPct! / 100.0) : nil)
        comp.measuredAt = reference?.endDate ?? Date()
        comp.sourceName = reference?.sourceRevision.source.name ?? "Femmto"

        if comp != bodyComposition {
            bodyComposition = comp
            persistComposition(comp)
            if !comp.isEmpty {
                onBodyCompositionChanged?(comp)
            }
        }
    }

    private func latestSample(_ id: HKQuantityTypeIdentifier) async -> HKQuantitySample? {
        guard let type = HKQuantityType.quantityType(forIdentifier: id) else { return nil }
        return await withCheckedContinuation { continuation in
            let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
            let query = HKSampleQuery(sampleType: type, predicate: nil, limit: 1, sortDescriptors: [sort]) { _, samples, _ in
                continuation.resume(returning: samples?.first as? HKQuantitySample)
            }
            healthStore.execute(query)
        }
    }

    private func startObservingBodyComposition() {
        guard observerQueries.isEmpty else { return }
        for id in [HKQuantityTypeIdentifier.bodyMass, .bodyFatPercentage] {
            guard let type = HKQuantityType.quantityType(forIdentifier: id) else { continue }
            let query = HKObserverQuery(sampleType: type, predicate: nil) { [weak self] _, completion, _ in
                Task { @MainActor in
                    await self?.refreshBodyComposition()
                    completion()
                }
            }
            healthStore.execute(query)
            observerQueries.append(query)
        }
    }

    private func saveBodyCompositionToHealthKitIfAuthorized(_ comp: BodyComposition) {
        let now = comp.measuredAt ?? Date()
        if let w = comp.weightKg,
           let type = HKQuantityType.quantityType(forIdentifier: .bodyMass),
           healthStore.authorizationStatus(for: type) == .sharingAuthorized {
            let sample = HKQuantitySample(
                type: type,
                quantity: HKQuantity(unit: .gramUnit(with: .kilo), doubleValue: w),
                start: now,
                end: now
            )
            healthStore.save(sample) { _, _ in }
        }
        if let f = comp.bodyFatPercent,
           let type = HKQuantityType.quantityType(forIdentifier: .bodyFatPercentage),
           healthStore.authorizationStatus(for: type) == .sharingAuthorized {
            let sample = HKQuantitySample(
                type: type,
                quantity: HKQuantity(unit: .percent(), doubleValue: f / 100.0),
                start: now,
                end: now
            )
            healthStore.save(sample) { _, _ in }
        }
    }

    // MARK: - Escritura de datos del reloj (Pasos + Pulso)

    func ingestWatchTelemetry(_ payload: WatchTelemetryPayload) {
        guard isAvailable, payload.hasData else { return }
        let now = Date()
        saveStepsDelta(total: payload.steps, now: now)
        saveHeartRate(bpm: payload.hr, now: now)
        lastWatchSyncDate = now
    }

    private func saveStepsDelta(total: Int, now: Date) {
        guard let stepType = HKQuantityType.quantityType(forIdentifier: .stepCount),
              healthStore.authorizationStatus(for: stepType) == .sharingAuthorized else { return }

        let todayKey = Self.dayKey(now)
        let savedDay = defaults.string(forKey: Keys.stepDay) ?? ""
        let savedTotal = defaults.integer(forKey: Keys.stepTotal)

        let delta: Int
        if savedDay != todayKey || total < savedTotal {
            delta = total
        } else {
            delta = total - savedTotal
        }

        defaults.set(todayKey, forKey: Keys.stepDay)
        defaults.set(total, forKey: Keys.stepTotal)

        guard delta > 0 else { return }
        let start = max(Calendar.current.startOfDay(for: now), now.addingTimeInterval(-180))
        let sample = HKQuantitySample(
            type: stepType,
            quantity: HKQuantity(unit: .count(), doubleValue: Double(delta)),
            start: start,
            end: now
        )
        healthStore.save(sample) { _, _ in }
    }

    private func saveHeartRate(bpm: Int, now: Date) {
        guard bpm >= 30, bpm <= 230,
              let hrType = HKQuantityType.quantityType(forIdentifier: .heartRate),
              healthStore.authorizationStatus(for: hrType) == .sharingAuthorized else { return }

        let lastBpm = defaults.integer(forKey: Keys.lastHr)
        let lastTs = defaults.double(forKey: Keys.lastHrTs)
        guard bpm != lastBpm || now.timeIntervalSince1970 - lastTs > 120 else { return }

        defaults.set(bpm, forKey: Keys.lastHr)
        defaults.set(now.timeIntervalSince1970, forKey: Keys.lastHrTs)

        let unit = HKUnit.count().unitDivided(by: .minute())
        let sample = HKQuantitySample(
            type: hrType,
            quantity: HKQuantity(unit: unit, doubleValue: Double(bpm)),
            start: now,
            end: now
        )
        healthStore.save(sample) { _, _ in }
    }

    private static func dayKey(_ date: Date) -> String {
        let f = DateFormatter()
        f.calendar = Calendar.current
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }
}
