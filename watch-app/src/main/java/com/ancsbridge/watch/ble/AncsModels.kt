package com.ancsbridge.watch.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object AppleBleUuids {
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val GENERIC_HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

    val GAP_SERVICE: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    val GAP_DEVICE_NAME_CHAR: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    val DIS_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val DIS_MODEL_NUMBER_CHAR: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")

    val CTS_SERVICE: UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
    val CTS_CURRENT_TIME_CHAR: UUID = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")

    val BAS_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BAS_BATTERY_LEVEL_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    val ANCS_SERVICE: UUID = UUID.fromString("7905f431-b5ce-4e99-a40f-4b1e122d00d0")
    val ANCS_NOTIFICATION_SOURCE: UUID = UUID.fromString("9fbf120d-6301-42d9-8c58-25e699a21dbd")
    val ANCS_CONTROL_POINT: UUID = UUID.fromString("69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9")
    val ANCS_DATA_SOURCE: UUID = UUID.fromString("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb")

    val AMS_SERVICE: UUID = UUID.fromString("89d3502b-0f36-433a-8ef4-c502ad55f8dc")
    val AMS_REMOTE_COMMAND: UUID = UUID.fromString("9b3c81d8-57b1-4a8a-b8df-0e56f7ca51c2")
    val AMS_ENTITY_UPDATE: UUID = UUID.fromString("2f7cabce-808d-411f-9a0c-bb92ba96c102")
}

object AppleDeviceModelResolver {
    private val appleModels = mapOf(
        "iPhone17,2" to "iPhone 16 Pro Max",
        "iPhone17,1" to "iPhone 16 Pro",
        "iPhone17,3" to "iPhone 16",
        "iPhone17,4" to "iPhone 16 Plus",
        "iPhone17,5" to "iPhone 16e",
        "iPhone16,2" to "iPhone 15 Pro Max",
        "iPhone16,1" to "iPhone 15 Pro",
        "iPhone15,5" to "iPhone 15 Plus",
        "iPhone15,4" to "iPhone 15",
        "iPhone15,3" to "iPhone 14 Pro Max",
        "iPhone15,2" to "iPhone 14 Pro",
        "iPhone14,8" to "iPhone 14 Plus",
        "iPhone14,7" to "iPhone 14",
        "iPhone14,3" to "iPhone 13 Pro Max",
        "iPhone14,2" to "iPhone 13 Pro",
        "iPhone14,5" to "iPhone 13"
    )

    fun resolveMarketingName(rawModelCode: String): String {
        val clean = rawModelCode.trim()
        return appleModels[clean] ?: clean
    }

    fun formatDisplayIdentity(gapName: String?, modelCode: String?): String {
        val resolvedModel = modelCode?.takeIf { it.isNotBlank() }?.let { resolveMarketingName(it) }
        val cleanName = gapName?.trim()?.takeIf { it.isNotBlank() }

        return when {
            resolvedModel != null && cleanName != null && !cleanName.equals("iPhone", ignoreCase = true) ->
                "$cleanName ($resolvedModel)"
            resolvedModel != null -> resolvedModel
            cleanName != null -> cleanName
            else -> "iPhone vinculado"
        }
    }
}

data class NowPlayingState(
    val title: String = "Sin reproducción",
    val artist: String = "Control multimedia",
    val isPlaying: Boolean = false
)

data class SyncedIphoneTime(
    val epochMillis: Long,
    val formattedTime: String,
    val clockDriftMillis: Long,
    val systemClockUpdated: Boolean
)

object CtsTimeParser {
    private val formatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

    fun parseCurrentTime(bytes: ByteArray): SyncedIphoneTime? {
        if (bytes.size < 7) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val year = buffer.short.toInt() and 0xFFFF
            val month = buffer.get().toInt() and 0xFF
            val day = buffer.get().toInt() and 0xFF
            val hours = buffer.get().toInt() and 0xFF
            val minutes = buffer.get().toInt() and 0xFF
            val seconds = buffer.get().toInt() and 0xFF

            val fraction256 = if (bytes.size >= 9) {
                buffer.get()
                buffer.get().toInt() and 0xFF
            } else 0

            val nanos = ((fraction256 / 256.0) * 1_000_000_000L).toInt()
            val localDateTime = LocalDateTime.of(year, month, day, hours, minutes, seconds, nanos)
            val zonedDateTime = localDateTime.atZone(ZoneId.systemDefault())
            val iphoneEpochMillis = zonedDateTime.toInstant().toEpochMilli()
            val drift = iphoneEpochMillis - System.currentTimeMillis()

            SyncedIphoneTime(
                epochMillis = iphoneEpochMillis,
                formattedTime = localDateTime.format(formatter),
                clockDriftMillis = drift,
                systemClockUpdated = false
            )
        }.getOrNull()
    }
}

object AmsEntityParser {
    val SUBSCRIBE_PLAYER_PLAYBACK_INFO = byteArrayOf(0x00, 0x01)
    val SUBSCRIBE_TRACK_ARTIST_AND_TITLE = byteArrayOf(0x02, 0x00, 0x02)

    fun applyEntityUpdate(current: NowPlayingState, bytes: ByteArray): NowPlayingState {
        if (bytes.size < 3) return current
        val entityId = bytes[0].toInt() and 0xFF
        val attrId = bytes[1].toInt() and 0xFF
        val textValue = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8).trim()

        return when (entityId) {
            0 -> {
                if (attrId == 1) {
                    val stateCode = textValue.substringBefore(',').toIntOrNull() ?: 0
                    current.copy(isPlaying = (stateCode == 1))
                } else current
            }
            2 -> {
                when (attrId) {
                    0 -> current.copy(artist = textValue.ifEmpty { "Artista desconocido" })
                    2 -> current.copy(title = textValue.ifEmpty { "Reproduciendo" })
                    else -> current
                }
            }
            else -> current
        }
    }
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
    CONNECTING("Conectando..."),
    BONDING("Vinculando..."),
    READY("Conectado")
}

data class AncsNotificationHeader(
    val eventId: AncsEventId,
    val eventFlags: Int,
    val categoryId: Int,
    val categoryCount: Int,
    val notificationUid: Int
) {
    val isSilent: Boolean get() = (eventFlags and 0x01) != 0
    val isImportant: Boolean get() = (eventFlags and 0x02) != 0
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
    val receivedInDndMode: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

object AppBundleResolver {
    private val knownBundles = mapOf(
        "net.whatsapp.WhatsApp" to "WhatsApp",
        "com.burbn.instagram" to "Instagram",
        "com.atebits.Tweetie2" to "X",
        "com.google.Gmail" to "Gmail",
        "com.apple.MobileSMS" to "Mensajes",
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
