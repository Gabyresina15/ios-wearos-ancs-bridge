package com.ancsbridge.watch.ble

import java.util.UUID

object AppleBleUuids {
    // Descriptor estándar de notificaciones BLE
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Servicio genérico Heart Rate para visibilidad inmediata en Ajustes > Bluetooth de iOS
    val GENERIC_HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

    // Apple Notification Center Service (ANCS)
    val ANCS_SERVICE: UUID = UUID.fromString("7905f431-b5ce-4e99-a40f-4b1e122d00d0")
    val ANCS_NOTIFICATION_SOURCE: UUID = UUID.fromString("9fbf120d-6301-42d9-8c58-25e699a21dbd")
    val ANCS_CONTROL_POINT: UUID = UUID.fromString("69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9")
    val ANCS_DATA_SOURCE: UUID = UUID.fromString("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb")

    // Apple Media Service (AMS)
    val AMS_SERVICE: UUID = UUID.fromString("89d3502b-0f36-433a-8ef4-c502ad55f8dc")
    val AMS_REMOTE_COMMAND: UUID = UUID.fromString("9b3c81d8-57b1-4a8a-b8df-0e56f7ca51c2")
}

enum class AncsEventId(val code: Byte) {
    ADDED(0),
    MODIFIED(1),
    REMOVED(2);

    companion object {
        fun fromByte(b: Byte): AncsEventId = entries.firstOrNull { it.code == b } ?: ADDED
    }
}

enum class AncsAttributeId(val code: Byte) {
    APP_IDENTIFIER(0),
    TITLE(1),
    SUBTITLE(2),
    MESSAGE(3),
    MESSAGE_SIZE(4),
    DATE(5),
    POSITIVE_ACTION_LABEL(6),
    NEGATIVE_ACTION_LABEL(7);
}

enum class AncsActionId(val code: Byte) {
    POSITIVE(0),
    NEGATIVE(1)
}

enum class AmsRemoteCommandId(val code: Byte) {
    PLAY(0),
    PAUSE(1),
    TOGGLE_PLAY_PAUSE(2),
    NEXT_TRACK(3),
    PREVIOUS_TRACK(4),
    VOLUME_UP(5),
    VOLUME_DOWN(6)
}

enum class BleConnectionState(val label: String) {
    DISCONNECTED("Desconectado"),
    ADVERTISING("Esperando iPhone..."),
    CONNECTING("Negociando GATT..."),
    BONDING("Emparejando ANCS..."),
    READY("ANCS + AMS Activo")
}

data class AncsNotificationHeader(
    val eventId: AncsEventId,
    val eventFlags: Int,
    val categoryId: Int,
    val categoryCount: Int,
    val notificationUid: Int
) {
    val isSilent: Boolean get() = (eventFlags and 0x01) != 0
    val isPreExisting: Boolean get() = (eventFlags and 0x04) != 0
    val hasPositiveAction: Boolean get() = (eventFlags and 0x08) != 0
    val hasNegativeAction: Boolean get() = (eventFlags and 0x10) != 0
}

data class AncsNotification(
    val uid: Int,
    val appIdentifier: String,
    val appDisplayName: String,
    val title: String,
    val subtitle: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

object AppBundleResolver {
    private val knownBundles = mapOf(
        "net.whatsapp.WhatsApp" to "WhatsApp",
        "com.burbn.instagram" to "Instagram",
        "com.atebits.Tweetie2" to "X",
        "com.google.Gmail" to "Gmail",
        "com.apple.MobileSMS" to "iMessage",
        "com.apple.mobilephone" to "Teléfono",
        "com.apple.mobilecal" to "Calendario",
        "ph.telegra.Telegraph" to "Telegram",
        "com.HammerAndChisel.Discord" to "Discord",
        "com.spotify.client" to "Spotify",
        "com.mercadopago.MercadoPago" to "Mercado Pago"
    )

    fun resolveName(bundleId: String): String {
        return knownBundles[bundleId]
            ?: bundleId.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
