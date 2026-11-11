package com.ancsbridge.watch.ble

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Contrato JSON versionado del canal puente reloj ↔ iOS.
 * Campos desconocidos y comandos desconocidos se ignoran (backward compatible).
 */
object BridgeProtocol {
    const val VERSION: Int = 2

    const val CMD_SET_NOTIF_MODE: String = "SET_NOTIF_MODE"
    const val CMD_SET_DND: String = "SET_DND"
    const val CMD_CAMERA_STATUS: String = "CAMERA_STATUS"
    const val EVT_CAMERA_OPEN: String = "CAMERA_OPEN"
    const val EVT_CAMERA_CAPTURE: String = "CAMERA_CAPTURE"
    const val EVT_FIND_PHONE: String = "FIND_PHONE"
    const val EVT_MEDIA_CMD: String = "MEDIA_CMD"

    const val MODE_KEY: String = "mode"
    const val LEGACY_MUTED_KEY: String = "muted"
    const val STATUS_KEY: String = "status"

    const val CAMERA_STATUS_READY: String = "ready"
    const val CAMERA_STATUS_NEED_FOREGROUND: String = "need_foreground"
    const val CAMERA_STATUS_DENIED: String = "denied"
    const val CAMERA_STATUS_CAPTURED: String = "captured"
    const val CAMERA_STATUS_ERROR: String = "error"
    const val CAMERA_STATUS_SAVING: String = "saving"

    fun encodeNotificationMode(mode: WatchNotificationMode): ByteArray {
        val json = JSONObject()
            .put("v", VERSION)
            .put(MODE_KEY, mode.wireValue)
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeNotificationMode(bytes: ByteArray?): WatchNotificationMode? {
        if (bytes == null || bytes.isEmpty()) return null
        return decodeNotificationMode(String(bytes, StandardCharsets.UTF_8))
    }

    fun decodeNotificationMode(raw: String): WatchNotificationMode? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Payload corto sin JSON (solo el valor wire).
        WatchNotificationMode.fromWire(trimmed)?.let { return it }
        return runCatching {
            val json = JSONObject(trimmed)
            WatchNotificationMode.fromWire(json.optString(MODE_KEY).ifBlank { null })
                ?: if (json.has(LEGACY_MUTED_KEY)) {
                    WatchNotificationMode.fromLegacyMutedFlag(json.optBoolean(LEGACY_MUTED_KEY))
                } else {
                    null
                }
        }.getOrNull()
    }

    fun decodeModeFromCommand(json: JSONObject): WatchNotificationMode? {
        WatchNotificationMode.fromWire(json.optString(MODE_KEY).ifBlank { null })?.let { return it }
        if (json.has(LEGACY_MUTED_KEY)) {
            return WatchNotificationMode.fromLegacyMutedFlag(json.optBoolean(LEGACY_MUTED_KEY))
        }
        return null
    }

    fun encodeSetNotifModeCommand(mode: WatchNotificationMode): String {
        return JSONObject()
            .put("v", VERSION)
            .put("cmd", CMD_SET_NOTIF_MODE)
            .put(MODE_KEY, mode.wireValue)
            .toString()
    }

    fun encodeCameraEvent(capture: Boolean): String {
        return JSONObject()
            .put("v", VERSION)
            .put("evt", if (capture) EVT_CAMERA_CAPTURE else EVT_CAMERA_OPEN)
            .toString()
    }

    fun encodeCameraStatus(status: String): String {
        return JSONObject()
            .put("v", VERSION)
            .put("cmd", CMD_CAMERA_STATUS)
            .put(STATUS_KEY, status)
            .toString()
    }

    fun decodeCameraStatus(json: JSONObject): String? {
        val status = json.optString(STATUS_KEY)
        return status.ifBlank { null }
    }
}
