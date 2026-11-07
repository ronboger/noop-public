package com.noop.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings tile: one tap opens NOOP on the Devices screen so the user can
 * reconnect / force a sync without hunting through More. Added (not active) by
 * default — the user must pin it from the QS edit panel.
 *
 * API 24+ (minSdk 26 covers this). No background work here: the tile is a deep-link
 * door, not a second BLE client.
 */
@RequiresApi(Build.VERSION_CODES.N)
class SyncNowTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(com.noop.R.string.tile_sync_label)
            contentDescription = getString(com.noop.R.string.tile_sync_content_description)
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // Guarded below for pre-Android 14 only.
    override fun onClick() {
        super.onClick()
        val launch = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_SHORTCUT, "devices")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
        // startActivityAndCollapse keeps the QS panel from fighting the activity.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }
}
