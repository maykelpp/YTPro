package com.ytpro.local

import android.content.ContentValues
import android.content.Context
import android.os.Environment
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
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class Job(val id: String, val url: String, var title: String, val opts: JSONObject) {
    @Volatile var status = "queued"
    @Volatile var step = "En cola"
    @Volatile var pct = 0.0
    @Volatile var speed = ""
    @Volatile var eta = ""
    @Volatile var error = ""
    @Volatile var files: List<String> = emptyList()
    @Volatile var cancel = false
    val created = System.currentTimeMillis()

    fun json(): JSONObject = JSONObject().put("id", id).put("url", url).put("title", title)
        .put("status", status).put("step", step).put("pct", pct).put("speed", speed).put("eta", eta)
        .put("item", "").put("error", error).put("files", JSONArray(files)).put("created", created / 1000.0)
}

/** Servidor interno: misma API que ytdl_pro.py, pero ejecutando yt-dlp + FFmpeg empaquetados en la APK. */
object Backend {
    const val PORT = 8731
    lateinit var dir: File
    private lateinit var appCtx: Context
    private var server: Srv? = null
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pool = Executors.newFixedThreadPool(2)
    private val CLIENTS: List<String?> = listOf(null, "android_vr", "tv", "web_safari", "mweb", "ios")
    private val AV = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "flac", "wav", "ogg", "aac")

    @Synchronized
    fun start(ctx: Context) {
        if (server != null) return
        appCtx = ctx.applicationContext
        dir = File(appCtx.getExternalFilesDir(null) ?: appCtx.filesDir, "YTPro").also { it.mkdirs() }
        YoutubeDL.getInstance().init(appCtx)
        FFmpeg.getInstance().init(appCtx)
        server = Srv().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
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
        "flac" -> "audio/flac"; "wav" -> "audio/wav"; "jpg" -> "image/jpeg"; "srt" -> "application/x-subrip"
        else -> "application/octet-stream"
    }

    private fun clients(url: String, full: Boolean): List<String?> =
        if (!url.contains("youtu")) listOf(null) else if (full) CLIENTS else CLIENTS.take(4)

    private fun playerClient(r: YoutubeDLRequest, cl: String?) {
        if (cl != null) r.addOption("--extractor-args", "youtube:player_client=$cl")
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
            p == "/api/search" -> json(search(q("q")))
            p == "/api/info" && m == Method.POST -> json(info(body().getString("url").trim()))
            p == "/api/download" && m == Method.POST -> download(body())
            p == "/api/jobs" -> json(JSONArray(jobs.values.sortedByDescending { it.created }.map { it.json() }))
            p.startsWith("/api/jobs/") && m == Method.DELETE -> {
                val id = p.removePrefix("/api/jobs/")
                val j = jobs[id]
                if (j != null && (j.status == "queued" || j.status == "running")) {
                    j.cancel = true
                    YoutubeDL.getInstance().destroyProcessById(id)
                    if (j.status == "queued") { j.status = "error"; j.step = "Cancelado"; j.error = "Cancelado" }
                } else jobs.remove(id)
                json(JSONObject().put("ok", true))
            }
            p == "/api/library" && m == Method.GET -> library()
            p == "/api/library" && m == Method.DELETE -> {
                val f = File(dir, q("name")).canonicalFile
                if (f.parentFile != dir.canonicalFile || !f.isFile) text(Response.Status.NOT_FOUND, "no") else {
                    f.delete(); json(JSONObject().put("ok", true))
                }
            }
            p.startsWith("/files/") -> serveFile(s, p.removePrefix("/files/"))
            else -> text(Response.Status.NOT_FOUND, "no")
        }
    }

    private fun library(): Response {
        val exts = AV + setOf("jpg", "srt")
        val fs = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.extension.lowercase() in exts }
            .sortedByDescending { it.lastModified() }
        return json(JSONArray(fs.map {
            JSONObject().put("name", it.name).put("size", it.length()).put("kind", kindOf(it.extension.lowercase()))
        }))
    }

    private fun serveFile(s: IHTTPSession, name: String): Response {
        val f = File(dir, name).canonicalFile
        if (f.parentFile != dir.canonicalFile || !f.isFile) return text(Response.Status.NOT_FOUND, "no")
        val len = f.length()
        val mime = mimeOf(f.extension.lowercase())
        val range = s.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end = parts.getOrNull(1)?.toLongOrNull() ?: (len - 1)
            if (start >= len) return text(Response.Status.RANGE_NOT_SATISFIABLE, "rango")
            val fis = FileInputStream(f)
            fis.skip(start)
            val r = NanoHTTPD.newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, end - start + 1)
            r.addHeader("Content-Range", "bytes $start-$end/$len")
            r.addHeader("Accept-Ranges", "bytes")
            return r
        }
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), len)
        r.addHeader("Accept-Ranges", "bytes")
        return r
    }

    // ------------------------------------------------------------ yt-dlp: busqueda e info
    private fun search(q: String): JSONArray {
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
            } catch (e: Exception) { err = e }
        }
        throw err ?: Exception("No se pudo analizar el link")
    }

    // ------------------------------------------------------------ descargas
    private fun download(b: JSONObject): Response {
        val url = b.optString("url").trim()
        if (!Regex("^https?://.*").matches(url)) return text(Response.Status.BAD_REQUEST, "Link no valido")
        fun pick(v: String, ok: List<String>, def: String) = if (v in ok) v else def
        val o = JSONObject()
            .put("kind", pick(b.optString("kind"), listOf("video", "audio", "subs", "thumb"), "video"))
            .put("acodec", pick(b.optString("acodec"), listOf("mp3", "m4a", "opus", "flac", "wav"), "mp3"))
            .put("abr", pick(b.optString("abr"), listOf("320", "256", "192", "128"), "192"))
            .put("height", b.optString("height").toIntOrNull() ?: 0)
            .put("h264", b.optBoolean("h264", true)).put("subs", b.optBoolean("subs", false))
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
        r.addOption("-P", tmp.absolutePath)
        r.addOption("-o", if (playlist) "%(playlist_index)02d - %(title).120s.%(ext)s" else "%(title).120s.%(ext)s")
        r.addOption("--retries", "5"); r.addOption("--fragment-retries", "5")
        r.addOption("--http-chunk-size", "10M"); r.addOption("--concurrent-fragments", "4"); r.addOption("-4")
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
                r.addOption("-f", sel); r.addOption("--merge-output-format", "mp4")
                if (o.optBoolean("subs")) {
                    r.addOption("--write-subs"); r.addOption("--write-auto-subs")
                    r.addOption("--sub-langs", "es,en"); r.addOption("--embed-subs")
                }
            }
            "audio" -> {
                r.addOption("-f", "bestaudio/best"); r.addOption("-x"); r.addOption("--audio-format", codec)
                if (codec in listOf("mp3", "m4a", "opus")) r.addOption("--audio-quality", o.getString("abr") + "K")
                if (o.optBoolean("norm")) r.addOption("--postprocessor-args", "ExtractAudio:-af loudnorm=I=-16:TP=-1.5:LRA=11")
            }
            "subs" -> {
                r.addOption("--skip-download"); r.addOption("--write-subs"); r.addOption("--write-auto-subs")
                r.addOption("--sub-langs", "es,en"); r.addOption("--convert-subs", "srt")
            }
            else -> {
                r.addOption("--skip-download"); r.addOption("--write-thumbnail"); r.addOption("--convert-thumbnails", "jpg")
            }
        }
        if (kind == "video" || kind == "audio") {
            if (o.optBoolean("meta", true)) {
                r.addOption("--embed-metadata")
                if (kind == "video" || codec == "mp3" || codec == "m4a") {
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

    private fun runJob(job: Job) {
        val o = job.opts
        val kind = o.getString("kind")
        val exts = when (kind) { "subs" -> setOf("srt"); "thumb" -> setOf("jpg"); else -> AV }
        val tmp = File(dir, ".tmp_${job.id}")
        job.status = "running"
        var firstErr: String? = null
        for (cl in clients(job.url, true)) {
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
                    val done = mutableListOf<String>()
                    for (f in files) {
                        var dest = File(dir, f.name)
                        if (dest.exists()) dest = File(dir, "${f.nameWithoutExtension}_${job.id.take(4)}.${f.extension}")
                        if (!f.renameTo(dest)) { f.copyTo(dest, true); f.delete() }
                        done.add(dest.name)
                    }
                    tmp.deleteRecursively()
                    job.files = done; job.pct = 100.0; job.speed = ""; job.eta = ""
                    job.step = "Listo"; job.status = "done"
                    return
                }
                if (firstErr == null) firstErr = "No se genero ningun archivo"
            } catch (e: Exception) {
                if (job.cancel) break
                if (firstErr == null) firstErr = (e.message ?: e.toString()).take(200)
            }
        }
        tmp.deleteRecursively()
        job.step = if (job.cancel) "Cancelado" else "Error"
        job.error = if (job.cancel) "Cancelado" else (firstErr ?: "Error") + "  -> Sube la version de youtubedl-android en app/build.gradle y recompila."
        job.status = "error"
    }

    // ------------------------------------------------------------ guardar en Descargas
    fun saveToDownloads(f: File): Boolean {
        if (!f.isFile) return false
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(f.extension.lowercase()))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YTPro")
            }
            val uri = appCtx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
            appCtx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            true
        } catch (e: Exception) { false }
    }
}
