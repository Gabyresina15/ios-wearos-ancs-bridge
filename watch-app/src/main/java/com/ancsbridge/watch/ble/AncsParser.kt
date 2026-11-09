package com.ancsbridge.watch.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object AncsProtocol {

    /**
     * Parsea los 8 bytes fijos del Notification Source (UUID: 9FBF120D-...).
     * Estructura: EventID(1B) | EventFlags(1B) | CategoryID(1B) | CategoryCount(1B) | NotificationUID(4B LE)
     */
    fun parseNotificationSource(bytes: ByteArray): AncsNotificationHeader? {
        if (bytes.size < 8) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eventId = AncsEventId.fromByte(buffer.get())
        val eventFlags = buffer.get().toInt() and 0xFF
        val categoryId = buffer.get().toInt() and 0xFF
        val categoryCount = buffer.get().toInt() and 0xFF
        val uid = buffer.int
        return AncsNotificationHeader(eventId, eventFlags, categoryId, categoryCount, uid)
    }

    /**
     * Construye el comando GetNotificationAttributes (CommandID = 0x00) para escribir en Control Point.
     * Solicita 6 atributos:
     * - AppIdentifier (0)
     * - Title (1, max 96B)
     * - Subtitle (2, max 96B)
     * - Message (3, max 512B)
     * - PositiveActionLabel (6)
     * - NegativeActionLabel (7)
     */
    fun buildGetNotificationAttributesCommand(notificationUid: Int): ByteArray {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x00.toByte()) // CommandIDGetNotificationAttributes
        buffer.putInt(notificationUid)

        // Attribute 0: AppIdentifier (no lleva parámetro de longitud máxima)
        buffer.put(AncsAttributeId.APP_IDENTIFIER.code)

        // Attribute 1: Title (2 bytes Little Endian max length = 96)
        buffer.put(AncsAttributeId.TITLE.code)
        buffer.putShort(96.toShort())

        // Attribute 2: Subtitle (2 bytes Little Endian max length = 96)
        buffer.put(AncsAttributeId.SUBTITLE.code)
        buffer.putShort(96.toShort())

        // Attribute 3: Message (2 bytes Little Endian max length = 512)
        buffer.put(AncsAttributeId.MESSAGE.code)
        buffer.putShort(512.toShort())

        // Attribute 6: PositiveActionLabel (no lleva longitud máxima según spec ANCS)
        buffer.put(AncsAttributeId.POSITIVE_ACTION_LABEL.code)

        // Attribute 7: NegativeActionLabel (no lleva longitud máxima según spec ANCS)
        buffer.put(AncsAttributeId.NEGATIVE_ACTION_LABEL.code)

        return buffer.array()
    }

    /**
     * Construye el comando PerformNotificationAction (CommandID = 0x02) para aceptar/descartar en iOS.
     */
    fun buildPerformNotificationActionCommand(notificationUid: Int, action: AncsActionId): ByteArray {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x02.toByte()) // CommandIDPerformNotificationAction
        buffer.putInt(notificationUid)
        buffer.put(action.code)
        return buffer.array()
    }
}

/**
 * Ensamblador de paquetes fragmentados de la característica Data Source (22EAC6E9-...).
 * Incluye protección por timeout para descartar fragmentos huérfanos si un paquete BLE se pierde.
 */
class AncsDataSourceCollector(
    private val expectedAttributesCount: Int = 6,
    private val onNotificationParsed: (AncsNotification) -> Unit
) {
    private val accumulator = ByteArrayOutputStream()
    private var lastChunkTimestampMs: Long = 0L

    @Synchronized
    fun onDataChunkReceived(chunk: ByteArray) {
        val now = System.currentTimeMillis()
        if (accumulator.size() > 0 && now - lastChunkTimestampMs > 2500L) {
            // Limpiamos fragmentos huérfanos de una notificación previa truncada
            accumulator.reset()
        }
        lastChunkTimestampMs = now
        accumulator.write(chunk)
        tryParseBuffer()
    }

    @Synchronized
    fun reset() {
        accumulator.reset()
        lastChunkTimestampMs = 0L
    }

    private fun tryParseBuffer() {
        val rawBytes = accumulator.toByteArray()
        // Mínimo requerido: CommandID(1B) + NotificationUID(4B) = 5 bytes
        if (rawBytes.size < 5) return

        val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val commandId = buffer.get().toInt() and 0xFF
        if (commandId != 0x00) {
            accumulator.reset()
            return
        }

        val uid = buffer.int
        val parsedAttrs = mutableMapOf<Byte, String>()

        for (i in 0 until expectedAttributesCount) {
            if (buffer.remaining() < 3) {
                return
            }

            val attrId = buffer.get()
            val attrLen = buffer.short.toInt() and 0xFFFF

            if (buffer.remaining() < attrLen) {
                return
            }

            val payload = ByteArray(attrLen)
            buffer.get(payload)
            parsedAttrs[attrId] = String(payload, StandardCharsets.UTF_8).trim()
        }

        val remainingBytes = ByteArray(buffer.remaining())
        buffer.get(remainingBytes)
        accumulator.reset()
        if (remainingBytes.isNotEmpty()) {
            accumulator.write(remainingBytes)
        }

        val bundleId = parsedAttrs[AncsAttributeId.APP_IDENTIFIER.code].orEmpty()
        val title = parsedAttrs[AncsAttributeId.TITLE.code].orEmpty()
        val subtitle = parsedAttrs[AncsAttributeId.SUBTITLE.code].orEmpty()
        val message = parsedAttrs[AncsAttributeId.MESSAGE.code].orEmpty()
        val posLabel = parsedAttrs[AncsAttributeId.POSITIVE_ACTION_LABEL.code].orEmpty()
        val negLabel = parsedAttrs[AncsAttributeId.NEGATIVE_ACTION_LABEL.code].orEmpty()

        onNotificationParsed(
            AncsNotification(
                uid = uid,
                appIdentifier = bundleId,
                appDisplayName = AppBundleResolver.resolveName(bundleId),
                title = title.ifEmpty { AppBundleResolver.resolveName(bundleId) },
                subtitle = subtitle,
                message = message,
                positiveActionLabel = posLabel,
                negativeActionLabel = negLabel
            )
        )

        if (remainingBytes.isNotEmpty()) {
            tryParseBuffer()
        }
    }
}
