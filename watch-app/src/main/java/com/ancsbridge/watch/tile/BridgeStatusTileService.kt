package com.ancsbridge.watch.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.ancsbridge.watch.ui.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

class BridgeStatusTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "2"
        private const val PREFS_BLE = "ancs_bridge_prefs"
        private const val PREFS_TELEMETRY = "ancs_telemetry_cache"

        fun requestUpdate(context: Context) {
            runCatching {
                getUpdater(context).requestUpdate(BridgeStatusTileService::class.java)
            }
        }
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val blePrefs = getSharedPreferences(PREFS_BLE, Context.MODE_PRIVATE)
        val telPrefs = getSharedPreferences(PREFS_TELEMETRY, Context.MODE_PRIVATE)

        val phoneName = blePrefs.getString("last_iphone_real_name", "iPhone") ?: "iPhone"
        val connLabel = blePrefs.getString("last_conn_state_label", "Conectado") ?: "Conectado"
        val phoneBatt = blePrefs.getInt("last_iphone_battery", -1)
        val isMuted = blePrefs.getBoolean("watch_notifications_muted", false)

        val steps = telPrefs.getLong("last_steps", 0L)
        val hrBpm = telPrefs.getLong("last_hr_bpm", 0L)
        val weightKg = telPrefs.getFloat("last_weight_kg", -1f)
        val fatPct = telPrefs.getFloat("last_body_fat_pct", -1f)

        val openAppAction = ModifiersBuilders.Clickable.Builder()
            .setId("open_bridge_app")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val batteryText = if (phoneBatt in 0..100) "$connLabel • 🔋 $phoneBatt%" else connLabel
        val hrText = if (hrBpm > 0) "❤️ $hrBpm BPM" else "❤️ --"
        val femmtoLine = buildString {
            if (weightKg > 0f) append(String.format(Locale.US, "⚖️ %.1f kg", weightKg))
            if (fatPct > 0f) {
                if (isNotEmpty()) append(" • ")
                append(String.format(Locale.US, "%.1f%% grasa", fatPct))
            }
        }.ifEmpty { if (isMuted) "🔕 Notificaciones pausadas" else "🔔 Notificaciones activas" }

        val rootLayout = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF0A0A0E.toInt()))
                            .build()
                    )
                    .setClickable(openAppAction)
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(wrap())
                    .setHeight(wrap())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(phoneName)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(15f))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                    .setColor(argb(0xFFFFFFFF.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(batteryText)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(11f))
                                    .setColor(argb(0xFF10B981.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("👟 $steps pasos  •  $hrText")
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(12f))
                                    .setColor(argb(0xFF06B6D4.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(femmtoLine)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(11f))
                                    .setColor(argb(if (isMuted) 0xFFF87171.toInt() else 0xFFA1A1AA.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(rootLayout))
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }
}
