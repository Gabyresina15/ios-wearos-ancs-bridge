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

/**
 * Tile nativo de Wear OS para visualizar de un vistazo el estado del enlace con el iPhone/Android,
 * batería del teléfono, pasos del día y frecuencia cardíaca.
 */
class BridgeStatusTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
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

        val phoneName = blePrefs.getString("last_iphone_real_name", "Teléfono") ?: "Teléfono"
        val connLabel = blePrefs.getString("last_conn_state_label", "Conectado") ?: "Conectado"
        val phoneBatt = blePrefs.getInt("last_iphone_battery", -1)
        val steps = telPrefs.getLong("last_steps", 0L)
        val hrBpm = telPrefs.getLong("last_hr_bpm", 0L)

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

        val batteryText = if (phoneBatt in 0..100) "$connLabel • $phoneBatt%" else connLabel
        val hrText = if (hrBpm > 0) "$hrBpm BPM" else "-- BPM"

        val rootLayout = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF0A0A0C.toInt()))
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
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(batteryText)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(12f))
                                    .setColor(argb(0xFF34D399.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("$steps pasos  •  $hrText")
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(13f))
                                    .setColor(argb(0xFF38BDF8.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("Tocar para abrir controles")
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(10f))
                                    .setColor(argb(0xFF71717A.toInt()))
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
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(rootLayout)
            )
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
