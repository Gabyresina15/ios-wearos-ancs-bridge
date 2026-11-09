import Foundation
import CoreBluetooth
import UIKit
import EventKit

@MainActor
final class WatchBleCentralManager: NSObject, ObservableObject {
    static let shared = WatchBleCentralManager()

    @Published var isConnected: Bool = false
    @Published var connectionStatusText: String = "Buscando Galaxy Watch..."
    @Published var watchName: String = "Galaxy Watch 6 Classic"
    @Published var telemetry: WatchTelemetryPayload = WatchTelemetryPayload(
        bat: 0, chg: false, steps: 0, hr: 0, wt: nil, fat: nil,
        dnd: false, hfp: false, hid: false, model: "Galaxy Watch 6 Classic", ts: 0
    )
    @Published var recentNotifications: [WatchNotificationLogItem] = []
    @Published var blockedBundles: Set<String> = []
    @Published var discoveredApps: [IosAppFilterCatalogItem] = IosAppFilterCatalogItem.defaultCatalog
    @Published var isSyncingPulse: Bool = false

    private var centralManager: CBCentralManager!
    private var watchPeripheral: CBPeripheral?
    private var telemetryCharacteristic: CBCharacteristic?
    private var commandConfigCharacteristic: CBCharacteristic?
    private var watchEventCharacteristic: CBCharacteristic?

    private let defaults = UserDefaults.standard
    private let keyBlockedBundles = "bridge_blocked_bundles"
    private let keySavedWatchUuid = "bridge_saved_watch_uuid"
    private let eventStore = EKEventStore()

    private override init() {
        super.init()
        UIDevice.current.isBatteryMonitoringEnabled = true
        let savedBlocked = defaults.stringArray(forKey: keyBlockedBundles) ?? ["com.apple.tips"]
        self.blockedBundles = Set(savedBlocked)

        centralManager = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [
                CBCentralManagerOptionRestoreIdentifierKey: "com.ancsbridge.ios.central",
                CBCentralManagerOptionShowPowerAlertKey: true
            ]
        )

        NotificationCenter.default.addObserver(
            forName: UIDevice.batteryLevelDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.sendPhoneContextToWatch() }
        }
    }

    func scanOrReconnectWatch() {
        guard centralManager.state == .poweredOn else {
            connectionStatusText = "Bluetooth desactivado"
            return
        }

        // 1. Intentar recuperar periférico por UUID guardado previamente
        if let uuidString = defaults.string(forKey: keySavedWatchUuid),
           let uuid = UUID(uuidString: uuidString) {
            let known = centralManager.retrievePeripherals(withIdentifiers: [uuid])
            if let existing = known.first {
                connectToPeripheral(existing)
            }
        }

        // 2. Recuperar periféricos ya conectados al sistema iOS (por ANCS, Heart Rate o BridgeSync)
        let connected = centralManager.retrieveConnectedPeripherals(withServices: [
            BridgeBleUUIDs.bridgeService,
            BridgeBleUUIDs.genericHeartRateService,
            BridgeBleUUIDs.ancsService
        ])
        if let firstConnected = connected.first {
            connectToPeripheral(firstConnected)
            return
        }

        // 3. Escaneo BLE activo filtrado por el servicio BridgeSyncService del reloj
        connectionStatusText = "Escaneando reloj..."
        centralManager.scanForPeripherals(
            withServices: [BridgeBleUUIDs.bridgeService, BridgeBleUUIDs.genericHeartRateService],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    private func connectToPeripheral(_ peripheral: CBPeripheral) {
        watchPeripheral = peripheral
        peripheral.delegate = self
        defaults.set(peripheral.identifier.uuidString, forKey: keySavedWatchUuid)
        if let name = peripheral.name, !name.isEmpty {
            watchName = name
        }
        connectionStatusText = "Conectando..."
        centralManager.connect(peripheral, options: [
            CBConnectPeripheralOptionNotifyOnConnectionKey: true,
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
        ])
    }

    // MARK: - Comandos BLE Directos hacia el Reloj

    func requestSyncNow() {
        isSyncingPulse = true
        sendJsonCommand(["cmd": "SYNC_NOW"])
        if let p = watchPeripheral, let char = telemetryCharacteristic {
            p.readValue(for: char)
        }
        sendPhoneContextToWatch()
        Task {
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            isSyncingPulse = false
        }
    }

    func pingWatch() {
        sendJsonCommand([
            "cmd": "PING_WATCH",
            "title": "Buscando tu reloj 🔔",
            "msg": "Ping enviado desde tu \(UIDevice.current.name)"
        ])
    }

    func setWatchMuted(_ muted: Bool) {
        sendJsonCommand([
            "cmd": "SET_DND",
            "muted": muted
        ])
    }

    func toggleBundleAllowed(_ bundleId: String) {
        if blockedBundles.contains(bundleId) {
            blockedBundles.remove(bundleId)
        } else {
            blockedBundles.insert(bundleId)
        }
        let array = Array(blockedBundles)
        defaults.set(array, forKey: keyBlockedBundles)
        sendJsonCommand([
            "cmd": "SET_BLOCKED",
            "bundles": array
        ])
    }

    func sendMediaCommand(_ action: String) {
        sendJsonCommand([
            "cmd": "MEDIA_CMD",
            "action": action
        ])
    }

    func sendPhoneContextToWatch() {
        let rawBat = UIDevice.current.batteryLevel
        let pct = rawBat >= 0 ? Int(rawBat * 100) : 100
        let chg = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        let nextEventTitle = fetchNextCalendarEventSummary()

        var payload: [String: Any] = [
            "cmd": "PHONE_CONTEXT",
            "bat": pct,
            "chg": chg,
            "platform": "iOS",
            "phoneName": UIDevice.current.name
        ]
        if let nextEventTitle {
            payload["nextEvent"] = nextEventTitle
        }
        sendJsonCommand(payload)
    }

    private func sendJsonCommand(_ dict: [String: Any]) {
        guard let peripheral = watchPeripheral,
              peripheral.state == .connected,
              let char = commandConfigCharacteristic,
              let data = try? JSONSerialization.data(withJSONObject: dict, options: []) else {
            return
        }
        let writeType: CBCharacteristicWriteType = char.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        peripheral.writeValue(data, for: char, type: writeType)
    }

    private func fetchNextCalendarEventSummary() -> String? {
        let status = EKEventStore.authorizationStatus(for: .event)
        guard status == .fullAccess || status == .authorized else { return nil }
        let now = Date()
        let end = now.addingTimeInterval(12 * 3600)
        let predicate = eventStore.predicateForEvents(withStart: now, end: end, calendars: nil)
        guard let next = eventStore.events(matching: predicate).first(where: { !$0.isAllDay }) else { return nil }

        let fmt = DateFormatter()
        fmt.dateFormat = "HH:mm"
        return "\(fmt.string(from: next.startDate)) \(next.title ?? "")"
    }

    private func handleIncomingWatchEventData(_ data: Data) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let evt = json["evt"] as? String else { return }

        switch evt {
        case "FIND_PHONE":
            PhoneFindMyAlarmManager.shared.triggerFindPhoneAlarm()

        case "NOTIF_LOG":
            if let decoded = try? JSONDecoder().decode(WatchNotificationLogItem.self, from: data) {
                recentNotifications.removeAll { $0.uid == decoded.uid }
                recentNotifications.insert(decoded, at: 0)
                if recentNotifications.count > 50 {
                    recentNotifications = Array(recentNotifications.prefix(50))
                }
                if !discoveredApps.contains(where: { $0.bundleId == decoded.app }) {
                    discoveredApps.append(
                        IosAppFilterCatalogItem(name: decoded.appName, bundleId: decoded.app, icon: "📱")
                    )
                }
            }

        default:
            break
        }
    }
}

extension WatchBleCentralManager: CBCentralManagerDelegate, CBPeripheralDelegate {

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            if central.state == .poweredOn {
                scanOrReconnectWatch()
            } else {
                isConnected = false
                connectionStatusText = "Bluetooth apagado"
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String : Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
           let restored = peripherals.first {
            Task { @MainActor in
                connectToPeripheral(restored)
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String : Any],
        rssi RSSI: NSNumber
    ) {
        Task { @MainActor in
            central.stopScan()
            connectToPeripheral(peripheral)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            isConnected = true
            connectionStatusText = "Conectado por BLE Directo"
            if let name = peripheral.name, !name.isEmpty {
                watchName = name
            }
            peripheral.discoverServices([BridgeBleUUIDs.bridgeService])
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor in
            isConnected = false
            connectionStatusText = "En espera de reconexión BLE..."
            // En iOS, llamar connect() sobre el periférico mantiene una solicitud pasiva que reconecta sin gastar batería
            central.connect(peripheral, options: [
                CBConnectPeripheralOptionNotifyOnConnectionKey: true,
                CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
            ])
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == BridgeBleUUIDs.bridgeService {
            peripheral.discoverCharacteristics([
                BridgeBleUUIDs.telemetryChar,
                BridgeBleUUIDs.commandConfigChar,
                BridgeBleUUIDs.watchEventChar
            ], for: service)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard let chars = service.characteristics else { return }
        Task { @MainActor in
            for char in chars {
                switch char.uuid {
                case BridgeBleUUIDs.telemetryChar:
                    telemetryCharacteristic = char
                    peripheral.setNotifyValue(true, for: char)
                    peripheral.readValue(for: char)

                case BridgeBleUUIDs.commandConfigChar:
                    commandConfigCharacteristic = char
                    sendPhoneContextToWatch()
                    sendJsonCommand([
                        "cmd": "SET_BLOCKED",
                        "bundles": Array(blockedBundles)
                    ])

                case BridgeBleUUIDs.watchEventChar:
                    watchEventCharacteristic = char
                    peripheral.setNotifyValue(true, for: char)

                default:
                    break
                }
            }
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard let data = characteristic.value else { return }
        Task { @MainActor in
            switch characteristic.uuid {
            case BridgeBleUUIDs.telemetryChar:
                if let decoded = try? JSONDecoder().decode(WatchTelemetryPayload.self, from: data) {
                    telemetry = decoded
                    if let model = decoded.model, !model.isEmpty {
                        watchName = model
                    }
                    isSyncingPulse = false
                    HealthKitSyncManager.shared.ingestWatchTelemetry(decoded)
                }

            case BridgeBleUUIDs.watchEventChar:
                handleIncomingWatchEventData(data)

            default:
                break
            }
        }
    }
}
