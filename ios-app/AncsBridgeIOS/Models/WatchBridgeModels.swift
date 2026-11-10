import Foundation
import CoreBluetooth
import Darwin

// MARK: - UUIDs del canal BLE directo (deben coincidir con AppleBleUuids del reloj)

enum BridgeBleUUIDs {
    static let bridgeService = CBUUID(string: "D973F2E0-B19E-11E2-9E96-0800200C9A66")
    static let telemetryChar = CBUUID(string: "D973F2E1-B19E-11E2-9E96-0800200C9A66")
    static let commandConfigChar = CBUUID(string: "D973F2E2-B19E-11E2-9E96-0800200C9A66")
    static let watchEventChar = CBUUID(string: "D973F2E3-B19E-11E2-9E96-0800200C9A66")

    static let genericHeartRateService = CBUUID(string: "180D")
}

// MARK: - Resolución Dinámica de Hardware (Cero Nombres Hardcodeados)

enum DeviceHardwareResolver {
    private static let watchModels: [String: String] = [
        "SM-R960": "Galaxy Watch6 Classic",
        "SM-R965": "Galaxy Watch6 Classic",
        "SM-R950": "Galaxy Watch6 Classic",
        "SM-R955": "Galaxy Watch6 Classic",
        "SM-R940": "Galaxy Watch6",
        "SM-R945": "Galaxy Watch6",
        "SM-R930": "Galaxy Watch6",
        "SM-R935": "Galaxy Watch6",
        "SM-L310": "Galaxy Watch7",
        "SM-L315": "Galaxy Watch7",
        "SM-L300": "Galaxy Watch7",
        "SM-L305": "Galaxy Watch7",
        "SM-L705": "Galaxy Watch Ultra",
        "SM-R920": "Galaxy Watch5 Pro",
        "SM-R925": "Galaxy Watch5 Pro",
        "SM-R910": "Galaxy Watch5",
        "SM-R900": "Galaxy Watch5",
        "SM-R890": "Galaxy Watch4 Classic",
        "SM-R880": "Galaxy Watch4 Classic",
        "SM-R870": "Galaxy Watch4",
        "SM-R860": "Galaxy Watch4"
    ]

    /// Obtiene el identificador exacto de máquina del kernel Darwin (ej. "iPhone17,2")
    static func currentMachineIdentifier() -> String {
        var sysinfo = utsname()
        uname(&sysinfo)
        let machineMirror = Mirror(reflecting: sysinfo.machine)
        let identifier = machineMirror.children.reduce("") { id, element in
            guard let value = element.value as? Int8, value != 0 else { return id }
            return id + String(UnicodeScalar(UInt8(value)))
        }
        return identifier.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Normaliza o traduce dinámicamente el nombre real reportado por el reloj
    static func normalizeWatchName(_ raw: String) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return nil }
        let prefix = String(clean.prefix(7)).uppercased()
        if let mapped = watchModels[prefix] {
            return mapped
        }
        return clean
    }
}

// MARK: - Estado de conexión

enum WatchLinkState: Equatable {
    case bluetoothOff
    case unauthorized
    case searching
    case connecting
    case discovering
    case ready
    case missingService

    var title: String {
        switch self {
        case .bluetoothOff: return "Bluetooth apagado"
        case .unauthorized: return "Permiso de Bluetooth denegado"
        case .searching: return "Buscando tu reloj…"
        case .connecting: return "Conectando…"
        case .discovering: return "Preparando enlace…"
        case .ready: return "Conectado"
        case .missingService: return "Abrí Watch Bridge en el reloj"
        }
    }

    var isReady: Bool { self == .ready }
}

// MARK: - Telemetría que envía el reloj

struct WatchTelemetryPayload: Decodable, Equatable {
    var bat: Int = 0
    var chg: Bool = false
    var steps: Int = 0
    var hr: Int = 0
    var dnd: Bool = false
    var hfp: Bool = false
    var hid: Bool = false
    var model: String = ""
    var mTitle: String = "Sin reproducción"
    var mArtist: String = "Control multimedia"
    var mPlay: Bool = false
    var mVol: Int? = nil
    var ts: Int64 = 0

    init() {}

    enum CodingKeys: String, CodingKey {
        case bat, chg, steps, hr, dnd, hfp, hid, model, mTitle, mArtist, mPlay, mVol, ts
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        bat = (try? c.decodeIfPresent(Int.self, forKey: .bat)) ?? 0
        chg = (try? c.decodeIfPresent(Bool.self, forKey: .chg)) ?? false
        steps = (try? c.decodeIfPresent(Int.self, forKey: .steps)) ?? 0
        hr = (try? c.decodeIfPresent(Int.self, forKey: .hr)) ?? 0
        dnd = (try? c.decodeIfPresent(Bool.self, forKey: .dnd)) ?? false
        hfp = (try? c.decodeIfPresent(Bool.self, forKey: .hfp)) ?? false
        hid = (try? c.decodeIfPresent(Bool.self, forKey: .hid)) ?? false
        model = (try? c.decodeIfPresent(String.self, forKey: .model)) ?? ""
        mTitle = (try? c.decodeIfPresent(String.self, forKey: .mTitle)) ?? "Sin reproducción"
        mArtist = (try? c.decodeIfPresent(String.self, forKey: .mArtist)) ?? "Control multimedia"
        mPlay = (try? c.decodeIfPresent(Bool.self, forKey: .mPlay)) ?? false
        mVol = try? c.decodeIfPresent(Int.self, forKey: .mVol)
        ts = (try? c.decodeIfPresent(Int64.self, forKey: .ts)) ?? 0
    }

    var hasData: Bool { ts > 0 }
}

// MARK: - Composición corporal (Apple Salud ← balanza Femmto)

struct BodyComposition: Equatable {
    var weightKg: Double?
    var bodyFatPercent: Double?
    var bmi: Double?
    var leanMassKg: Double?
    var measuredAt: Date?
    var sourceName: String?

    var isEmpty: Bool { weightKg == nil && bodyFatPercent == nil }

    /// Payload compacto para el reloj (comando BODY_COMP)
    func blePayload() -> [String: Any] {
        var dict: [String: Any] = ["cmd": "BODY_COMP"]
        if let weightKg { dict["wt"] = (weightKg * 10).rounded() / 10 }
        if let bodyFatPercent { dict["fat"] = (bodyFatPercent * 10).rounded() / 10 }
        if let bmi { dict["bmi"] = (bmi * 10).rounded() / 10 }
        if let leanMassKg { dict["lean"] = (leanMassKg * 10).rounded() / 10 }
        if let measuredAt { dict["mts"] = Int64(measuredAt.timeIntervalSince1970 * 1000) }
        if let sourceName { dict["src"] = String(sourceName.prefix(24)) }
        return dict
    }
}

// MARK: - Historial de notificaciones reenviadas por el reloj

struct WatchNotificationLogItem: Identifiable, Decodable, Equatable {
    var id: Int { uid }
    let uid: Int
    let app: String
    var appName: String
    let title: String
    let msg: String
    let ts: Int64

    enum CodingKeys: String, CodingKey { case uid, app, appName, title, msg, ts }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uid = try c.decode(Int.self, forKey: .uid)
        app = (try? c.decodeIfPresent(String.self, forKey: .app)) ?? ""
        appName = (try? c.decodeIfPresent(String.self, forKey: .appName)) ?? "App"
        title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? ""
        msg = (try? c.decodeIfPresent(String.self, forKey: .msg)) ?? ""
        ts = (try? c.decodeIfPresent(Int64.self, forKey: .ts)) ?? Int64(Date().timeIntervalSince1970 * 1000)
    }

    var date: Date { Date(timeIntervalSince1970: TimeInterval(ts) / 1000.0) }
}

// MARK: - Catálogo de apps filtrables

struct IosAppFilterCatalogItem: Identifiable, Equatable {
    var id: String { bundleId }
    var name: String
    let bundleId: String
    let symbol: String

    static let defaultCatalog: [IosAppFilterCatalogItem] = [
        IosAppFilterCatalogItem(name: "WhatsApp", bundleId: "net.whatsapp.WhatsApp", symbol: "message.fill"),
        IosAppFilterCatalogItem(name: "Llamadas", bundleId: "com.apple.mobilephone", symbol: "phone.fill"),
        IosAppFilterCatalogItem(name: "Mensajes", bundleId: "com.apple.MobileSMS", symbol: "bubble.left.fill"),
        IosAppFilterCatalogItem(name: "Instagram", bundleId: "com.burbn.instagram", symbol: "camera.fill"),
        IosAppFilterCatalogItem(name: "Telegram", bundleId: "ph.telegra.Telegraph", symbol: "paperplane.fill"),
        IosAppFilterCatalogItem(name: "Gmail", bundleId: "com.google.Gmail", symbol: "envelope.fill"),
        IosAppFilterCatalogItem(name: "Mercado Pago", bundleId: "com.mercadopago.MercadoPago", symbol: "creditcard.fill"),
        IosAppFilterCatalogItem(name: "Calendario", bundleId: "com.apple.mobilecal", symbol: "calendar"),
        IosAppFilterCatalogItem(name: "X", bundleId: "com.atebits.Tweetie2", symbol: "at"),
        IosAppFilterCatalogItem(name: "Spotify", bundleId: "com.spotify.client", symbol: "music.note"),
        IosAppFilterCatalogItem(name: "Consejos", bundleId: "com.apple.tips", symbol: "lightbulb.fill")
    ]
}
