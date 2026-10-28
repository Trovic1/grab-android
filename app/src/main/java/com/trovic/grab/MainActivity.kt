package com.trovic.grab

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var clipCard: MaterialCardView
    private lateinit var clipText: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var cookiesBtn: Button
    private lateinit var versionText: TextView
    private var workInfos: Map<String, WorkInfo> = emptyMap()
    private var pendingClip: String? = null

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val pickCookies = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                Downloader.cookiesFile(this).outputStream().use { input.copyTo(it) }
            }
        }.onSuccess { toast("Cookies imported") }.onFailure { toast("Couldn't read that file") }
        refreshCookiesButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        clipCard = findViewById(R.id.clipCard)
        clipText = findViewById(R.id.clipText)
        historyList = findViewById(R.id.historyList)
        emptyText = findViewById(R.id.emptyText)
        cookiesBtn = findViewById(R.id.cookiesBtn)
        versionText = findViewById(R.id.versionText)

        findViewById<Button>(R.id.downloadBtn).setOnClickListener { downloadTyped() }
        urlInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { downloadTyped(); true } else false
        }
        findViewById<Button>(R.id.pasteBtn).setOnClickListener {
            Links.readClipboard(this)?.let { urlInput.setText(Links.find(it) ?: it) }
        }
        findViewById<Button>(R.id.clipDownload).setOnClickListener {
            pendingClip?.let { start(it) }
            clipCard.visibility = View.GONE
        }

        val auto = findViewById<MaterialSwitch>(R.id.autoSwitch)
        auto.isChecked = Prefs.autoGrab(this)
        auto.setOnCheckedChangeListener { _, on -> Prefs.setAutoGrab(this, on) }

        findViewById<Button>(R.id.updateBtn).setOnClickListener { btn ->
            btn.isEnabled = false
            toast("Updating… this takes a few seconds")
            lifecycleScope.launch {
                val msg = runCatching { Downloader.update(this@MainActivity) }
                    .getOrElse { "Update failed: ${it.message?.take(120)}" }
                toast(msg)
                btn.isEnabled = true
                showVersion()
            }
        }
        cookiesBtn.setOnClickListener {
            val f = Downloader.cookiesFile(this)
            if (f.exists()) { f.delete(); toast("Cookies removed"); refreshCookiesButton() }
            else pickCookies.launch(arrayOf("text/plain", "*/*"))
        }
        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            History.clear(this); render()
        }

        WorkManager.getInstance(this).getWorkInfosByTagLiveData(DownloadWorker.TAG).observe(this) { infos ->
            workInfos = infos.associateBy { it.id.toString() }
            render()
        }

        askForPermissions()
        refreshCookiesButton()
        showVersion()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Android only lets apps read the clipboard while they're on screen, so check the moment we get focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val url = Links.find(Links.readClipboard(this)) ?: return
        if (url == Prefs.lastHandled(this)) return
        if (Prefs.autoGrab(this)) {
            start(url)
            Snackbar.make(findViewById(R.id.root), "Downloading copied link", Snackbar.LENGTH_SHORT).show()
        } else {
            pendingClip = url
            clipText.text = Links.short(url)
            clipCard.visibility = View.VISIBLE
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            Links.find(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { start(it) }
        }
    }

    private fun downloadTyped() {
        val url = Links.find(urlInput.text)
        if (url == null) { toast("That doesn't look like a link"); return }
        start(url)
        urlInput.setText("")
    }

    private fun start(url: String) {
        Links.enqueue(this, url)
        clipCard.visibility = View.GONE
        render()
    }

    private fun render() {
        val items = History.all(this)
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        historyList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (e in items) {
            val row = inflater.inflate(R.layout.item_history, historyList, false)
            val info = workInfos[e.id]
            val bar = row.findViewById<LinearProgressIndicator>(R.id.progress)
            row.findViewById<TextView>(R.id.title).text = Links.short(e.url)

            // If the app was killed mid-download, WorkManager knows the real state.
            val state = when {
                e.state in setOf("queued", "running") && info?.state == WorkInfo.State.FAILED -> "failed"
                e.state in setOf("queued", "running") && info?.state == WorkInfo.State.CANCELLED -> "failed"
                else -> e.state
            }
            val pct = info?.progress?.getInt(DownloadWorker.KEY_PROGRESS, -1) ?: -1
            val status = when (state) {
                "running" -> if (pct >= 0) "Downloading $pct%" else "Downloading…"
                "queued" -> "Waiting for network…"
                "done" -> "✓ ${e.message}"
                else -> "✕ ${e.message}"
            }
            row.findViewById<TextView>(R.id.status).text =
                status + " · " + DateUtils.getRelativeTimeSpanString(e.time)

            if (state == "running" || state == "queued") {
                bar.visibility = View.VISIBLE
                bar.isIndeterminate = pct < 0
                if (pct >= 0) bar.setProgressCompat(pct, true)
            }
            row.setOnClickListener { openEntry(e, state) }
            historyList.addView(row)
        }
    }

    private fun openEntry(e: History.Entry, state: String) {
        val uri = e.uri
        if (state == "done" && uri != null && uri.startsWith("content")) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), e.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                return
            } catch (_: ActivityNotFoundException) { }
        }
        if (state == "failed") {
            start(e.url)
            toast("Retrying")
        }
    }

    private fun askForPermissions() {
        val perm = when {
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.POST_NOTIFICATIONS
            Build.VERSION.SDK_INT <= 28 -> Manifest.permission.WRITE_EXTERNAL_STORAGE
            else -> null
        } ?: return
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            askPermission.launch(perm)
        }
    }

    private fun refreshCookiesButton() {
        cookiesBtn.text = if (Downloader.cookiesFile(this).exists()) "Remove imported cookies"
        else "Import cookies.txt (for private / login-only posts)"
    }

    private fun showVersion() {
        lifecycleScope.launch {
            val v = Downloader.version(this@MainActivity)
            val app = packageManager.getPackageInfo(packageName, 0).versionName
            versionText.text = "Grab $app · engine yt-dlp ${v ?: "(not loaded yet)"}"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
