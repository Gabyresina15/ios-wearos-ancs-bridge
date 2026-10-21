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
     * Solicita: AppIdentifier(0), Title(1, max 64B), Subtitle(2, max 64B), Message(3, max 256B).
     */
    fun buildGetNotificationAttributesCommand(notificationUid: Int): ByteArray {
        val buffer = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x00.toByte()) // CommandIDGetNotificationAttributes
        buffer.putInt(notificationUid)

        // Attribute 0: AppIdentifier (no lleva parámetro de longitud máxima)
        buffer.put(AncsAttributeId.APP_IDENTIFIER.code)

        // Attribute 1: Title (2 bytes Little Endian max length = 64)
        buffer.put(AncsAttributeId.TITLE.code)
        buffer.putShort(64.toShort())

        // Attribute 2: Subtitle (2 bytes Little Endian max length = 64)
        buffer.put(AncsAttributeId.SUBTITLE.code)
        buffer.putShort(64.toShort())

        // Attribute 3: Message (2 bytes Little Endian max length = 256)
        buffer.put(AncsAttributeId.MESSAGE.code)
        buffer.putShort(256.toShort())

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
 * Ensamblador de paquetes fragmentados del característica Data Source (22EAC6E9-...).
 * Verifica que los 4 atributos solicitados hayan llegado completos antes de emitir la notificación.
 */
class AncsDataSourceCollector(
    private val expectedAttributesCount: Int = 4,
    private val onNotificationParsed: (AncsNotification) -> Unit
) {
    private val accumulator = ByteArrayOutputStream()

    @Synchronized
    fun onDataChunkReceived(chunk: ByteArray) {
        accumulator.write(chunk)
        tryParseBuffer()
    }

    @Synchronized
    fun reset() {
        accumulator.reset()
    }

    private fun tryParseBuffer() {
        val rawBytes = accumulator.toByteArray()
        // Mínimo requerido: CommandID(1B) + NotificationUID(4B) = 5 bytes
        if (rawBytes.size < 5) return

        val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val commandId = buffer.get().toInt() and 0xFF
        if (commandId != 0x00) {
            // Si no es una respuesta de GetNotificationAttributes, limpiamos el buffer
            accumulator.reset()
            return
        }

        val uid = buffer.int
        val parsedAttrs = mutableMapOf<Byte, String>()

        for (i in 0 until expectedAttributesCount) {
            // Cada atributo tiene: AttributeID (1B) + Length (2B LE)
            if (buffer.remaining() < 3) {
                // Paquete incompleto: esperamos el siguiente callback de onCharacteristicChanged
                return
            }

            val attrId = buffer.get()
            val attrLen = buffer.short.toInt() and 0xFFFF

            if (buffer.remaining() < attrLen) {
                // El payload UTF-8 viene fragmentado en el próximo paquete BLE
                return
            }

            val payload = ByteArray(attrLen)
            buffer.get(payload)
            parsedAttrs[attrId] = String(payload, StandardCharsets.UTF_8).trim()
        }

        // Si llegamos aquí, hemos leído todos los atributos completos de la notificación actual
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

        onNotificationParsed(
            AncsNotification(
                uid = uid,
                appIdentifier = bundleId,
                appDisplayName = AppBundleResolver.resolveName(bundleId),
                title = title.ifEmpty { AppBundleResolver.resolveName(bundleId) },
                subtitle = subtitle,
                message = message
            )
        )

        // Si quedaron bytes remanentes de una segunda notificación en cola, procesarlos recursivamente
        if (remainingBytes.isNotEmpty()) {
            tryParseBuffer()
        }
    }
}
