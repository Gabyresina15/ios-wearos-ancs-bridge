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
     */
    fun buildGetNotificationAttributesCommand(notificationUid: Int): ByteArray {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x00.toByte()) // CommandIDGetNotificationAttributes
        buffer.putInt(notificationUid)

        // Attribute 0: AppIdentifier (sin longitud máxima)
        buffer.put(AncsAttributeId.APP_IDENTIFIER.code)

        // Attribute 1: Title (max length = 96)
        buffer.put(AncsAttributeId.TITLE.code)
        buffer.putShort(96.toShort())

        // Attribute 2: Subtitle (max length = 96)
        buffer.put(AncsAttributeId.SUBTITLE.code)
        buffer.putShort(96.toShort())

        // Attribute 3: Message (max length = 512)
        buffer.put(AncsAttributeId.MESSAGE.code)
        buffer.putShort(512.toShort())

        // Attribute 6: PositiveActionLabel
        buffer.put(AncsAttributeId.POSITIVE_ACTION_LABEL.code)

        // Attribute 7: NegativeActionLabel
        buffer.put(AncsAttributeId.NEGATIVE_ACTION_LABEL.code)

        return buffer.array()
    }

    /**
     * Construye el comando nativo ANCS GetAppAttributes (CommandID = 0x01) para consultar a iOS
     * el nombre oficial real de cualquier aplicación instalada (AppAttributeIDDisplayName = 0x00).
     */
    fun buildGetAppAttributesCommand(appIdentifier: String): ByteArray {
        val idBytes = appIdentifier.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + idBytes.size + 1 + 1)
        buffer.put(0x01.toByte()) // CommandIDGetAppAttributes
        buffer.put(idBytes)
        buffer.put(0x00.toByte()) // Null terminator del string UTF-8
        buffer.put(0x00.toByte()) // AppAttributeIDDisplayName = 0x00
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
 * Soporta tanto respuestas GetNotificationAttributes (0x00) como GetAppAttributes (0x01).
 */
class AncsDataSourceCollector(
    private val expectedAttributesCount: Int = 6,
    private val onAppAttributeParsed: ((bundleId: String, displayName: String) -> Unit)? = null,
    private val onNotificationParsed: (AncsNotification) -> Unit
) {
    private val accumulator = ByteArrayOutputStream()
    private var lastChunkTimestampMs: Long = 0L

    @Synchronized
    fun onDataChunkReceived(chunk: ByteArray) {
        val now = System.currentTimeMillis()
        if (accumulator.size() > 0 && now - lastChunkTimestampMs > 2500L) {
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
        if (rawBytes.isEmpty()) return

        val commandId = rawBytes[0].toInt() and 0xFF
        when (commandId) {
            0x00 -> tryParseNotificationAttributes(rawBytes)
            0x01 -> tryParseAppAttributes(rawBytes)
            else -> accumulator.reset()
        }
    }

    private fun tryParseNotificationAttributes(rawBytes: ByteArray) {
        if (rawBytes.size < 5) return
        val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // 0x00
        val uid = buffer.int
        val parsedAttrs = mutableMapOf<Byte, String>()

        for (i in 0 until expectedAttributesCount) {
            if (buffer.remaining() < 3) return
            val attrId = buffer.get()
            val attrLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < attrLen) return

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

    private fun tryParseAppAttributes(rawBytes: ByteArray) {
        // Formato: 0x01 | AppIdentifier (null-terminated) | AttrId(1B) | AttrLen(2B LE) | Value(AttrLen B)
        var nullIndex = -1
        for (i in 1 until rawBytes.size) {
            if (rawBytes[i] == 0x00.toByte()) {
                nullIndex = i
                break
            }
        }
        if (nullIndex == -1) return // Aún no llegó el null terminator del bundleId
        val headerAfterNull = nullIndex + 1
        if (rawBytes.size < headerAfterNull + 3) return

        val buffer = ByteBuffer.wrap(rawBytes, headerAfterNull, rawBytes.size - headerAfterNull)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // AttrId (0x00 = DisplayName)
        val attrLen = buffer.short.toInt() and 0xFFFF
        if (buffer.remaining() < attrLen) return

        val nameBytes = ByteArray(attrLen)
        buffer.get(nameBytes)

        val bundleId = String(rawBytes, 1, nullIndex - 1, StandardCharsets.UTF_8).trim()
        val displayName = String(nameBytes, StandardCharsets.UTF_8).trim()

        val remainingBytes = ByteArray(buffer.remaining())
        buffer.get(remainingBytes)
        accumulator.reset()
        if (remainingBytes.isNotEmpty()) {
            accumulator.write(remainingBytes)
        }

        if (bundleId.isNotEmpty() && displayName.isNotEmpty()) {
            onAppAttributeParsed?.invoke(bundleId, displayName)
        }

        if (remainingBytes.isNotEmpty()) {
            tryParseBuffer()
        }
    }
}
