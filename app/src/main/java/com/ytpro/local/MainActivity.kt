package com.ytpro.local

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.net.URLEncoder

/** Ventana (WebView) a la interfaz que sirve el servidor interno (Backend.kt). */
class MainActivity : Activity() {
    private lateinit var wv: WebView

    inner class Bridge {
        @JavascriptInterface
        fun open(id: String) {
            val e = Backend.entry(id) ?: return
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.uri), e.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (x: Exception) { Toast.makeText(this@MainActivity, "No hay app para abrirlo", Toast.LENGTH_SHORT).show() }
            }
        }

        @JavascriptInterface
        fun share(id: String) {
            val e = Backend.entry(id) ?: return
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(e.uri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, "Compartir"))
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wv = WebView(this)
        setContentView(wv)
        wv.setBackgroundColor(0xFF05060F.toInt())
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = WebViewClient()
        wv.addJavascriptInterface(Bridge(), "Android")
        // El servidor sube al instante; el motor (yt-dlp/FFmpeg) se prepara en segundo plano mientras ya ves la interfaz.
        Thread {
            try {
                Backend.start(applicationContext)
                runOnUiThread { openApp(intent) }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "No se pudo iniciar: $e", Toast.LENGTH_LONG).show() }
            }
        }.start()
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
        openApp(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        wv.evaluateJavascript("(window.goBack&&window.goBack())?'1':'0'") { r ->
            if (r != "\"1\"" && r != "1") super.onBackPressed()
        }
    }
}
