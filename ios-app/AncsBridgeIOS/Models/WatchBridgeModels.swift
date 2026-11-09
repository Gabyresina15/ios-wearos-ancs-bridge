import Foundation
import CoreBluetooth

enum BridgeBleUUIDs {
    static let bridgeService = CBUUID(string: "D973F2E0-B19E-11E2-9E96-0800200C9A66")
    static let telemetryChar = CBUUID(string: "D973F2E1-B19E-11E2-9E96-0800200C9A66")
    static let commandConfigChar = CBUUID(string: "D973F2E2-B19E-11E2-9E96-0800200C9A66")
    static let watchEventChar = CBUUID(string: "D973F2E3-B19E-11E2-9E96-0800200C9A66")

    static let genericHeartRateService = CBUUID(string: "180D")
    static let ancsService = CBUUID(string: "7905F431-B5CE-4E99-A40F-4B1E122D00D0")
}

struct WatchTelemetryPayload: Codable, Equatable {
    let bat: Int
    let chg: Bool
    let steps: Int
    let hr: Int
    let wt: Double?
    let fat: Double?
    let dnd: Bool?
    let hfp: Bool?
    let hid: Bool?
    let model: String?
    let ts: Int64
}

struct WatchNotificationLogItem: Identifiable, Codable, Equatable {
    var id: Int { uid }
    let uid: Int
    let app: String
    let appName: String
    let title: String
    let msg: String
    let ts: Int64

    var formattedTime: String {
        let date = Date(timeIntervalSince1970: TimeInterval(ts) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}

struct IosAppFilterCatalogItem: Identifiable, Codable, Equatable {
    var id: String { bundleId }
    let name: String
    let bundleId: String
    let icon: String

    static let defaultCatalog: [IosAppFilterCatalogItem] = [
        IosAppFilterCatalogItem(name: "WhatsApp", bundleId: "net.whatsapp.WhatsApp", icon: "💬"),
        IosAppFilterCatalogItem(name: "Instagram", bundleId: "com.burbn.instagram", icon: "📸"),
        IosAppFilterCatalogItem(name: "Llamadas", bundleId: "com.apple.mobilephone", icon: "📞"),
        IosAppFilterCatalogItem(name: "iMessage / SMS", bundleId: "com.apple.MobileSMS", icon: "✉️"),
        IosAppFilterCatalogItem(name: "Telegram", bundleId: "ph.telegra.Telegraph", icon: "✈️"),
        IosAppFilterCatalogItem(name: "Gmail", bundleId: "com.google.Gmail", icon: "📧"),
        IosAppFilterCatalogItem(name: "Mercado Pago", bundleId: "com.mercadopago.MercadoPago", icon: "💳"),
        IosAppFilterCatalogItem(name: "Calendario", bundleId: "com.apple.mobilecal", icon: "📅"),
        IosAppFilterCatalogItem(name: "X (Twitter)", bundleId: "com.atebits.Tweetie2", icon: "🐦"),
        IosAppFilterCatalogItem(name: "Spotify", bundleId: "com.spotify.client", icon: "🎵")
    ]
}
