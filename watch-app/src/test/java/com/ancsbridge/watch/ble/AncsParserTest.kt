package com.ancsbridge.watch.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class AncsParserTest {

    @Test
    fun parseNotificationSource_rejectsShortPayload() {
        assertNull(AncsProtocol.parseNotificationSource(ByteArray(7)))
    }

    @Test
    fun parseNotificationSource_readsLittleEndianUidAndFlags() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x00) // ADDED
            put(0x15) // silent(0x01) + preexisting(0x04) + negative(0x10)
            put(0x01) // incoming call category
            put(0x03) // category count
            putInt(0x01020304)
        }.array()

        val header = AncsProtocol.parseNotificationSource(bytes)!!
        assertEquals(AncsEventId.ADDED, header.eventId)
        assertTrue(header.isSilent)
        assertFalse(header.isImportant)
        assertTrue(header.isPreExisting)
        assertFalse(header.hasPositiveAction)
        assertTrue(header.hasNegativeAction)
        assertTrue(header.isIncomingCall)
        assertEquals(3, header.categoryCount)
        assertEquals(0x01020304, header.notificationUid)
    }

    @Test
    fun fromByte_returnsNullForUnknownEventId() {
        assertNull(AncsEventId.fromByte(0x7F))
        assertNull(AncsProtocol.parseNotificationSource(byteArrayOf(0x7F, 0, 1, 1, 1, 0, 0, 0)))
        assertEquals(AncsEventId.REMOVED, AncsEventId.fromByte(0x02))
    }

    @Test
    fun buildGetNotificationAttributesCommand_is17BytesLittleEndian() {
        val cmd = AncsProtocol.buildGetNotificationAttributesCommand(0x11223344)
        assertEquals(17, cmd.size)
        assertEquals(0x00, cmd[0].toInt())
        val uid = ByteBuffer.wrap(cmd, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0x11223344, uid)
    }

    @Test
    fun buildPerformNotificationActionCommand_encodesUidAndAction() {
        val cmd = AncsProtocol.buildPerformNotificationActionCommand(42, AncsActionId.NEGATIVE)
        assertEquals(6, cmd.size)
        assertEquals(0x02, cmd[0].toInt())
        assertEquals(42, ByteBuffer.wrap(cmd, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(1, cmd[5].toInt())
    }

    @Test
    fun collector_parsesCompleteNotificationInOneChunk() {
        val parsed = mutableListOf<AncsNotification>()
        val collector = AncsDataSourceCollector(onNotificationParsed = { parsed += it })
        collector.onDataChunkReceived(
            notificationAttributesPayload(
                uid = 99,
                app = "com.apple.MobileSMS",
                title = "Ana",
                subtitle = "",
                message = "Hola",
                positive = "Responder",
                negative = "Cerrar"
            )
        )
        assertEquals(1, parsed.size)
        assertEquals(99, parsed[0].uid)
        assertEquals("com.apple.MobileSMS", parsed[0].appIdentifier)
        assertEquals("Mensajes", parsed[0].appDisplayName)
        assertEquals("Ana", parsed[0].title)
        assertEquals("Hola", parsed[0].message)
        assertEquals("Responder", parsed[0].positiveActionLabel)
        assertEquals("Cerrar", parsed[0].negativeActionLabel)
    }

    @Test
    fun collector_reassemblesFragmentedNotification() {
        val parsed = mutableListOf<AncsNotification>()
        val collector = AncsDataSourceCollector(onNotificationParsed = { parsed += it })
        val full = notificationAttributesPayload(
            uid = 7,
            app = "net.whatsapp.WhatsApp",
            title = "Título largo con tildes áéí",
            subtitle = "sub",
            message = "mensaje " + "x".repeat(200),
            positive = "",
            negative = "Descartar"
        )
        val mid = full.size / 2
        collector.onDataChunkReceived(full.copyOfRange(0, mid))
        assertTrue(parsed.isEmpty())
        collector.onDataChunkReceived(full.copyOfRange(mid, full.size))
        assertEquals(1, parsed.size)
        assertEquals(7, parsed[0].uid)
        assertEquals("WhatsApp", parsed[0].appDisplayName)
        assertTrue(parsed[0].title.contains("áéí"))
        assertEquals(200, parsed[0].message.removePrefix("mensaje ").length)
    }

    @Test
    fun collector_parsesTwoBackToBackNotificationsInSameChunk() {
        val parsed = mutableListOf<AncsNotification>()
        val collector = AncsDataSourceCollector(onNotificationParsed = { parsed += it })
        val first = notificationAttributesPayload(1, "com.apple.mobilemail", "A", "", "uno", "", "")
        val second = notificationAttributesPayload(2, "com.apple.mobilemail", "B", "", "dos", "", "")
        collector.onDataChunkReceived(first + second)
        assertEquals(listOf(1, 2), parsed.map { it.uid })
        assertEquals("uno", parsed[0].message)
        assertEquals("dos", parsed[1].message)
    }

    @Test
    fun collector_parsesGetAppAttributes() {
        var seen: Pair<String, String>? = null
        val collector = AncsDataSourceCollector(
            onAppAttributeParsed = { id, name -> seen = id to name },
            onNotificationParsed = { fail("no debería parsear una notificación") }
        )
        collector.onDataChunkReceived(appAttributesPayload("ph.telegra.Telegraph", "Telegram"))
        assertEquals("ph.telegra.Telegraph" to "Telegram", seen)
    }

    @Test
    fun collector_recoversFromCorruptAttrLengthAndParsesLaterValidNotification() {
        val parsed = mutableListOf<AncsNotification>()
        val collector = AncsDataSourceCollector(onNotificationParsed = { parsed += it })

        // Encabezado 0x00 + uid + attrId + attrLen=10000, sin payload.
        // Un collector robusto debe descartar ese marco y seguir con el siguiente chunk válido.
        val corrupt = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x00)
            putInt(1)
            put(0x01) // title
            putShort(10_000.toShort())
        }.array()
        collector.onDataChunkReceived(corrupt)

        val valid = notificationAttributesPayload(2, "com.apple.MobileSMS", "Ana", "", "hola", "", "")
        collector.onDataChunkReceived(valid)

        assertEquals(listOf(2), parsed.map { it.uid })
        assertEquals("hola", parsed[0].message)
    }

    @Test
    fun collector_timeoutResetsStaleBufferAfter2500ms() {
        val parsed = mutableListOf<AncsNotification>()
        val collector = AncsDataSourceCollector(onNotificationParsed = { parsed += it })
        val corrupt = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x00)
            putInt(1)
            put(0x01)
            putShort(10_000.toShort())
        }.array()
        collector.onDataChunkReceived(corrupt)
        Thread.sleep(2600L)
        collector.onDataChunkReceived(
            notificationAttributesPayload(3, "com.apple.MobileSMS", "Ana", "", "después", "", "")
        )
        assertEquals(listOf(3), parsed.map { it.uid })
    }

    @Test
    fun pendingIntentRequestCode_doesNotOverflowForLargeAncsUids() {
        val uid = 300_000_000
        val codes = (0..5).map { offset -> AncsProtocol.computeNotificationRequestCode(uid, offset) }
        assertEquals(6, codes.toSet().size)
        assertTrue(codes.all { it >= 0 })
    }

    @Test
    fun ctsTimeParser_readsCurrentTimeAndFraction() {
        val bytes = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(2026.toShort())
            put(11.toByte()) // month
            put(10.toByte()) // day
            put(19.toByte()) // hours
            put(30.toByte()) // minutes
            put(45.toByte()) // seconds
            put(2.toByte()) // day of week
            put(128.toByte()) // 128/256 s
        }.array()
        val parsed = CtsTimeParser.parseCurrentTime(bytes)
        assertNotNull(parsed)
        assertEquals("10/11 19:30:45", parsed!!.formattedTime)
    }

    @Test
    fun ctsTimeParser_rejectsShortOrInvalid() {
        assertNull(CtsTimeParser.parseCurrentTime(ByteArray(6)))
        val badMonth = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(2026.toShort())
            put(0.toByte()) // invalid month
            put(1.toByte()); put(0.toByte()); put(0.toByte()); put(0.toByte())
        }.array()
        assertNull(CtsTimeParser.parseCurrentTime(badMonth))
    }

    @Test
    fun amsEntityParser_updatesPlaybackVolumeAndTrack() {
        var state = NowPlayingState()
        state = AmsEntityParser.applyEntityUpdate(state, amsUpdate(entity = 0, attr = 1, "1,0.25"))
        assertTrue(state.isPlaying)
        state = AmsEntityParser.applyEntityUpdate(state, amsUpdate(entity = 0, attr = 2, "0.5"))
        assertEquals(50, state.volumePercent)
        state = AmsEntityParser.applyEntityUpdate(state, amsUpdate(entity = 2, attr = 2, "Bohemian Rhapsody"))
        assertEquals("Bohemian Rhapsody", state.title)
        state = AmsEntityParser.applyEntityUpdate(state, amsUpdate(entity = 2, attr = 0, "Queen"))
        assertEquals("Queen", state.artist)
    }

    @Test
    fun amsEntityParser_detectsTruncationFlagAndAppliesFullEntityAttribute() {
        val state = AmsEntityParser.applyEntityUpdate(
            NowPlayingState(),
            amsUpdate(entity = 2, attr = 2, "Titulo cortado", flags = 0x01)
        )
        assertEquals("Titulo cortado", state.title)
        assertTrue(state.isTruncated)

        val cmd = AmsEntityParser.buildEntityAttributeCommand(2, 2)
        assertEquals(2, cmd.size)
        assertEquals(2, cmd[0].toInt())
        assertEquals(2, cmd[1].toInt())

        val fullState = AmsEntityParser.applyFullEntityAttribute(
            state,
            entityId = 2,
            attrId = 2,
            rawValue = "Título completo sin truncamiento desde EntityAttribute".toByteArray(StandardCharsets.UTF_8)
        )
        assertEquals("Título completo sin truncamiento desde EntityAttribute", fullState.title)
        assertFalse(fullState.isTruncated)
    }

    @Test
    fun appleDeviceModelResolver_mapsKnownAndUnknown() {
        assertEquals("iPhone 16 Pro Max", AppleDeviceModelResolver.resolveMarketingName("iPhone17,2"))
        assertEquals("iPhone99,1", AppleDeviceModelResolver.resolveMarketingName("iPhone99,1"))
        assertEquals(
            "iPhone de Gaby",
            AppleDeviceModelResolver.formatDisplayIdentity("iPhone de Gaby", "iPhone17,2")
        )
        assertEquals(
            "iPhone 16 Pro Max",
            AppleDeviceModelResolver.formatDisplayIdentity("iPhone", "iPhone17,2")
        )
    }

    private fun notificationAttributesPayload(
        uid: Int,
        app: String,
        title: String,
        subtitle: String,
        message: String,
        positive: String,
        negative: String
    ): ByteArray {
        val attrs = listOf(
            AncsAttributeId.APP_IDENTIFIER.code to app,
            AncsAttributeId.TITLE.code to title,
            AncsAttributeId.SUBTITLE.code to subtitle,
            AncsAttributeId.MESSAGE.code to message,
            AncsAttributeId.POSITIVE_ACTION_LABEL.code to positive,
            AncsAttributeId.NEGATIVE_ACTION_LABEL.code to negative
        )
        val encoded = attrs.map { encodeAttr(it.first, it.second) }
        val size = 5 + encoded.sumOf { it.size }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x00)
        buffer.putInt(uid)
        encoded.forEach { buffer.put(it) }
        return buffer.array()
    }

    private fun encodeAttr(id: Byte, value: String): ByteArray {
        val payload = value.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(3 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id)
            putShort(payload.size.toShort())
            put(payload)
        }.array()
    }

    private fun appAttributesPayload(bundleId: String, displayName: String): ByteArray {
        val idBytes = bundleId.toByteArray(StandardCharsets.UTF_8)
        val nameBytes = displayName.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(1 + idBytes.size + 1 + 1 + 2 + nameBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(0x01)
                put(idBytes)
                put(0x00)
                put(0x00) // DisplayName
                putShort(nameBytes.size.toShort())
                put(nameBytes)
            }.array()
    }

    private fun amsUpdate(entity: Int, attr: Int, text: String, flags: Int = 0): ByteArray {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        return byteArrayOf(entity.toByte(), attr.toByte(), flags.toByte()) + textBytes
    }
}
