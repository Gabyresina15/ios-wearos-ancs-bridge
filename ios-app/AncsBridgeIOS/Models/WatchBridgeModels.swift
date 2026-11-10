import Foundation
import CoreBluetooth

// MARK: - UUIDs del canal BLE directo (deben coincidir con AppleBleUuids del reloj)

enum BridgeBleUUIDs {
    static let bridgeService = CBUUID(string: "D973F2E0-B19E-11E2-9E96-0800200C9A66")
    static let telemetryChar = CBUUID(string: "D973F2E1-B19E-11E2-9E96-0800200C9A66")
    static let commandConfigChar = CBUUID(string: "D973F2E2-B19E-11E2-9E96-0800200C9A66")
    static let watchEventChar = CBUUID(string: "D973F2E3-B19E-11E2-9E96-0800200C9A66")

    static let genericHeartRateService = CBUUID(string: "180D")
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
    var model: String = "Galaxy Watch"
    var ts: Int64 = 0

    init() {}

    enum CodingKeys: String, CodingKey {
        case bat, chg, steps, hr, dnd, hfp, hid, model, ts
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
        model = (try? c.decodeIfPresent(String.self, forKey: .model)) ?? "Galaxy Watch"
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
    let appName: String
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
    let name: String
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
