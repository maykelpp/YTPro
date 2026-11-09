package com.ytpro.local

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media as AudioMedia
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class Track(
    val id: String, val uri: String, val title: String, val artist: String, val album: String,
    val durMs: Long, val size: Long, val mod: Long, val src: String, val mime: String,
    val trackNo: Int, val writable: Boolean, val lrcUri: String = ""
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("artist", artist).put("album", album)
        .put("dur", durMs / 1000.0).put("size", size).put("src", src).put("writable", writable).put("no", trackNo)
        .put("hasLrc", lrcUri.isNotEmpty())
}

/** Biblioteca local: MediaStore + carpetas elegidas con el Selector de Archivos (SAF), con permisos persistentes. */
object MusicLib {
    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    @Volatile var tracks: List<Track> = emptyList()
    @Volatile var scanning = false
    @Volatile var lastScan = 0L
    @Volatile var scanMsg = ""
    private val handler = Handler(Looper.getMainLooper())
    private val loud = ConcurrentHashMap<String, Double>()
    private val lyr = ConcurrentHashMap<String, String>()
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "mka")

    @Synchronized
    fun start(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        prefs = ctx.getSharedPreferences("ytpro_music", Context.MODE_PRIVATE)
        loadCache()
        try {
            ctx.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        handler.removeCallbacks(rescan)
                        handler.postDelayed(rescan, 4000L)   // actualizacion automatica cuando cambia la musica del telefono
                    }
                })
        } catch (_: Exception) {}
    }

    private val rescan = Runnable { scanAsync() }

    fun hasAudioPerm(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun find(id: String): Track? = tracks.firstOrNull { it.id == id }

    // ------------------------------------------------------------ carpetas (SAF)
    fun folders(): List<String> = try {
        val a = JSONArray(prefs.getString("folders", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    fun addFolder(uri: Uri) {
        val l = folders().toMutableList()
        if (uri.toString() !in l) l.add(uri.toString())
        prefs.edit().putString("folders", JSONArray(l).toString()).apply()
        scanAsync()
    }

    fun removeFolder(uri: String) {
        try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(uri), 3) } catch (_: Exception) {}
        prefs.edit().putString("folders", JSONArray(folders().filter { it != uri }).toString()).apply()
        scanAsync()
    }

    fun folderName(uri: String): String = try {
        val u = Uri.parse(uri)
        Uri.decode(DocumentsContract.getTreeDocumentId(u)).substringAfter(':').ifEmpty { "Almacenamiento" }
    } catch (e: Exception) { uri }

    // ------------------------------------------------------------ escaneo
    fun scanAsync() {
        if (scanning) return
        scanning = true
        Thread {
            try { scan() } catch (e: Throwable) { scanMsg = e.toString().take(120) }
            finally { scanning = false; lastScan = System.currentTimeMillis() }
        }.start()
    }

    private fun idOf(uri: String) = "t" + Integer.toHexString(uri.hashCode()) + Integer.toHexString(uri.length)

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun scan() {
        scanMsg = ""
        val prev = tracks.associateBy { it.uri }
        val out = ArrayList<Track>()
        scanMediaStore(out)
        for (f in folders()) scanTree(Uri.parse(f), prev, out)
        // Un mismo archivo visto por MediaStore y por una carpeta SAF se guarda una sola vez (se prefiere SAF: tiene permiso de escritura).
        val key = { t: Track -> "${t.size}|${t.durMs / 1000}|${norm(t.title)}" }
        val saf = out.filter { it.src == "saf" }.map(key).toSet()
        val clean = out.filter { it.src == "saf" || key(it) !in saf }.distinctBy { it.uri }
        tracks = clean
        saveCache()
    }

    private fun scanMediaStore(out: MutableList<Track>) {
        val base = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(AudioMedia._ID, AudioMedia.TITLE, AudioMedia.ARTIST, AudioMedia.ALBUM, AudioMedia.DURATION, AudioMedia.SIZE, AudioMedia.DATE_MODIFIED, AudioMedia.MIME_TYPE, AudioMedia.TRACK, AudioMedia.OWNER_PACKAGE_NAME)
        val sel = "${AudioMedia.DURATION} >= 20000 AND ${AudioMedia.IS_RINGTONE}=0 AND ${AudioMedia.IS_NOTIFICATION}=0 AND ${AudioMedia.IS_ALARM}=0"
        if (!hasAudioPerm()) scanMsg = "Sin permiso de audio: solo se ven los archivos creados por YT Pro."
        ctx.contentResolver.query(base, proj, sel, null, "${AudioMedia.TITLE} COLLATE NOCASE ASC")?.use { c ->
            while (c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(base, c.getLong(0)).toString()
                val owner = c.getString(9) ?: ""
                fun s(i: Int, d: String) = (c.getString(i) ?: "").let { if (it.isBlank() || it == "<unknown>") d else it }
                out.add(Track(idOf(uri), uri, s(1, "Sin título"), s(2, "Desconocido"), s(3, "Desconocido"), c.getLong(4), c.getLong(5),
                    c.getLong(6), "media", c.getString(7) ?: "audio/*", c.getInt(8) % 1000, owner == ctx.packageName))
            }
        }
    }

    private fun scanTree(tree: Uri, prev: Map<String, Track>, out: MutableList<Track>) {
        try {
            val root = DocumentsContract.getTreeDocumentId(tree)
            val lrcs = HashMap<String, String>()
            val found = ArrayList<List<Any>>()
            walk(tree, root, 0, lrcs, found)
            for (f in found) {
                val docUri = f[0] as Uri
                val name = f[1] as String
                val size = f[2] as Long
                val mod = f[3] as Long
                val mime = f[4] as String
                val us = docUri.toString()
                val lrc = lrcs[name.substringBeforeLast('.').lowercase()] ?: ""
                val p = prev[us]
                if (p != null && p.size == size && p.mod == mod) { out.add(Track(p.id, us, p.title, p.artist, p.album, p.durMs, size, mod, "saf", mime, p.trackNo, true, lrc)); continue }
                var title = name.substringBeforeLast('.'); var artist = "Desconocido"; var album = "Desconocido"; var dur = 0L; var no = 0
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, docUri)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                    dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    no = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull() ?: 0) % 1000
                } catch (_: Exception) {} finally { try { r.release() } catch (_: Exception) {} }
                if (dur in 1..19999) continue
                out.add(Track(idOf(us), us, title, artist, album, dur, size, mod, "saf", mime, no, true, lrc))
            }
        } catch (e: Exception) { scanMsg = "No se pudo leer una carpeta: " + (e.message ?: "").take(80) }
    }

    private fun walk(tree: Uri, docId: String, depth: Int, lrcs: MutableMap<String, String>, found: MutableList<List<Any>>) {
        if (depth > 8 || found.size > 20000) return
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val dirs = ArrayList<String>()
        ctx.contentResolver.query(kids, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { dirs.add(id); continue }
                val ext = name.substringAfterLast('.', "").lowercase()
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (ext == "lrc") lrcs[name.substringBeforeLast('.').lowercase()] = doc.toString()
                else if (mime.startsWith("audio/") || ext in AUDIO_EXT) found.add(listOf(doc, name, c.getLong(3), c.getLong(4), if (mime.startsWith("audio/")) mime else "audio/*"))
            }
        }
        for (d in dirs) walk(tree, d, depth + 1, lrcs, found)
    }

    // ------------------------------------------------------------ cache
    private fun loadCache() {
        try {
            val f = File(ctx.filesDir, "musiclib.json")
            if (f.exists()) {
                val a = JSONArray(f.readText()); val l = ArrayList<Track>()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    l.add(Track(o.getString("id"), o.getString("uri"), o.getString("title"), o.getString("artist"), o.getString("album"),
                        o.getLong("durMs"), o.getLong("size"), o.getLong("mod"), o.getString("src"), o.getString("mime"),
                        o.getInt("no"), o.getBoolean("w"), o.optString("lrc", "")))
                }
                tracks = l
            }
            val lf = File(ctx.filesDir, "loudness.json")
            if (lf.exists()) { val o = JSONObject(lf.readText()); o.keys().forEach { k -> loud[k] = o.getDouble(k) } }
            val yf = File(ctx.filesDir, "lyrics_store.json")
            if (yf.exists()) { val o = JSONObject(yf.readText()); o.keys().forEach { k -> lyr[k] = o.getString(k) } }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun saveCache() {
        try {
            val a = JSONArray()
            tracks.forEach { a.put(JSONObject().put("id", it.id).put("uri", it.uri).put("title", it.title).put("artist", it.artist)
                .put("album", it.album).put("durMs", it.durMs).put("size", it.size).put("mod", it.mod).put("src", it.src)
                .put("mime", it.mime).put("no", it.trackNo).put("w", it.writable).put("lrc", it.lrcUri)) }
            File(ctx.filesDir, "musiclib.json").writeText(a.toString())
        } catch (_: Exception) {}
    }

    fun invalidate(uri: String) { tracks = tracks.filter { it.uri != uri }; saveCache(); scanAsync() }

    fun libraryJson(): JSONObject = JSONObject().put("tracks", JSONArray(tracks.map { it.json() })).put("scanning", scanning)
        .put("audioPerm", hasAudioPerm()).put("msg", scanMsg).put("last", lastScan)
        .put("folders", JSONArray(folders().map { JSONObject().put("uri", it).put("name", folderName(it)) }))

    // ------------------------------------------------------------ letras (almacen propio + .lrc al lado + letras de YT Pro)
    fun lyricsFor(t: Track): String {
        lyr[t.id]?.let { return it }
        Backend.entryByUri(t.uri)?.lyrics?.takeIf { it.isNotEmpty() }?.let { return it }
        if (t.lrcUri.isNotEmpty()) try { return ctx.contentResolver.openInputStream(Uri.parse(t.lrcUri))?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) {}
        return ""
    }

    @Synchronized
    fun saveLyrics(id: String, text: String) {
        lyr[id] = text
        try { File(ctx.filesDir, "lyrics_store.json").writeText(JSONObject(lyr as Map<*, *>).toString()) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ caratula incrustada
    fun art(ref: String): ByteArray? {
        val uri = find(ref)?.uri ?: Backend.entry(ref)?.uri ?: ref
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(ctx, Uri.parse(uri)); r.embeddedPicture } catch (e: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }

    // ------------------------------------------------------------ nivel de volumen (RMS en dBFS) para normalizar entre canciones
    fun loudnessDb(uri: String): Double? {
        loud[uri]?.let { return it }
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, Uri.parse(uri), null)
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ex.selectTrack(i); fmt = f; break }
            }
            if (fmt == null) return null
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durUs > 60_000_000L) ex.seekTo(durUs / 3, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sum = 0.0; var n = 0L; var inDone = false; var outDone = false
            val t0 = System.currentTimeMillis()
            while (!outDone && n < 44100L * 2 * 12 && System.currentTimeMillis() - t0 < 6000L) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    while (sb.hasRemaining()) { val v = sb.get() / 32768.0; sum += v * v; n++ }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (n < 20000) return null
            val db = 20 * Math.log10(Math.max(Math.sqrt(sum / n), 1e-6))
            loud[uri] = db
            try { File(ctx.filesDir, "loudness.json").writeText(JSONObject(loud as Map<*, *>).toString()) } catch (_: Exception) {}
            return db
        } catch (e: Exception) { return null }
        finally { try { codec?.stop(); codec?.release() } catch (_: Exception) {}; try { ex.release() } catch (_: Exception) {} }
    }
}
