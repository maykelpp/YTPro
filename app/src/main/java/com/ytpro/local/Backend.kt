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
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class Entry(val id: String, val name: String, val uri: String, val kind: String, val size: Long, val mime: String, val date: Long) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("size", size).put("date", date)
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
    private val jobs = ConcurrentHashMap<String, Job>()
    private val index = CopyOnWriteArrayList<Entry>()
    private val pool = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val CLIENTS: List<String?> = listOf(null, "android_vr", "tv", "web_safari", "mweb", "ios")
    private val AV = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "flac", "wav", "ogg", "aac")

    // ------------------------------------------------------------ arranque (el servidor sube al instante; el motor, en segundo plano)
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
            ready = true

            // Actualiza desde NIGHTLY como máximo una vez al día.
            // Si una actualización falla, permite reintentar después de 6 horas.
            val now = System.currentTimeMillis()
            val lastSuccess = prefs.getLong("lastUpdate", 0L)
            val lastAttempt = prefs.getLong("lastUpdateAttempt", 0L)
            val day = 86_400_000L
            val retryDelay = 21_600_000L
            if (prefs.getBoolean("autoUpdate", true) &&
                now - lastSuccess >= day && now - lastAttempt >= retryDelay) {
                runUpdate()
            }
        } catch (e: Throwable) {
            engineError = e.toString().take(200)
            android.util.Log.e("YTPro", "No se pudo iniciar yt-dlp/FFmpeg", e)
        }
    }

    private fun awaitEngine() {
        val t0 = System.currentTimeMillis()
        while ((!ready || updating) && engineError.isEmpty() &&
            System.currentTimeMillis() - t0 < 180_000L) Thread.sleep(200)
        if (engineError.isNotEmpty()) throw Exception("Motor: $engineError")
        if (!ready) throw Exception("El motor sigue iniciando, espera unos segundos")
    }

    private fun ytdlVersion(): String {
        if (verCache.isNotEmpty()) return verCache
        return try {
            // API documentada por youtubedl-android: versionName(Context).
            (YoutubeDL.getInstance().versionName(appCtx) ?: "?").also { verCache = it }
        } catch (e: Throwable) {
            android.util.Log.w("YTPro", "No se pudo consultar la versión de yt-dlp", e)
            "?"
        }
    }

    /**
     * Actualiza yt-dlp desde el canal NIGHTLY oficial de youtubedl-android.
     * Solo guarda lastUpdate cuando el estado confirma DONE o ALREADY_UP_TO_DATE.
     */
    @Synchronized
    private fun runUpdate() {
        if (updating) return
        updating = true
        val now = System.currentTimeMillis()
        prefs.edit().putLong("lastUpdateAttempt", now).apply()
        updateMsg = "Descargando actualización NIGHTLY de yt-dlp…"
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                appCtx,
                YoutubeDL.UpdateChannel._NIGHTLY
            )
            when (status.toString().substringAfterLast('.').uppercase()) {
                "DONE" -> {
                    verCache = ""
                    prefs.edit().putLong("lastUpdate", now).apply()
                    updateMsg = "yt-dlp actualizado: ${ytdlVersion()}"
                    android.util.Log.i("YTPro", updateMsg)
                }
                "ALREADY_UP_TO_DATE" -> {
                    verCache = ""
                    prefs.edit().putLong("lastUpdate", now).apply()
                    updateMsg = "yt-dlp ya está actualizado: ${ytdlVersion()}"
                    android.util.Log.i("YTPro", updateMsg)
                }
                else -> {
                    updateMsg = "La actualización no se completó ($status). Se podrá reintentar en 6 horas."
                    android.util.Log.w("YTPro", updateMsg)
                }
            }
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            updateMsg = "Falló la actualización: " +
                (cause.message ?: cause.toString()).take(150) +
                ". Se podrá reintentar en 6 horas."
            android.util.Log.e("YTPro", updateMsg, cause)
        } finally {
            updating = false
        }
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
        .put("autoUpdate", prefs.getBoolean("autoUpdate", true))

    private fun applySettings(b: JSONObject) {
        val e = prefs.edit()
        if (b.has("parallel")) { val n = b.optInt("parallel", 2).coerceIn(1, 4); e.putInt("parallel", n); pool.corePoolSize = n }
        if (b.has("anti")) e.putBoolean("anti", b.optBoolean("anti"))
        if (b.has("wifi")) e.putBoolean("wifi", b.optBoolean("wifi"))
        if (b.has("autoUpdate")) e.putBoolean("autoUpdate", b.optBoolean("autoUpdate"))
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
                    o.getLong("size"), o.getString("mime"), o.getLong("date")))
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun saveIndex() {
        val a = JSONArray()
        index.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri).put("kind", it.kind)
                .put("size", it.size).put("mime", it.mime).put("date", it.date))
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
                r.addOption("--no-playlist"); r.addOption("--skip-download"); r.addOption("--extractor-retries", "3"); r.addOption("--dump-single-json")
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
            } catch (e: Exception) { err = e }
        }
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
            .put("norm", b.optBoolean("norm", false))
            .put("rate", pick(b.optString("rate"), listOf("500K", "1M", "2M", "5M"), ""))
            .put("items", b.optString("items").trim().let { if (Regex("[0-9,\\-: ]{0,40}").matches(it)) it else "" })
        val title = b.optString("title").ifEmpty { url }.take(150)
        val job = Job(UUID.randomUUID().toString().replace("-", "").take(10), url, title, o)
        jobs[job.id] = job
        pool.execute { runJob(job) }
        return json(JSONObject().put("id", job.id))
    }

    private fun args(r: YoutubeDLRequest, o: JSONObject, tmp: File, cl: String?) {
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
        r.addOption("--retries", "5"); r.addOption("--fragment-retries", "5"); r.addOption("--extractor-retries", "3")
        r.addOption("--concurrent-fragments", "8"); r.addOption("--throttled-rate", "100K"); r.addOption("-4")
        if (prefs.getBoolean("anti", false)) r.addOption("--http-chunk-size", "10M")
        r.addOption("--newline")
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
                val sel = (if (o.optBoolean("h264", true)) "bv*$h[vcodec^=avc1]+ba[ext=m4a]/" else "") + "bv*$h+ba/b$h/b"
                r.addOption("-f", sel); r.addOption("--merge-output-format", o.optString("container", "mp4"))
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
        val s = secs(o.optString("t0"))
        val e = secs(o.optString("t1"))
        if (s != null || e != null) {
            r.addOption("--download-sections", "*${s ?: 0.0}-${e ?: "inf"}")
            r.addOption("--force-keyframes-at-cuts")
        }
    }

    private fun hint(e: String?): String = when {
        e == null -> ""
        e.contains("TikTok", true) || e.contains("impersonat", true) ->
            "  -> Esta compilacion Android no incluye curl_cffi/impersonation. Actualizar yt-dlp puede ayudar con cambios del extractor, pero no instala esa dependencia; TikTok puede seguir sin funcionar."
        e.contains("Instagram", true) ->
            "  -> Instagram puede exigir una sesion valida o limitar enlaces privados. Prueba un enlace publico al que tengas permiso de acceder; esta app no puede abrir contenido privado."
        e.contains("403") || e.contains("Sign in", true) ->
            "  -> El sitio esta rechazando la solicitud o requiere autenticacion. Actualiza yt-dlp y usa solo contenido al que tengas acceso autorizado."
        else -> "  -> Comprueba que el enlace sea publico y vuelve a intentar. Tambien puedes actualizar yt-dlp desde Ajustes."
    }

    private fun runJob(job: Job) {
        val o = job.opts
        val kind = o.getString("kind")
        val exts = when (kind) { "subs" -> setOf("srt"); "thumb" -> setOf("jpg"); else -> AV }
        val tmp = File(tmpDir, "job_${job.id}")
        job.status = "running"
        job.step = "Preparando"
        var firstErr: String? = null
        try { awaitEngine() } catch (e: Exception) { firstErr = e.message }
        if (firstErr == null) for (cl in clients(job.url, true)) {
            if (job.cancel) break
            tmp.deleteRecursively(); tmp.mkdirs()
            job.pct = 0.0
            try {
                val r = YoutubeDLRequest(job.url)
                args(r, o, tmp, cl)
                YoutubeDL.getInstance().execute(r, job.id) { p, eta, line ->
                    job.pct = p.toDouble().coerceIn(0.0, 100.0)
                    job.eta = if (eta >= 0) "%02d:%02d".format(eta / 60, eta % 60) else ""
                    job.step = "Descargando"
                    Regex("at\\s+(\\S+/s)").find(line)?.let { job.speed = it.groupValues[1] }
                }
                val files = (tmp.listFiles() ?: emptyArray()).filter { it.isFile && it.extension.lowercase() in exts }
                    .sortedBy { it.name }
                if (files.isNotEmpty()) {
                    job.step = "Guardando en tu galeria"; job.speed = ""; job.eta = ""
                    val saved = files.mapNotNull { saveToMedia(it) }
                    tmp.deleteRecursively()
                    if (saved.isEmpty()) { firstErr = "No se pudo guardar en la galeria/musica"; break }
                    index.addAll(saved); saveIndex()
                    prefs.edit().putString("good", cl ?: "").apply()
                    job.files = saved; job.pct = 100.0; job.step = "Listo"; job.status = "done"
                    return
                }
                if (firstErr == null) firstErr = "No se genero ningun archivo"
            } catch (e: Exception) {
                if (job.cancel) break
                if (firstErr == null) firstErr = (e.message ?: e.toString()).take(220)
            }
        }
        tmp.deleteRecursively()
        job.step = if (job.cancel) "Cancelado" else "Error"
        job.error = if (job.cancel) "Cancelado" else (firstErr ?: "Error") + hint(firstErr)
        job.status = "error"
    }
}
