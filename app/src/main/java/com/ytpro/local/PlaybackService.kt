package com.ytpro.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

/**
 * Servicio multimedia: MediaSession (pantalla de bloqueo, auriculares, Bluetooth) + notificacion con controles.
 * La musica suena en el reproductor de la interfaz; este servicio mantiene viva la app y le reenvia los comandos del sistema.
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private val h = Handler(Looper.getMainLooper())
    private var art: Bitmap? = null
    private var artFor = ""
    private var foreground = false
    private var focusReq: AudioFocusRequest? = null
    private var resumeOnGain = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { MainActivity.dispatch("pause") }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; MainActivity.dispatch("pause") }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { resumeOnGain = true; MainActivity.dispatch("pause") }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) { resumeOnGain = false; MainActivity.dispatch("play") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CH, "Reproductor", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "YTProSession")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { MainActivity.dispatch("play") }
            override fun onPause() { MainActivity.dispatch("pause") }
            override fun onSkipToNext() { MainActivity.dispatch("next") }
            override fun onSkipToPrevious() { MainActivity.dispatch("prev") }
            override fun onStop() { MainActivity.dispatch("pause"); stopNow() }
            override fun onSeekTo(pos: Long) { MainActivity.dispatch("seek:" + pos / 1000.0) }
        })
        session.setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        session.isActive = true
        try { registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) } catch (_: Exception) {}
        startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        foreground = true
        refresh()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            "prev" -> MainActivity.dispatch("prev")
            "next" -> MainActivity.dispatch("next")
            "toggle" -> MainActivity.dispatch(if (state.optBoolean("playing")) "pause" else "play")
            "stop" -> { MainActivity.dispatch("pause"); stopNow() }
        }
        return START_NOT_STICKY
    }

    private fun act(a: String, icon: Int, label: String): Notification.Action {
        val pi = PendingIntent.getService(this, a.hashCode(), Intent(this, PlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun build(): Notification {
        val playing = state.optBoolean("playing")
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH).setSmallIcon(if (playing) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(state.optString("title", "YT Pro")).setContentText(state.optString("artist", ""))
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(playing)
        art?.let { b.setLargeIcon(it) }
        b.addAction(act("prev", android.R.drawable.ic_media_previous, "Anterior"))
        b.addAction(act("toggle", if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pausa" else "Reproducir"))
        b.addAction(act("next", android.R.drawable.ic_media_next, "Siguiente"))
        b.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        return b.build()
    }

    /** Aplica el ultimo estado enviado por la interfaz a la sesion y a la notificacion. */
    fun refresh() {
        val playing = state.optBoolean("playing")
        val pos = (state.optDouble("pos", 0.0) * 1000).toLong()
        val dur = (state.optDouble("dur", 0.0) * 1000).toLong()
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, state.optString("title"))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.optString("artist")).putString(MediaMetadata.METADATA_KEY_ALBUM, state.optString("album"))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dur).apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }.build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, if (playing) 1f else 0f).build())
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (playing) {
            requestFocus()
            if (!foreground) { startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); foreground = true }
            else nm.notify(ID, build())
        } else {
            if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
            nm.notify(ID, build())
        }
        val u = state.optString("art")
        if (u.isNotEmpty() && u != artFor) {
            artFor = u
            Thread {
                val bytes = MusicLib.art(u)
                art = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { Bitmap.createScaledBitmap(it, 256, 256, true) }
                h.post { try { refresh() } catch (_: Exception) {} }
            }.start()
        }
    }

    private fun requestFocus() {
        if (focusReq != null) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener).build()
        focusReq = r
        am.requestAudioFocus(r)
    }

    private fun stopNow() {
        try { (getSystemService(AUDIO_SERVICE) as AudioManager).let { am -> focusReq?.let { am.abandonAudioFocusRequest(it) } } } catch (_: Exception) {}
        focusReq = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(noisy) } catch (_: Exception) {}
        try { session.isActive = false; session.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    companion object {
        const val CH = "ytpro_player"
        const val ID = 4102
        @Volatile var instance: PlaybackService? = null
        @Volatile var state: JSONObject = JSONObject()

        /** Llamado desde la interfaz cuando cambia la cancion, play/pausa o se hace seek. */
        fun push(ctx: Context, json: String) {
            state = try { JSONObject(json) } catch (e: Exception) { return }
            if (state.optBoolean("stop")) { instance?.stopNow(); return }
            val svc = instance
            if (svc != null) svc.h.post { svc.refresh() }
            else if (state.optBoolean("playing")) try { ctx.startForegroundService(Intent(ctx, PlaybackService::class.java)) } catch (_: Exception) {}
        }
    }
}
