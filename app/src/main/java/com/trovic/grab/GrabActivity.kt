package com.trovic.grab

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible screen that flashes for a split second:
 *  - Share → Grab from any app
 *  - "Grab" in the text-selection menu
 *  - the Quick Settings tile (reads the clipboard; Android only allows that while an app is on screen)
 */
class GrabActivity : AppCompatActivity() {

    companion object {
        const val ACTION_CLIPBOARD = "com.trovic.grab.FROM_CLIPBOARD"
        const val EXTRA_MODE = "com.trovic.grab.MODE"
    }

    private var handled = false

    /** "Grab MP3" in the share menu is an alias of this screen; the audio tile passes EXTRA_MODE. */
    private val mode: String
        get() = if (intent?.getStringExtra(EXTRA_MODE) == Mode.AUDIO ||
            intent?.component?.className?.endsWith(".GrabAudio") == true
        ) Mode.AUDIO else Mode.VIDEO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != ACTION_CLIPBOARD) handle(sharedText(intent))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled && intent?.action == ACTION_CLIPBOARD) handle(Links.readClipboard(this))
    }

    private fun sharedText(i: Intent?): CharSequence? = when (i?.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            i.getCharSequenceExtra(Intent.EXTRA_TEXT),
            i.getStringExtra(Intent.EXTRA_SUBJECT),
        ).joinToString(" ")
        Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        else -> null
    }

    private fun handle(text: CharSequence?) {
        handled = true
        val url = Links.find(text)
        if (url == null) {
            Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show()
        } else {
            val m = mode
            Links.enqueue(this, url, m)
            val what = if (m == Mode.AUDIO) "Grabbing MP3 from" else "Grabbing"
            Toast.makeText(this, "$what ${Links.short(url)}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
