package com.ytpro.local

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.net.URLEncoder

/** Ventana (WebView) a la web que sirve el servidor interno (Backend.kt). */
class MainActivity : Activity() {
    private lateinit var wv: WebView
    private var ready = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wv = WebView(this)
        setContentView(wv)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = WebViewClient()
        wv.setDownloadListener { url, _, _, _, _ -> save(url) }
        msg("Preparando el motor…", "La primera vez tarda un poco: descomprime yt-dlp y FFmpeg.")
        Thread {
            try {
                Backend.start(applicationContext)
                runOnUiThread { ready = true; openApp(intent) }
            } catch (e: Throwable) {
                runOnUiThread { msg("No se pudo iniciar", e.toString()) }
            }
        }.start()
    }

    private fun msg(t: String, d: String) {
        val html = "<body style='background:#101216;color:#eee;font-family:sans-serif;padding:24px'><h3>$t</h3><p>$d</p>"
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun openApp(i: Intent?) {
        var q = ""
        if (i != null && i.action == Intent.ACTION_SEND) {
            val m = Regex("https?://\\S+").find(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            if (m != null) q = "?u=" + URLEncoder.encode(m.value, "UTF-8")
        }
        wv.loadUrl("http://127.0.0.1:${Backend.PORT}/$q")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ready) openApp(intent)
    }

    private fun save(url: String) {
        val name = Uri.parse(url).lastPathSegment ?: return
        Thread {
            val ok = Backend.saveToDownloads(File(Backend.dir, name))
            runOnUiThread {
                Toast.makeText(this, if (ok) "Guardado en Descargas/YTPro" else "No se pudo guardar", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }
}
