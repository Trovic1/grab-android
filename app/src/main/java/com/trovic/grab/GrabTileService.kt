package com.trovic.grab

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: copy a link anywhere, pull down the shade, tap the tile. */
open class GrabTileService : TileService() {

    open val mode = Mode.VIDEO
    open val labelRes = R.string.tile_label

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(labelRes)
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, GrabActivity::class.java)
            .setAction(GrabActivity.ACTION_CLIPBOARD)
            .putExtra(GrabActivity.EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val requestCode = if (mode == Mode.AUDIO) 2 else 1
            startActivityAndCollapse(
                PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** Second tile: same thing, but saves just the sound as an MP3. */
class GrabAudioTileService : GrabTileService() {
    override val mode = Mode.AUDIO
    override val labelRes = R.string.tile_audio_label
}
