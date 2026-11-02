package com.ytpro.local

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Cliente de actualizaciones: consulta tu "servidor de mantenimiento" (la web local, ytdl_pro.py).
 * REGLA DE ORO: si el servidor esta apagado o no responde, la app funciona normal (nunca se bloquea por eso).
 */
object Updater {
    @Volatile private var cache: JSONObject? = null
    @Volatile private var cacheAt = 0L
    @Volatile private var man: JSONObject? = null
    @Volatile var state = "idle"   // idle | downloading | verifying | installing | error | need_permission
    @Volatile var pct = 0.0
    @Volatile var error = ""

    fun reset() { cache = null; cacheAt = 0L }
    fun status(): JSONObject = JSONObject().put("state", state).put("pct", pct).put("error", error)
    fun finish(err: String) { if (err.isEmpty()) { state = "idle" } else { state = "error"; error = err } }

    private fun base(): String = Backend.pref().getString("updUrl", "http://127.0.0.1:5001")!!.trimEnd('/')

    private fun get(url: String, ms: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = ms
        c.readTimeout = ms
        try {
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun check(force: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        val cached = cache
        if (!force && cached != null && now - cacheAt < 20_000L) return JSONObject(cached.toString()).put("dl", status())
        val ctx = Backend.appCtx
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = pi.longVersionCode
        val out = JSONObject().put("installed", JSONObject().put("code", code).put("name", pi.versionName ?: ""))
        try {
            val m = JSONObject(get(base() + "/api/apk/manifest", 2500))
            man = m
            val rc = m.optLong("versionCode", 0L)
            val update = rc > code && m.optString("apkUrl").isNotEmpty()
            out.put("reachable", true).put("update", update).put("paused", m.optBoolean("paused", false))
                .put("force", m.optBoolean("force", false) && update).put("message", m.optString("message"))
                .put("remote", JSONObject().put("code", rc).put("name", m.optString("versionName", rc.toString()))
                    .put("notes", m.optString("notes")))
        } catch (e: Exception) {
            // Servidor apagado / sin red: se ignora por completo.
            out.put("reachable", false).put("update", false).put("paused", false).put("force", false)
        }
        cache = out
        cacheAt = now
        return JSONObject(out.toString()).put("dl", status())
    }

    fun start(): JSONObject {
        if (state == "downloading" || state == "verifying" || state == "installing") return status()
        if (!Backend.appCtx.packageManager.canRequestPackageInstalls()) { state = "need_permission"; return status() }
        val m = man
        if (m == null || m.optString("apkUrl").isEmpty()) { state = "error"; error = "Primero busca la actualizacion"; return status() }
        state = "downloading"; pct = 0.0; error = ""
        Thread {
            try { fetchAndInstall(m) } catch (e: Exception) { state = "error"; error = (e.message ?: e.toString()).take(160) }
        }.start()
        return status()
    }

    private fun fetchAndInstall(m: JSONObject) {
        val ref = m.optString("apkUrl")
        val u = if (ref.startsWith("http")) ref else base() + ref
        val f = File(Backend.appCtx.cacheDir, "update.apk")
        f.delete()
        val md = MessageDigest.getInstance("SHA-256")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        try {
            if (c.responseCode != 200) throw Exception("El servidor respondio ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else m.optLong("size", 0L)
            c.inputStream.use { i ->
                f.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var n = 0
                    var done = 0L
                    while (i.read(buf).also { n = it } > 0) {
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) pct = done * 100.0 / total
                    }
                }
            }
        } finally { c.disconnect() }
        state = "verifying"
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        val want = m.optString("sha256")
        if (want.isNotEmpty() && !want.equals(sha, true)) { f.delete(); throw Exception("El archivo no coincide con el publicado (sha256)") }
        install(f)
    }

    private fun install(f: File) {
        val ctx = Backend.appCtx
        state = "installing"
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            f.inputStream().use { i -> s.openWrite("ytpro.apk", 0, f.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val intent = Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName)
            val pend = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pend.intentSender)
        }
    }
}
