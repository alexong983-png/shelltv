package com.example.shell

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val name: String, val url: String)

class MainActivity : AppCompatActivity() {

    private lateinit var mainContent: View
    private lateinit var webPage: View
    private lateinit var playerPage: View
    private lateinit var web: WebView
    private lateinit var fullscreen: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var playlistInput: EditText
    private lateinit var playerView: PlayerView
    private lateinit var channelList: ListView
    private lateinit var player: ExoPlayer

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private val channels = mutableListOf<Channel>()
    private val prefs by lazy { getSharedPreferences("shell", MODE_PRIVATE) }

    private val chrome = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customCallback = callback
            fullscreen.addView(view, ViewGroup.LayoutParams(-1, -1))
            fullscreen.visibility = View.VISIBLE
            mainContent.visibility = View.GONE
            setImmersive(true)
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            fullscreen.removeView(v)
            fullscreen.visibility = View.GONE
            mainContent.visibility = View.VISIBLE
            customCallback?.onCustomViewHidden()
            customView = null
            customCallback = null
            setImmersive(false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainContent = findViewById(R.id.mainContent)
        webPage = findViewById(R.id.webPage)
        playerPage = findViewById(R.id.playerPage)
        web = findViewById(R.id.webView)
        fullscreen = findViewById(R.id.fullscreenContainer)
        urlInput = findViewById(R.id.urlInput)
        playlistInput = findViewById(R.id.playlistInput)
        playerView = findViewById(R.id.playerView)
        channelList = findViewById(R.id.channelList)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        web.webChromeClient = chrome
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val scheme = r.url.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(v: WebView, url: String) {
                urlInput.setText(url)
            }
        }

        val goWeb = { openWeb(urlInput.text.toString()) }
        findViewById<Button>(R.id.goBtn).setOnClickListener { goWeb() }
        urlInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { goWeb(); true } else false
        }
        prefs.getString("web_url", "")?.takeIf { it.isNotEmpty() }?.let {
            urlInput.setText(it); openWeb(it)
        }

        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        val goLoad = { loadSource(playlistInput.text.toString()) }
        findViewById<Button>(R.id.loadBtn).setOnClickListener { goLoad() }
        playlistInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { goLoad(); true } else false
        }
        channelList.setOnItemClickListener { _, _, pos, _ -> play(channels[pos].url) }
        prefs.getString("playlist_url", "")?.takeIf { it.isNotEmpty() }?.let {
            playlistInput.setText(it)
        }

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener {
            showPage(it.itemId == R.id.nav_web)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chrome.onHideCustomView()
                    webPage.visibility == View.VISIBLE && web.canGoBack() -> web.goBack()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    private fun showPage(showWeb: Boolean) {
        webPage.visibility = if (showWeb) View.VISIBLE else View.GONE
        playerPage.visibility = if (showWeb) View.GONE else View.VISIBLE
        if (showWeb) { player.pause(); web.onResume() } else { web.onPause() }
    }

    private fun normalize(raw: String): String {
        val s = raw.trim()
        return if (s.contains("://")) s else "https://$s"
    }

    private fun openWeb(raw: String) {
        if (raw.isBlank()) return
        val url = normalize(raw)
        prefs.edit().putString("web_url", url).apply()
        web.loadUrl(url)
    }

    private fun loadSource(raw: String) {
        if (raw.isBlank()) return
        val url = normalize(raw)
        prefs.edit().putString("playlist_url", url).apply()
        val path = url.substringBefore('?').lowercase()
        if (listOf(".mp4", ".mkv", ".flv", ".ts", ".mp3", ".aac").any { path.endsWith(it) }) {
            setChannels(emptyList()); play(url); return
        }
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                runOnUiThread { handlePlaylist(url, text) }
            } catch (e: Exception) {
                runOnUiThread { toast("加载失败：${e.message}") }
            }
        }.start()
    }

    private fun handlePlaylist(base: String, text: String) {
        if (text.contains("#EXT-X-")) {
            setChannels(emptyList()); play(base); return
        }
        val list = mutableListOf<Channel>()
        var name: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(',', "").trim()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val abs = try { URL(URL(base), line).toString() } catch (e: Exception) { line }
                list.add(Channel(if (name.isNullOrEmpty()) line else name, abs))
                name = null
            }
        }
        if (list.isEmpty()) { toast("没有解析到频道"); return }
        setChannels(list)
        play(list[0].url)
    }

    private fun setChannels(list: List<Channel>) {
        channels.clear()
        channels.addAll(list)
        channelList.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, channels.map { it.name }
        )
    }

    private fun play(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    private fun setImmersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        web.destroy()
    }
}
