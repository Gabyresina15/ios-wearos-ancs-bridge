import Foundation
import CoreBluetooth
import UIKit
import EventKit

/// Motor BLE Dual-Role (Central + Peripheral Server) para conexión instantánea y permanente
/// entre el iPhone 16 Pro Max y el Samsung Galaxy Watch 6 Classic:
///  • Rol 1 (CBPeripheralManager): Publica `BridgeSyncService` en la tabla GATT del propio iPhone.
///    Como el Galaxy Watch YA está conectado al iPhone para leer ANCS/AMS, el reloj descubre este
///    servicio sobre su conexión existente (`activeGatt`) en < 1 segundo sin depender de escaneos.
///  • Rol 2 (CBCentralManager): Escanea y recupera el periférico del reloj cuando el reloj actúa como servidor.
@MainActor
final class WatchBleCentralManager: NSObject, ObservableObject {
    static let shared = WatchBleCentralManager()

    // MARK: - Estado Publicado
    @Published private(set) var linkState: WatchLinkState = .searching
    @Published private(set) var watchName: String = "Galaxy Watch 6 Classic"
    @Published private(set) var telemetry = WatchTelemetryPayload()
    @Published private(set) var lastTelemetryDate: Date?
    @Published private(set) var isSyncing = false
    @Published private(set) var recentNotifications: [WatchNotificationLogItem] = []
    @Published private(set) var blockedBundles: Set<String> = []
    @Published private(set) var discoveredApps: [IosAppFilterCatalogItem] = IosAppFilterCatalogItem.defaultCatalog

    var isConnected: Bool { linkState.isReady }

    // MARK: - Central Role Internals
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var telemetryChar: CBCharacteristic?
    private var commandChar: CBCharacteristic?
    private var eventChar: CBCharacteristic?

    // MARK: - Peripheral Server Role Internals (Fast-Link sobre activeGatt del reloj)
    private var peripheralManager: CBPeripheralManager!
    private var localCommandChar: CBMutableCharacteristic?
    private var localTelemetryChar: CBMutableCharacteristic?
    private var localEventChar: CBMutableCharacteristic?
    private var isLocalServicePublished = false
    private var hasSubscribedWatchCentral = false

    private var retryTask: Task<Void, Never>?
    private var nameScanTask: Task<Void, Never>?
    private var serviceRetryCount = 0
    private var lastContextSent: Date = .distantPast

    private let defaults = UserDefaults.standard
    private let eventStore = EKEventStore()

    private enum Keys {
        static let blocked = "bridge_blocked_bundles"
        static let watchId = "bridge_saved_watch_uuid"
        static let watchName = "bridge_saved_watch_name"
    }

    private override init() {
        super.init()
        UIDevice.current.isBatteryMonitoringEnabled = true
        blockedBundles = Set(defaults.stringArray(forKey: Keys.blocked) ?? ["com.apple.tips"])
        if let saved = defaults.string(forKey: Keys.watchName), !saved.isEmpty { watchName = saved }

        central = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [
                CBCentralManagerOptionRestoreIdentifierKey: "com.ancsbridge.ios.central",
                CBCentralManagerOptionShowPowerAlertKey: true
            ]
        )

        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: nil,
            options: [
                CBPeripheralManagerOptionRestoreIdentifierKey: "com.ancsbridge.ios.peripheral"
            ]
        )

        HealthKitSyncManager.shared.onBodyCompositionChanged = { [weak self] comp in
            self?.sendBodyComposition(comp)
        }

        NotificationCenter.default.addObserver(
            forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.sendPhoneContext() }
        }
        NotificationCenter.default.addObserver(
            forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.sendPhoneContext() }
        }
    }

    // MARK: - Conexión y Descubrimiento

    func reconnect() {
        setupLocalPeripheralServiceIfNeeded()

        switch central.state {
        case .poweredOn: break
        case .unauthorized:
            linkState = .unauthorized
            return
        case .poweredOff:
            linkState = .bluetoothOff
            return
        default:
            return
        }

        if linkState == .ready && (peripheral?.state == .connected || hasSubscribedWatchCentral) {
            requestSyncNow()
            return
        }

        // 1. Reloj guardado por UUID
        if let idString = defaults.string(forKey: Keys.watchId),
           let uuid = UUID(uuidString: idString),
           let known = central.retrievePeripherals(withIdentifiers: [uuid]).first {
            connect(known)
        }

        // 2. Periféricos ya conectados al sistema iOS
        let systemConnected = central.retrieveConnectedPeripherals(withServices: [
            BridgeBleUUIDs.bridgeService,
            BridgeBleUUIDs.genericHeartRateService
        ])
        if let linked = systemConnected.first(where: { isLikelyWatch($0) }) ?? systemConnected.first {
            connect(linked)
        }

        // 3. Escaneo activo
        startScanning()
    }

    private func startScanning() {
        guard central.state == .poweredOn else { return }
        if !linkState.isReady && linkState != .connecting && linkState != .discovering {
            linkState = .searching
        }
        central.stopScan()
        central.scanForPeripherals(
            withServices: [BridgeBleUUIDs.bridgeService, BridgeBleUUIDs.genericHeartRateService],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )

        nameScanTask?.cancel()
        nameScanTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard let self, !Task.isCancelled, !self.linkState.isReady,
                  UIApplication.shared.applicationState == .active,
                  self.central.state == .poweredOn else { return }
            self.central.stopScan()
            self.central.scanForPeripherals(withServices: nil, options: nil)
        }
    }

    private func connect(_ p: CBPeripheral) {
        if let current = peripheral, current.identifier != p.identifier, current.state != .disconnected {
            central.cancelPeripheralConnection(current)
        }
        peripheral = p
        p.delegate = self
        if let name = p.name, !name.isEmpty { updateWatchName(name) }

        switch p.state {
        case .connected:
            if !linkState.isReady { linkState = .discovering }
            discoverBridgeService(on: p)
        case .connecting:
            if !linkState.isReady { linkState = .connecting }
        default:
            if !linkState.isReady { linkState = .connecting }
            central.connect(p, options: [
                CBConnectPeripheralOptionNotifyOnConnectionKey: true,
                CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
            ])
        }
    }

    private func discoverBridgeService(on p: CBPeripheral) {
        p.discoverServices([BridgeBleUUIDs.bridgeService])
    }

    private func isLikelyWatch(_ p: CBPeripheral) -> Bool {
        let name = (p.name ?? "").lowercased()
        return name.contains("watch") || name.contains("galaxy") || name.contains("samsung")
    }

    private func resetCharacteristics() {
        telemetryChar = nil
        commandChar = nil
        eventChar = nil
    }

    private func scheduleRetry(after seconds: Double, _ block: @escaping @MainActor () -> Void) {
        retryTask?.cancel()
        retryTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            block()
        }
    }

    private func updateWatchName(_ raw: String) {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, !clean.hasPrefix("SM-") else { return }
        watchName = clean
        defaults.set(clean, forKey: Keys.watchName)
    }

    // MARK: - Servidor GATT Local en iOS (CBPeripheralManager)

    private func setupLocalPeripheralServiceIfNeeded() {
        guard peripheralManager.state == .poweredOn else { return }
        if !isLocalServicePublished {
            let telChar = CBMutableCharacteristic(
                type: BridgeBleUUIDs.telemetryChar,
                properties: [.read, .write, .writeWithoutResponse, .notify],
                value: nil,
                permissions: [.readable, .writeable]
            )
            let cmdChar = CBMutableCharacteristic(
                type: BridgeBleUUIDs.commandConfigChar,
                properties: [.read, .write, .writeWithoutResponse, .notify],
                value: nil,
                permissions: [.readable, .writeable]
            )
            let evtChar = CBMutableCharacteristic(
                type: BridgeBleUUIDs.watchEventChar,
                properties: [.read, .write, .writeWithoutResponse, .notify],
                value: nil,
                permissions: [.readable, .writeable]
            )

            let service = CBMutableService(type: BridgeBleUUIDs.bridgeService, primary: true)
            service.characteristics = [telChar, cmdChar, evtChar]

            localTelemetryChar = telChar
            localCommandChar = cmdChar
            localEventChar = evtChar

            peripheralManager.removeAllServices()
            peripheralManager.add(service)
            isLocalServicePublished = true
        }

        if !peripheralManager.isAdvertising {
            peripheralManager.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [BridgeBleUUIDs.bridgeService],
                CBAdvertisementDataLocalNameKey: "WatchBridgeIOS"
            ])
        }
    }

    private func buildCombinedPhoneContextPayload() -> [String: Any] {
        let level = UIDevice.current.batteryLevel
        let pct = level >= 0 ? Int((level * 100).rounded()) : 100
        let state = UIDevice.current.batteryState
        var payload: [String: Any] = [
            "cmd": "PHONE_CONTEXT",
            "bat": pct,
            "chg": state == .charging || state == .full,
            "platform": "iOS",
            "phoneName": String(UIDevice.current.name.prefix(28))
        ]
        if let next = nextCalendarEventSummary() {
            payload["nextEvent"] = String(next.prefix(40))
        }
        let comp = HealthKitSyncManager.shared.bodyComposition
        if let wt = comp.weightKg { payload["wt"] = (wt * 10).rounded() / 10 }
        if let fat = comp.bodyFatPercent { payload["fat"] = (fat * 10).rounded() / 10 }
        if let bmi = comp.bmi { payload["bmi"] = (bmi * 10).rounded() / 10 }
        if let lean = comp.leanMassKg { payload["lean"] = (lean * 10).rounded() / 10 }
        if let src = comp.sourceName { payload["src"] = String(src.prefix(16)) }
        if let mts = comp.measuredAt { payload["mts"] = Int64(mts.timeIntervalSince1970 * 1000) }
        return payload
    }

    // MARK: - Acciones Públicas desde la UI de iPhone

    func requestSyncNow() {
        isSyncing = true
        sendJson(["cmd": "SYNC_NOW"])
        if let p = peripheral, let c = telemetryChar { p.readValue(for: c) }
        sendPhoneContext()
        Task {
            await HealthKitSyncManager.shared.refreshBodyComposition()
            sendBodyComposition(HealthKitSyncManager.shared.bodyComposition)
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            isSyncing = false
        }
    }

    func findWatch() {
        sendJson([
            "cmd": "PING_WATCH",
            "title": "Buscando tu reloj 🔔",
            "msg": "Alerta enviada desde \(UIDevice.current.name)"
        ])
    }

    func measureWatchHeartRate() {
        sendJson(["cmd": "MEASURE_HR"])
    }

    func clearWatchNotifications() {
        sendJson(["cmd": "CLEAR_NOTIFS"])
    }

    func setWatchMuted(_ muted: Bool) {
        telemetry.dnd = muted
        sendJson(["cmd": "SET_DND", "muted": muted])
    }

    func sendMediaCommand(_ action: String) {
        sendJson(["cmd": "MEDIA_CMD", "action": action])
    }

    func isAllowed(_ bundleId: String) -> Bool { !blockedBundles.contains(bundleId) }

    func setAllowed(_ allowed: Bool, bundleId: String) {
        if allowed { blockedBundles.remove(bundleId) } else { blockedBundles.insert(bundleId) }
        let array = Array(blockedBundles).sorted()
        defaults.set(array, forKey: Keys.blocked)
        sendJson(["cmd": "SET_BLOCKED", "bundles": array])
    }

    func clearRecentNotifications() {
        recentNotifications.removeAll()
    }

    func sendPhoneContext() {
        let payload = buildCombinedPhoneContextPayload()
        if sendJson(payload) { lastContextSent = Date() }
    }

    func sendBodyComposition(_ comp: BodyComposition) {
        guard !comp.isEmpty else { return }
        sendJson(comp.blePayload())
    }

    @discardableResult
    private func sendJson(_ dict: [String: Any]) -> Bool {
        guard let data = try? JSONSerialization.data(withJSONObject: dict) else { return false }
        var sent = false

        // Vía 1: Escritura como Central hacia el servidor GATT del reloj
        if let p = peripheral, p.state == .connected, let c = commandChar {
            let writeType: CBCharacteristicWriteType = c.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
            p.writeValue(data, for: c, type: writeType)
            sent = true
        }

        // Vía 2: Notificación desde el servidor GATT local de iOS hacia activeGatt del reloj
        if peripheralManager.state == .poweredOn, let localCmd = localCommandChar {
            let ok = peripheralManager.updateValue(data, for: localCmd, onSubscribedCentrals: nil)
            if ok { sent = true }
        }

        return sent
    }

    private func nextCalendarEventSummary() -> String? {
        let status = EKEventStore.authorizationStatus(for: .event)
        guard status == .fullAccess else { return nil }
        let now = Date()
        let predicate = eventStore.predicateForEvents(withStart: now, end: now.addingTimeInterval(12 * 3600), calendars: nil)
        guard let next = eventStore.events(matching: predicate)
            .filter({ !$0.isAllDay })
            .sorted(by: { $0.startDate < $1.startDate })
            .first else { return nil }
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return "\(f.string(from: next.startDate)) \(next.title ?? "")"
    }

    func requestCalendarAccess() {
        guard EKEventStore.authorizationStatus(for: .event) == .notDetermined else { return }
        eventStore.requestFullAccessToEvents { [weak self] _, _ in
            Task { @MainActor in self?.sendPhoneContext() }
        }
    }

    // MARK: - Procesamiento de Datos Entrantes del Reloj

    private func handleTelemetry(_ data: Data) {
        guard let decoded = try? JSONDecoder().decode(WatchTelemetryPayload.self, from: data), decoded.hasData else {
            if let p = peripheral, let c = telemetryChar { p.readValue(for: c) }
            return
        }
        if linkState != .ready {
            onLinkReady()
        }
        telemetry = decoded
        lastTelemetryDate = Date()
        isSyncing = false
        updateWatchName(decoded.model)
        HealthKitSyncManager.shared.ingestWatchTelemetry(decoded)

        if Date().timeIntervalSince(lastContextSent) > 300 {
            sendPhoneContext()
        }
    }

    private func handleWatchEvent(_ data: Data) {
        if linkState != .ready {
            onLinkReady()
        }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let evt = json["evt"] as? String else { return }

        switch evt {
        case "FIND_PHONE":
            let active = json["active"] as? Bool ?? true
            if active {
                PhoneFindMyAlarmManager.shared.startAlarm()
            } else {
                PhoneFindMyAlarmManager.shared.stopAlarm()
            }

        case "NOTIF_LOG":
            guard let item = try? JSONDecoder().decode(WatchNotificationLogItem.self, from: data) else { return }
            recentNotifications.removeAll { $0.uid == item.uid }
            recentNotifications.insert(item, at: 0)
            if recentNotifications.count > 60 {
                recentNotifications = Array(recentNotifications.prefix(60))
            }
            if !item.app.isEmpty, !discoveredApps.contains(where: { $0.bundleId == item.app }) {
                discoveredApps.append(
                    IosAppFilterCatalogItem(name: item.appName, bundleId: item.app, symbol: "app.badge.fill")
                )
            }

        default:
            break
        }
    }

    private func onLinkReady() {
        guard linkState != .ready else { return }
        linkState = .ready
        serviceRetryCount = 0
        central.stopScan()
        nameScanTask?.cancel()
        retryTask?.cancel()
        if let p = peripheral {
            defaults.set(p.identifier.uuidString, forKey: Keys.watchId)
        }

        sendPhoneContext()
        sendJson(["cmd": "SET_BLOCKED", "bundles": Array(blockedBundles).sorted()])
        if let p = peripheral, let c = telemetryChar { p.readValue(for: c) }
        Task {
            await HealthKitSyncManager.shared.refreshBodyComposition()
            sendBodyComposition(HealthKitSyncManager.shared.bodyComposition)
        }
    }

    private func handleDisconnect(_ p: CBPeripheral) {
        resetCharacteristics()
        if !hasSubscribedWatchCentral && linkState != .bluetoothOff && linkState != .unauthorized {
            linkState = .searching
        }
        if central.state == .poweredOn {
            central.connect(p, options: [
                CBConnectPeripheralOptionNotifyOnConnectionKey: true,
                CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
            ])
            startScanning()
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension WatchBleCentralManager: CBCentralManagerDelegate {

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn:
                reconnect()
            case .poweredOff:
                resetCharacteristics()
                linkState = .bluetoothOff
            case .unauthorized:
                linkState = .unauthorized
            default:
                break
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        MainActor.assumeIsolated {
            guard let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first else { return }
            peripheral = restored
            restored.delegate = self
            if restored.state == .connected {
                linkState = .discovering
                discoverBridgeService(on: restored)
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        MainActor.assumeIsolated {
            let advServices = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? []
            let advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
            let matchesService = advServices.contains(BridgeBleUUIDs.bridgeService)
            let nameLower = (advName ?? peripheral.name ?? "").lowercased()
            let matchesName = nameLower.contains("watch") || nameLower.contains("galaxy")
            guard matchesService || matchesName else { return }

            central.stopScan()
            nameScanTask?.cancel()
            if let advName, !advName.isEmpty { updateWatchName(advName) }
            connect(peripheral)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated {
            guard peripheral.identifier == self.peripheral?.identifier else { return }
            if !linkState.isReady { linkState = .discovering }
            discoverBridgeService(on: peripheral)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            handleDisconnect(peripheral)
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            guard peripheral.identifier == self.peripheral?.identifier else { return }
            handleDisconnect(peripheral)
        }
    }
}

// MARK: - CBPeripheralDelegate

extension WatchBleCentralManager: CBPeripheralDelegate {

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            if let service = peripheral.services?.first(where: { $0.uuid == BridgeBleUUIDs.bridgeService }) {
                peripheral.discoverCharacteristics([
                    BridgeBleUUIDs.telemetryChar,
                    BridgeBleUUIDs.commandConfigChar,
                    BridgeBleUUIDs.watchEventChar
                ], for: service)
                return
            }

            if !hasSubscribedWatchCentral {
                linkState = .missingService
            }
            serviceRetryCount += 1
            let delay = min(15.0, 2.5 * Double(serviceRetryCount))
            scheduleRetry(after: delay) { [weak self] in
                guard let self, let p = self.peripheral, p.state == .connected else {
                    self?.reconnect()
                    return
                }
                self.discoverBridgeService(on: p)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        MainActor.assumeIsolated {
            resetCharacteristics()
            discoverBridgeService(on: peripheral)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            for c in service.characteristics ?? [] {
                switch c.uuid {
                case BridgeBleUUIDs.telemetryChar:
                    telemetryChar = c
                    peripheral.setNotifyValue(true, for: c)
                case BridgeBleUUIDs.commandConfigChar:
                    commandChar = c
                case BridgeBleUUIDs.watchEventChar:
                    eventChar = c
                    peripheral.setNotifyValue(true, for: c)
                default:
                    break
                }
            }
            if commandChar != nil || telemetryChar != nil {
                onLinkReady()
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard error == nil, let data = characteristic.value, !data.isEmpty else { return }
            switch characteristic.uuid {
            case BridgeBleUUIDs.telemetryChar:
                handleTelemetry(data)
            case BridgeBleUUIDs.watchEventChar:
                handleWatchEvent(data)
            default:
                break
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if error != nil, characteristic.uuid == BridgeBleUUIDs.commandConfigChar {
                resetCharacteristics()
                discoverBridgeService(on: peripheral)
            }
        }
    }
}

// MARK: - CBPeripheralManagerDelegate (Servidor GATT Local en el iPhone)

extension WatchBleCentralManager: CBPeripheralManagerDelegate {

    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        MainActor.assumeIsolated {
            if peripheral.state == .poweredOn {
                setupLocalPeripheralServiceIfNeeded()
            } else {
                isLocalServicePublished = false
                hasSubscribedWatchCentral = false
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String : Any]) {
        MainActor.assumeIsolated {
            setupLocalPeripheralServiceIfNeeded()
        }
    }

    nonisolated func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        MainActor.assumeIsolated {
            hasSubscribedWatchCentral = true
            onLinkReady()
        }
    }

    nonisolated func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        MainActor.assumeIsolated {
            hasSubscribedWatchCentral = false
            if self.peripheral?.state != .connected {
                linkState = .searching
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        MainActor.assumeIsolated {
            hasSubscribedWatchCentral = true
            if linkState != .ready { onLinkReady() }

            if request.characteristic.uuid == BridgeBleUUIDs.commandConfigChar,
               let data = try? JSONSerialization.data(withJSONObject: buildCombinedPhoneContextPayload()) {
                if request.offset < data.count {
                    request.value = data.subdata(in: request.offset..<data.count)
                    peripheral.respond(to: request, withResult: .success)
                    return
                }
            }
            request.value = Data()
            peripheral.respond(to: request, withResult: .success)
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        MainActor.assumeIsolated {
            hasSubscribedWatchCentral = true
            if linkState != .ready { onLinkReady() }

            for req in requests {
                if let val = req.value, !val.isEmpty {
                    switch req.characteristic.uuid {
                    case BridgeBleUUIDs.telemetryChar:
                        handleTelemetry(val)
                    case BridgeBleUUIDs.watchEventChar:
                        handleWatchEvent(val)
                    default:
                        break
                    }
                }
            }
            if let first = requests.first {
                peripheral.respond(to: first, withResult: .success)
            }
        }
    }
}
