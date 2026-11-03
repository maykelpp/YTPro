package com.ytpro.local

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class Entry(
    val id: String, val name: String, val uri: String, val kind: String,
    val size: Long, val mime: String, val date: Long,
    @Volatile var lyrics: String = "",
    @Volatile var artist: String = "",
    @Volatile var track: String = "",
    @Volatile var duration: Double = 0.0
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("size", size).put("date", date)
        .put("hasLyrics", lyrics.isNotEmpty()).put("artist", artist).put("track", track).put("duration", duration)
}

class Job(val id: String, val url: String, var title: String, val opts: JSONObject) {
    @Volatile var status = "queued"
    @Volatile var step = "En cola"
    @Volatile var pct = 0.0
    @Volatile var speed = ""
    @Volatile var eta = ""
    @Volatile var error = ""
    @Volatile var files: List<Entry> = emptyList()
    @Volatile var cancel = false
    val created = System.currentTimeMillis()

    fun json(): JSONObject = JSONObject().put("id", id).put("url", url).put("title", title)
        .put("status", status).put("step", step).put("pct", pct).put("speed", speed).put("eta", eta)
        .put("error", error).put("files", JSONArray(files.map { it.json() })).put("opts", opts)
        .put("created", created / 1000.0)
}

/** Servidor interno: yt-dlp + FFmpeg empaquetados en la APK, expuestos como API local para la interfaz. */
object Backend {
    const val PORT = 8731
    lateinit var appCtx: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var tmpDir: File
    private var server: Srv? = null
    @Volatile private var ready = false
    @Volatile private var updating = false
    @Volatile private var engineError = ""
    @Volatile private var updateMsg = ""
    @Volatile private var verCache = ""
    @Volatile private var ariaOk = false
    private val jobs = ConcurrentHashMap<String, Job>()
    private val index = CopyOnWriteArrayList<Entry>()
    private val pool = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val CLIENTS: List<String?> = listOf(null, "android_vr", "tv", "web_safari", "mweb", "ios")
    private val AV = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "flac", "wav", "ogg", "aac")

    // ------------------------------------------------------------ arranque (el servidor sube al instante; el motor, en segundo plano)
    fun pref(): SharedPreferences = prefs

    @Synchronized
    fun start(ctx: Context) {
        if (server != null) return
        appCtx = ctx.applicationContext
        prefs = appCtx.getSharedPreferences("ytpro", Context.MODE_PRIVATE)
        tmpDir = File(appCtx.getExternalFilesDir(null) ?: appCtx.filesDir, "tmp").also { it.mkdirs() }
        pool.corePoolSize = prefs.getInt("parallel", 2).coerceIn(1, 4)
        loadIndex()
        server = Srv().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        Thread { initEngine() }.start()
    }

    private fun initEngine() {
        try {
            YoutubeDL.getInstance().init(appCtx)
            FFmpeg.getInstance().init(appCtx)
            try { Aria2c.getInstance().init(appCtx); ariaOk = true } catch (_: Throwable) {}
            ready = true
            if (prefs.getBoolean("autoUpdate", true) &&
                System.currentTimeMillis() - prefs.getLong("lastUpdate", 0L) > 86_400_000L) runUpdate()
        } catch (e: Throwable) {
            engineError = e.toString().take(200)
        }
    }

    private fun awaitEngine() {
        val t0 = System.currentTimeMillis()
        while ((!ready || updating) && engineError.isEmpty() && System.currentTimeMillis() - t0 < 180_000L) Thread.sleep(200)
        if (engineError.isNotEmpty()) throw Exception("Motor: $engineError")
        if (!ready) throw Exception("El motor sigue iniciando, espera unos segundos")
    }

    private fun ytdlVersion(): String {
        if (verCache.isNotEmpty()) return verCache
        return try {
            val y = YoutubeDL.getInstance()
            val m = y.javaClass.methods.firstOrNull { it.name == "version" }
            val v = if (m == null) null else m.invoke(y, *(if (m.parameterCount == 1) arrayOf<Any?>(appCtx) else arrayOf<Any?>()))
            (v as? String ?: "?").also { verCache = it }
        } catch (e: Throwable) { "?" }
    }

    /** Actualiza yt-dlp (canal nightly) por reflexion, para no depender del nombre exacto de la API. */
    private fun runUpdate() {
        updating = true
        try {
            val y = YoutubeDL.getInstance()
            val chanCls = Class.forName("com.yausername.youtubedl_android.YoutubeDL\$UpdateChannel")
            val chan: Any? = listOf("_NIGHTLY", "NIGHTLY").firstNotNullOfOrNull { n ->
                try { chanCls.getField(n).get(null) } catch (e: Throwable) { null }
            } ?: Class.forName("com.yausername.youtubedl_android.YoutubeDL\$UpdateChannel\$NIGHTLY").getField("INSTANCE").get(null)
            val m = y.javaClass.methods.first { it.name == "updateYoutubeDL" }
            val r = m.invoke(y, appCtx, chan)
            prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
            verCache = ""
            updateMsg = "yt-dlp actualizado ($r)"
        } catch (e: Throwable) {
            updateMsg = "No se pudo actualizar: " + (e.cause?.message ?: e.message ?: e.toString()).take(120)
        } finally { updating = false }
    }

    private class Srv : NanoHTTPD("127.0.0.1", PORT) {
        override fun serve(session: IHTTPSession): Response =
            try { route(session) } catch (e: Exception) { text(Response.Status.INTERNAL_ERROR, e.message ?: "error") }
    }

    // ------------------------------------------------------------ utilidades
    private fun JSONObject.str(k: String): String {
        val v = opt(k)
        return if (v == null || v == JSONObject.NULL) "" else v.toString()
    }

    private fun text(st: Response.IStatus, s: String): Response =
        NanoHTTPD.newFixedLengthResponse(st, "text/plain; charset=utf-8", s)

    private fun json(o: Any): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun fmtDur(d: Double): String {
        val n = d.toInt()
        return if (n >= 3600) "%d:%02d:%02d".format(n / 3600, n / 60 % 60, n % 60) else "%d:%02d".format(n / 60, n % 60)
    }

    private fun secs(t: String): Double? {
        val p = t.trim().split(":").map { it.toDoubleOrNull() ?: return null }
        return p.fold(0.0) { a, x -> a * 60 + x }
    }

    private fun kindOf(ext: String) = when (ext) {
        "mp4", "mkv", "webm" -> "video"
        "jpg" -> "image"
        "srt" -> "text"
        else -> "audio"
    }

    private fun mimeOf(ext: String) = when (ext) {
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"; "wav" -> "audio/x-wav"; "jpg" -> "image/jpeg"; "srt" -> "application/x-subrip"
        else -> "application/octet-stream"
    }

    private fun clients(url: String, full: Boolean): List<String?> {
        if (!url.contains("youtu")) return listOf(null)
        val base = if (full) CLIENTS else CLIENTS.take(4)
        if (!prefs.contains("good")) return base
        val g = prefs.getString("good", "").let { if (it.isNullOrEmpty()) null else it }
        return listOf(g) + base.filter { it != g }
    }

    private fun playerClient(r: YoutubeDLRequest, cl: String?) {
        if (cl != null) r.addOption("--extractor-args", "youtube:player_client=$cl")
    }

    private fun isRateLimited(e: Throwable?): Boolean {
        var cur = e
        while (cur != null) {
            val msg = cur.message.orEmpty()
            if (msg.contains("429") || msg.contains("Too Many Requests", true)) return true
            cur = cur.cause
        }
        return false
    }

    private fun onWifi(): Boolean {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ------------------------------------------------------------ rutas
    private fun route(s: IHTTPSession): Response {
        val p = s.uri
        val m = s.method
        fun q(k: String) = s.parameters[k]?.firstOrNull() ?: ""
        fun body(): JSONObject {
            val f = HashMap<String, String>()
            s.parseBody(f)
            return JSONObject(f["postData"] ?: "{}")
        }
        return when {
            p == "/" -> {
                val html = appCtx.assets.open("index.html").use { String(it.readBytes(), Charsets.UTF_8) }
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            }
            p == "/api/status" -> json(JSONObject().put("ready", ready).put("updating", updating)
                .put("version", if (ready) ytdlVersion() else "").put("error", engineError).put("msg", updateMsg))
            p == "/api/settings" && m == Method.GET -> json(settings())
            p == "/api/settings" && m == Method.POST -> { applySettings(body()); json(settings()) }
            p == "/api/update" && m == Method.POST -> {
                if (!updating) Thread { try { awaitEngine(); runUpdate() } catch (_: Exception) {} }.start()
                json(JSONObject().put("ok", true))
            }
            p == "/api/app/check" -> json(Updater.check(q("force") == "1"))
            p == "/api/app/update" && m == Method.POST -> json(Updater.start())
            p == "/api/app/update/status" -> json(Updater.status())
            p == "/api/lyrics" && m == Method.GET -> json(JSONObject().put("text", entry(q("id"))?.lyrics ?: ""))
            p == "/api/lyrics/find" && m == Method.POST -> {
                val b = body()
                val e = entry(b.optString("id"))
                if (e == null) text(Response.Status.NOT_FOUND, "no") else {
                    val qn = b.optString("q").trim()
                    val (qa, qt) = if (qn.isNotEmpty()) splitArtist(e.artist, qn) else (e.artist to e.track.ifEmpty { e.name.substringBeforeLast('.') })
                    val a = qa.ifEmpty { e.artist }
                    val t = qt.ifEmpty { e.track.ifEmpty { e.name.substringBeforeLast('.') } }
                    val r = fetchLyrics(a, t, e.duration)
                    if (r != null) { e.lyrics = r; saveIndex() }
                    json(JSONObject().put("ok", r != null))
                }
            }
            p == "/api/search" -> json(search(q("q")))
            p == "/api/info" && m == Method.POST -> json(info(body().getString("url").trim()))
            p == "/api/download" && m == Method.POST -> download(body())
            p == "/api/jobs" -> json(JSONArray(jobs.values.sortedByDescending { it.created }.map { it.json() }))
            p.startsWith("/api/jobs/") && m == Method.DELETE -> {
                val id = p.removePrefix("/api/jobs/")
                val j = jobs[id]
                if (j != null && (j.status == "queued" || j.status == "running")) {
                    j.cancel = true
                    try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Throwable) {}
                    if (j.status == "queued") { j.status = "error"; j.step = "Cancelado"; j.error = "Cancelado" }
                } else jobs.remove(id)
                json(JSONObject().put("ok", true))
            }
            p == "/api/library" && m == Method.GET -> library()
            p == "/api/library" && m == Method.DELETE -> {
                val e = entry(q("id"))
                if (e == null) text(Response.Status.NOT_FOUND, "no") else {
                    try { appCtx.contentResolver.delete(Uri.parse(e.uri), null, null) } catch (_: Exception) {}
                    index.remove(e); saveIndex()
                    json(JSONObject().put("ok", true))
                }
            }
            p.startsWith("/files/") -> serveEntry(s, p.removePrefix("/files/"))
            else -> text(Response.Status.NOT_FOUND, "no")
        }
    }

    private fun settings() = JSONObject().put("parallel", prefs.getInt("parallel", 2)).put("anti", prefs.getBoolean("anti", false))
        .put("dest", prefs.getString("dest", "media")).put("wifi", prefs.getBoolean("wifi", false))
        .put("autoUpdate", prefs.getBoolean("autoUpdate", true)).put("aria", prefs.getBoolean("aria", true))
        .put("lyrics", prefs.getBoolean("lyrics", true)).put("ariaOk", ariaOk).put("updUrl", prefs.getString("updUrl", "http://127.0.0.1:5001"))

    private fun applySettings(b: JSONObject) {
        val e = prefs.edit()
        if (b.has("parallel")) { val n = b.optInt("parallel", 2).coerceIn(1, 4); e.putInt("parallel", n); pool.corePoolSize = n }
        if (b.has("anti")) e.putBoolean("anti", b.optBoolean("anti"))
        if (b.has("wifi")) e.putBoolean("wifi", b.optBoolean("wifi"))
        if (b.has("autoUpdate")) e.putBoolean("autoUpdate", b.optBoolean("autoUpdate"))
        if (b.has("aria")) e.putBoolean("aria", b.optBoolean("aria"))
        if (b.has("lyrics")) e.putBoolean("lyrics", b.optBoolean("lyrics"))
        if (b.has("updUrl")) {
            val u = b.optString("updUrl").trim().trimEnd('/')
            if (Regex("https?://[A-Za-z0-9.\\-]+(:\\d+)?").matches(u)) { e.putString("updUrl", u); Updater.reset() }
        }
        if (b.has("dest") && b.optString("dest") in listOf("media", "downloads")) e.putString("dest", b.optString("dest"))
        e.apply()
    }

    // ------------------------------------------------------------ biblioteca (MediaStore: Galeria, Musica, Descargas)
    fun entry(id: String): Entry? = index.firstOrNull { it.id == id }

    private fun exists(uri: String): Boolean = try {
        appCtx.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (e: Exception) { false }

    private fun loadIndex() {
        try {
            val f = File(appCtx.filesDir, "index.json")
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                index.add(Entry(o.getString("id"), o.getString("name"), o.getString("uri"), o.getString("kind"),
                    o.getLong("size"), o.getString("mime"), o.getLong("date"), o.optString("lyrics", ""),
                    o.optString("artist", ""), o.optString("track", ""), o.optDouble("duration", 0.0)))
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun saveIndex() {
        val a = JSONArray()
        index.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri).put("kind", it.kind)
                 .put("size", it.size).put("mime", it.mime).put("date", it.date).put("lyrics", it.lyrics)
                .put("artist", it.artist).put("track", it.track).put("duration", it.duration))
        }
        File(appCtx.filesDir, "index.json").writeText(a.toString())
    }

    private fun library(): Response {
        val gone = index.filter { !exists(it.uri) }
        if (gone.isNotEmpty()) { index.removeAll(gone.toSet()); saveIndex() }
        return json(JSONArray(index.sortedByDescending { it.date }.map { it.json() }))
    }

    /** Copia el archivo a la coleccion publica (Peliculas / Musica / Imagenes / Descargas) y borra la copia temporal. */
    private fun saveToMedia(f: File): Entry? {
        val ext = f.extension.lowercase()
        val kind = kindOf(ext)
        val mime = mimeOf(ext)
        val downloads = prefs.getString("dest", "media") == "downloads"
        val (collection, rel) = when {
            downloads || kind == "text" -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_DOWNLOADS + "/YTPro")
            kind == "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to (Environment.DIRECTORY_MOVIES + "/YTPro")
            kind == "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to (Environment.DIRECTORY_MUSIC + "/YTPro")
            else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to (Environment.DIRECTORY_PICTURES + "/YTPro")
        }
        val cr = appCtx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, v) ?: return null
        return try {
            cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val e = Entry(UUID.randomUUID().toString().replace("-", "").take(10), f.name, uri.toString(), kind, f.length(), mime, System.currentTimeMillis())
            f.delete()
            e
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }

    private fun serveEntry(s: IHTTPSession, id: String): Response {
        val e = entry(id) ?: return text(Response.Status.NOT_FOUND, "no")
        val pfd = appCtx.contentResolver.openFileDescriptor(Uri.parse(e.uri), "r") ?: return text(Response.Status.NOT_FOUND, "no")
        val len = pfd.statSize
        val ins = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val range = s.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end = parts.getOrNull(1)?.toLongOrNull() ?: (len - 1)
            if (start >= len) { ins.close(); return text(Response.Status.RANGE_NOT_SATISFIABLE, "rango") }
            ins.skip(start)
            val r = NanoHTTPD.newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, e.mime, ins, end - start + 1)
            r.addHeader("Content-Range", "bytes $start-$end/$len")
            r.addHeader("Accept-Ranges", "bytes")
            return r
        }
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, e.mime, ins, len)
        r.addHeader("Accept-Ranges", "bytes")
        return r
    }

    // ------------------------------------------------------------ yt-dlp: busqueda e info
    private fun search(q: String): JSONArray {
        awaitEngine()
        val r = YoutubeDLRequest("ytsearch10:$q")
        r.addOption("--flat-playlist"); r.addOption("--dump-single-json"); r.addOption("--skip-download")
        val e = JSONObject(YoutubeDL.getInstance().execute(r).out).optJSONArray("entries") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until e.length()) {
            val x = e.getJSONObject(i)
            val id = x.str("id")
            if (id.isEmpty()) continue
            out.put(JSONObject().put("url", "https://www.youtube.com/watch?v=$id").put("title", x.str("title"))
                .put("channel", x.str("channel").ifEmpty { x.str("uploader") })
                .put("dur", fmtDur(x.optDouble("duration", 0.0)))
                .put("thumb", "https://i.ytimg.com/vi/$id/mqdefault.jpg"))
        }
        return out
    }

    private fun info(url: String): JSONObject {
        awaitEngine()
        var err: Exception? = null
        for (cl in clients(url, false)) {
            try {
                val r = YoutubeDLRequest(url)
                playerClient(r, cl)
                r.addOption("--no-playlist"); r.addOption("--skip-download"); r.addOption("--dump-single-json")
                val j = JSONObject(YoutubeDL.getInstance().execute(r).out)
                val hs = mutableSetOf<Int>()
                val fm = j.optJSONArray("formats") ?: JSONArray()
                for (i in 0 until fm.length()) {
                    val f = fm.getJSONObject(i)
                    val h = f.optInt("height", 0)
                    if (h > 0 && f.str("vcodec") != "none") hs.add(h)
                }
                var count = 0
                if (url.contains("list=")) {
                    try {
                        val pr = YoutubeDLRequest(url)
                        pr.addOption("--flat-playlist"); pr.addOption("--yes-playlist"); pr.addOption("--dump-single-json")
                        count = JSONObject(YoutubeDL.getInstance().execute(pr).out).optJSONArray("entries")?.length() ?: 0
                    } catch (_: Exception) {}
                }
                return JSONObject().put("url", url).put("title", j.str("title"))
                    .put("channel", j.str("channel").ifEmpty { j.str("uploader") })
                    .put("dur", fmtDur(j.optDouble("duration", 0.0))).put("thumb", j.str("thumbnail"))
                    .put("heights", JSONArray(hs.sortedDescending())).put("count", count)
                    .put("artist", j.str("artist").ifEmpty { j.str("creator") }).put("track", j.str("track").ifEmpty { j.str("title") })
                    .put("durSec", j.optDouble("duration", 0.0))
            } catch (e: Exception) {
                err = e
                // Un 429 es un límite temporal: no bombardear YouTube probando más clientes.
                if (isRateLimited(e)) break
            }
        }
        if (isRateLimited(err)) throw Exception("YouTube está limitando las solicitudes (HTTP 429). Espera un rato antes de volver a analizar este video.")
        throw err ?: Exception("No se pudo analizar el link")
    }

    // ------------------------------------------------------------ descargas
    private fun download(b: JSONObject): Response {
        val url = b.optString("url").trim()
        if (!Regex("^https?://.*").matches(url)) return text(Response.Status.BAD_REQUEST, "Link no valido")
        if (prefs.getBoolean("wifi", false) && !onWifi()) return text(Response.Status.CONFLICT, "Solo WiFi esta activado: conectate a una red WiFi o desactiva la opcion en Ajustes.")
        fun pick(v: String, ok: List<String>, def: String) = if (v in ok) v else def
        val langs = b.optString("sublangs").trim().let { if (Regex("[A-Za-z,.*\\-]{1,30}").matches(it)) it else "es,en" }
        val o = JSONObject()
            .put("kind", pick(b.optString("kind"), listOf("video", "audio", "subs", "thumb"), "video"))
            .put("acodec", pick(b.optString("acodec"), listOf("mp3", "m4a", "opus", "flac", "wav"), "mp3"))
            .put("abr", pick(b.optString("abr"), listOf("320", "256", "192", "128"), "192"))
            .put("container", pick(b.optString("container"), listOf("mp4", "mkv"), "mp4"))
            .put("tpl", pick(b.optString("tpl"), listOf("title", "channel", "date"), "title"))
            .put("height", b.optString("height").toIntOrNull() ?: 0)
            .put("h264", b.optBoolean("h264", true)).put("subs", b.optBoolean("subs", false)).put("sublangs", langs)
            .put("meta", b.optBoolean("meta", true)).put("playlist", b.optBoolean("playlist", false))
            .put("t0", b.optString("t0").take(12)).put("t1", b.optString("t1").take(12))
            .put("sb", b.optBoolean("sb", false)).put("chapters", b.optBoolean("chapters", false))
            .put("norm", b.optBoolean("norm", false)).put("lyrics", b.optBoolean("lyrics", true))
            .put("m", JSONObject().put("artist", b.optJSONObject("m")?.optString("artist", "")?.take(100) ?: "")
                .put("track", b.optJSONObject("m")?.optString("track", "")?.take(150) ?: "")
                .put("dur", b.optJSONObject("m")?.optDouble("dur", 0.0) ?: 0.0))
            .put("rate", pick(b.optString("rate"), listOf("500K", "1M", "2M", "5M"), ""))
            .put("items", b.optString("items").trim().let { if (Regex("[0-9,\\-: ]{0,40}").matches(it)) it else "" })
        val title = b.optString("title").ifEmpty { url }.take(150)
        val job = Job(UUID.randomUUID().toString().replace("-", "").take(10), url, title, o)
        jobs[job.id] = job
        pool.execute { runJob(job) }
        return json(JSONObject().put("id", job.id))
    }

    private fun args(r: YoutubeDLRequest, o: JSONObject, tmp: File, cl: String?, fast: Boolean, lyr: String?) {
        val kind = o.getString("kind")
        val codec = o.getString("acodec")
        val playlist = o.getBoolean("playlist")
        val base = when (o.optString("tpl")) {
            "channel" -> "%(channel).40s - %(title).90s"
            "date" -> "%(upload_date)s - %(title).100s"
            else -> "%(title).120s"
        }
        r.addOption("-P", tmp.absolutePath)
        r.addOption("-o", (if (playlist) "%(playlist_index)02d - " else "") + base + ".%(ext)s")
        r.addOption("--retries", "5"); r.addOption("--fragment-retries", "5")
        r.addOption("--concurrent-fragments", "10"); r.addOption("--throttled-rate", "100K"); r.addOption("-4")
        if (prefs.getBoolean("anti", false)) r.addOption("--http-chunk-size", "10M")
        r.addOption("--newline")
        if (fast) {
            // aria2c: varias conexiones a la vez por archivo (mucho mas rapido). Si falla, se reintenta sin el.
            r.addOption("--downloader", "libaria2c.so")
            r.addOption("--external-downloader-args", "aria2c:-x 16 -s 16 -k 1M --summary-interval=1")
        }
        if (playlist) {
            r.addOption("--yes-playlist")
            val items = o.optString("items")
            if (items.isNotEmpty()) r.addOption("--playlist-items", items)
        } else r.addOption("--no-playlist")
        val rate = o.optString("rate")
        if (rate.isNotEmpty()) r.addOption("--limit-rate", rate)
        playerClient(r, cl)
        when (kind) {
            "video" -> {
                val height = o.optInt("height", 0)
                val h = if (height > 0) "[height<=$height]" else ""
                val sel = if (o.optBoolean("h264", true)) {
                    // Prioriza H.264 + AAC, pero permite otros códecs y formatos progresivos como respaldo.
                    "bestvideo*$h[vcodec^=avc1]+bestaudio[ext=m4a]/" +
                    "bestvideo*$h+bestaudio/" +
                    "best$h/best"
                } else {
                    "bestvideo*$h+bestaudio/best$h/best"
                }
                r.addOption("-f", sel)
                r.addOption("--merge-output-format", o.optString("container", "mp4"))
                if (o.optBoolean("subs")) {
                    r.addOption("--write-subs"); r.addOption("--write-auto-subs")
                    r.addOption("--sub-langs", o.optString("sublangs", "es,en")); r.addOption("--embed-subs")
                }
            }
            "audio" -> {
                r.addOption("-f", "bestaudio/best"); r.addOption("-x"); r.addOption("--audio-format", codec)
                if (codec in listOf("mp3", "m4a", "opus")) r.addOption("--audio-quality", o.getString("abr") + "K")
                if (o.optBoolean("norm")) r.addOption("--postprocessor-args", "ExtractAudio:-af loudnorm=I=-16:TP=-1.5:LRA=11")
            }
            "subs" -> {
                r.addOption("--skip-download"); r.addOption("--write-subs"); r.addOption("--write-auto-subs")
                r.addOption("--sub-langs", o.optString("sublangs", "es,en")); r.addOption("--convert-subs", "srt")
            }
            else -> {
                r.addOption("--skip-download"); r.addOption("--write-thumbnail"); r.addOption("--convert-thumbnails", "jpg")
            }
        }
        if (kind == "video" || kind == "audio") {
            if (o.optBoolean("meta", true)) {
                r.addOption("--embed-metadata")
                if ((kind == "video" && o.optString("container", "mp4") == "mp4") || codec == "mp3" || codec == "m4a") {
                    r.addOption("--embed-thumbnail"); r.addOption("--convert-thumbnails", "jpg")
                }
            }
            if (o.optBoolean("sb")) {
                r.addOption("--sponsorblock-remove",
                    "sponsor,selfpromo,interaction,intro,outro" + if (kind == "audio") ",music_offtopic" else "")
            }
            if (o.optBoolean("chapters")) r.addOption("--split-chapters")
        }
        if (kind == "audio" && lyr != null) {
            // La letra viaja como etiqueta "lyrics" (USLT en MP3, (c)lyr en M4A): tu reproductor la lee al reescanear.
            if (!o.optBoolean("meta", true)) r.addOption("--embed-metadata")
            r.addOption("--parse-metadata", lyr.replace("%", "%%").replace(":", "\\:") + ":(?s)(?P<meta_lyrics>.+)")
        }
        val s = secs(o.optString("t0"))
        val e = secs(o.optString("t1"))
        if (s != null || e != null) {
            r.addOption("--download-sections", "*${s ?: 0.0}-${e ?: "inf"}")
            r.addOption("--force-keyframes-at-cuts")
        }
    }

    // ------------------------------------------------------------ letras sincronizadas (LRCLIB) -> se incrustan en el audio
    private fun lrcJson(path: String): Any? = try {
        val c = URL("https://lrclib.net/api/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 7000
        c.setRequestProperty("User-Agent", "YTPro/3 (uso personal)")
        try { if (c.responseCode != 200) null else JSONTokener(c.inputStream.bufferedReader().use { it.readText() }).nextValue() }
        finally { c.disconnect() }
    } catch (e: Exception) { null }

    private fun cleanTitle(t: String): String = t.replace(Regex("[\\(\\[].*?[\\)\\]]"), " ")
        .replace(Regex("(?i)\\b(official|oficial|video|lyrics?|letra|audio|hd|4k|hq|remaster(ed)?|karaoke|topic)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim(' ', '-', '|')

    private fun splitArtist(artist: String, track: String): Pair<String, String> {
        if (track.contains(" - ")) { val p = track.split(" - ", limit = 2); return p[0].trim() to p[1].trim() }
        return artist to track
    }

    private fun matchText(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun similarity(a: String, b: String): Double {
        val aa = matchText(a).split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        val bb = matchText(b).split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size.toDouble() / aa.union(bb).size.toDouble()
    }

    /** Busca letras y descarta versiones con título/duración claramente diferentes. */
    private fun fetchLyrics(artist: String, rawTitle: String, dur: Double): String? {
        val title = cleanTitle(rawTitle)
        if (title.isEmpty()) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        if (artist.isNotEmpty() && dur > 0) {
            val j = lrcJson("get?artist_name=${enc(artist)}&track_name=${enc(title)}&duration=${dur.toInt()}") as? JSONObject
            if (j != null) {
                val foundDur = j.optDouble("duration", 0.0)
                val tolerance = maxOf(10.0, dur * 0.08)
                val durationMatches = foundDur <= 0.0 || kotlin.math.abs(foundDur - dur) <= tolerance
                val titleMatches = similarity(title, j.str("trackName")) >= 0.45
                val artistMatches = artist.isEmpty() || similarity(artist, j.str("artistName")) >= 0.25
                if (durationMatches && titleMatches && artistMatches) {
                    val synced = j.str("syncedLyrics")
                    if (synced.isNotEmpty()) return synced
                    val plain = j.str("plainLyrics")
                    if (plain.isNotEmpty()) return plain
                }
            }
        }

        val items = mutableListOf<JSONObject>()
        val queries = listOf("$artist $title".trim(), title).distinct().filter { it.isNotEmpty() }
        for (q in queries) {
            val a = lrcJson("search?q=${enc(q)}") as? JSONArray ?: continue
            for (i in 0 until a.length()) items.add(a.getJSONObject(i))
            if (items.any { it.str("syncedLyrics").isNotEmpty() }) break
        }

        fun score(item: JSONObject): Double {
            val ts = similarity(title, item.str("trackName"))
            val ascore = if (artist.isNotEmpty()) similarity(artist, item.str("artistName")) else 0.5
            val foundDur = item.optDouble("duration", 0.0)
            val dscore = if (dur > 0 && foundDur > 0) {
                val delta = kotlin.math.abs(foundDur - dur)
                if (delta <= maxOf(10.0, dur * 0.08)) 1.0 else (1.0 - delta / maxOf(dur, foundDur)).coerceAtLeast(0.0)
            } else 0.5
            return ts * 0.70 + ascore * 0.15 + dscore * 0.15
        }

        val synced = items.filter { it.str("syncedLyrics").isNotEmpty() }
        val bestSynced = synced.maxByOrNull { score(it) }
        if (bestSynced != null && similarity(title, bestSynced.str("trackName")) >= 0.45) {
            val foundDur = bestSynced.optDouble("duration", 0.0)
            if (dur <= 0 || foundDur <= 0 || kotlin.math.abs(foundDur - dur) <= maxOf(15.0, dur * 0.12)) {
                return bestSynced.str("syncedLyrics")
            }
        }

        val plain = items.filter { it.str("plainLyrics").isNotEmpty() }
            .maxByOrNull { score(it) }
        return if (plain != null && similarity(title, plain.str("trackName")) >= 0.45) plain.str("plainLyrics") else null
    }

    private fun lyricsFor(job: Job): String? {
        val m = job.opts.optJSONObject("m")
        var artist = m?.optString("artist", "") ?: ""
        var track = m?.optString("track", "") ?: ""
        var dur = m?.optDouble("dur", 0.0) ?: 0.0
        if (track.isEmpty()) {
            val r = YoutubeDLRequest(job.url)
            r.addOption("--no-playlist"); r.addOption("--skip-download"); r.addOption("--dump-single-json")
            val j = JSONObject(YoutubeDL.getInstance().execute(r).out)
            artist = j.str("artist").ifEmpty { j.str("creator") }
            track = j.str("track").ifEmpty { j.str("title") }
            dur = j.optDouble("duration", 0.0)
            if (artist.isEmpty()) artist = j.str("channel").ifEmpty { j.str("uploader") }.removeSuffix(" - Topic")
        }
        val (a, t) = splitArtist(artist, track)
        return fetchLyrics(a, t, dur)
    }

    private fun hint(e: String?): String = when {
        e == null -> ""
        e.contains("429") || e.contains("Too Many Requests", true) ->
            " No sigas reintentando ahora: espera unos minutos, evita iniciar varias descargas y vuelve a probar."
        e.contains("Requested format is not available", true) || e.contains("Only images are available", true) ->
            " YouTube no entregó una pista de video para este cliente. Prueba más tarde; si otros videos funcionan, el problema puede ser específico de ese video o de la respuesta de YouTube."
        e.contains("TikTok", true) || e.contains("impersonat", true) ->
            " TikTok cambió su web: actualiza yt-dlp. Algunos enlaces pueden seguir sin funcionar por falta de impersonación."
        e.contains("403") || e.contains("Sign in", true) -> " Prueba actualizar yt-dlp y vuelve a intentarlo más tarde."
        else -> " Comprueba el enlace y prueba actualizar yt-dlp."
    }

    private fun runJob(job: Job) {
        val o = job.opts
        val kind = o.getString("kind")
        val exts = when (kind) { "subs" -> setOf("srt"); "thumb" -> setOf("jpg"); else -> AV }
        val tmp = File(tmpDir, "job_${job.id}")
        job.status = "running"
        job.step = "Preparando"
        var firstErr: String? = null
        var fast = prefs.getBoolean("aria", true) && ariaOk
        try { awaitEngine() } catch (e: Exception) { firstErr = e.message }
        var lyr: String? = null
        if (firstErr == null && kind == "audio" && o.optBoolean("lyrics", true) && prefs.getBoolean("lyrics", true) && !o.getBoolean("playlist")) {
            job.step = "Buscando letra"
            try { lyr = lyricsFor(job) } catch (_: Exception) {}
        }
        val lyrText = lyr
        if (firstErr == null) for (cl in clients(job.url, true)) {
            if (job.cancel) break
            tmp.deleteRecursively(); tmp.mkdirs()
            job.pct = 0.0
            try {
                val r = YoutubeDLRequest(job.url)
                args(r, o, tmp, cl, fast, lyr)
                YoutubeDL.getInstance().execute(r, job.id) { p, eta, line ->
                    val ar = Regex("\\((\\d{1,3})%\\).*?DL:(\\S+)(?:.*?ETA:(\\S+))?").find(line)
                    if (ar != null) {
                        job.pct = ar.groupValues[1].toDouble().coerceIn(0.0, 100.0)
                        job.speed = ar.groupValues[2] + "/s"
                        job.eta = ar.groupValues[3]
                        job.step = "Descargando (acelerado)"
                    } else if (p > 0f) {
                        job.pct = p.toDouble().coerceIn(0.0, 100.0)
                        job.eta = if (eta >= 0) "%02d:%02d".format(eta / 60, eta % 60) else ""
                        job.step = "Descargando"
                        Regex("at\\s+(\\S+/s)").find(line)?.let { job.speed = it.groupValues[1] }
                    }
                }
                val files = (tmp.listFiles() ?: emptyArray()).filter { it.isFile && it.extension.lowercase() in exts }
                    .sortedBy { it.name }
                if (files.isNotEmpty()) {
                    job.step = "Guardando en tu galeria"; job.speed = ""; job.eta = ""
                    val saved = files.mapNotNull { saveToMedia(it) }
                    saved.forEach { it ->
                        if (it.kind == "audio") {
                            val meta = o.optJSONObject("m")
                            it.artist = meta?.optString("artist", "") ?: ""
                            it.track = meta?.optString("track", "") ?: ""
                            it.duration = meta?.optDouble("dur", 0.0) ?: 0.0
                            if (lyrText != null) it.lyrics = lyrText
                        }
                    }
                    tmp.deleteRecursively()
                    if (saved.isEmpty()) { firstErr = "No se pudo guardar en la galeria/musica"; break }
                    index.addAll(saved); saveIndex()
                    prefs.edit().putString("good", cl ?: "").apply()
                    job.files = saved; job.pct = 100.0; job.step = "Listo"; job.status = "done"
                    return
                }
                if (firstErr == null) firstErr = "No se genero ningun archivo"
                fast = false
                lyr = null
            } catch (e: Exception) {
                if (job.cancel) break
                val message = (e.message ?: e.toString()).take(220)
                if (firstErr == null) firstErr = message
                fast = false
                lyr = null
                // No probar seis clientes seguidos cuando YouTube responde HTTP 429.
                if (isRateLimited(e)) {
                    firstErr = "YouTube está limitando las solicitudes (HTTP 429). Espera unos minutos y vuelve a intentarlo."
                    break
                }
            }
        }
        tmp.deleteRecursively()
        job.step = if (job.cancel) "Cancelado" else "Error"
        job.error = if (job.cancel) "Cancelado" else (firstErr ?: "Error") + hint(firstErr)
        job.status = "error"
    }
}
