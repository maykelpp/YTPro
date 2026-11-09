package com.ytpro.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Mantiene vivas las descargas con la pantalla apagada y muestra su progreso, con Pausar / Reanudar / Cancelar. */
class DownloadService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var idle = 0

    private val tick = object : Runnable {
        override fun run() {
            val s = Backend.summary()
            if (s.getInt("active") == 0 && s.getInt("paused") == 0) {
                if (++idle >= 4) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            } else idle = 0
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(s))
            h.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Descargas", NotificationManager.IMPORTANCE_LOW))
        try {
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytpro:descargas")
            wl?.acquire(4 * 60 * 60 * 1000L)
        } catch (_: Exception) {}
        startForeground(ID, build(Backend.summary()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        h.post(tick)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            "pause" -> Backend.pauseAll()
            "resume" -> Backend.resumeAll()
            "cancel" -> Backend.cancelAll()
        }
        return START_NOT_STICKY
    }

    private fun act(a: String, label: String, icon: Int): Notification.Action {
        val pi = PendingIntent.getService(this, a.hashCode(), Intent(this, DownloadService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun build(s: org.json.JSONObject): Notification {
        val active = s.getInt("active"); val paused = s.getInt("paused"); val pct = s.getInt("pct")
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("YT Pro · Descargas")
            .setContentText(if (active > 0) "$active en curso · $pct% · ${s.optString("name")}" else "$paused en pausa")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        if (active > 0) b.setProgress(100, pct, pct <= 0)
        if (active > 0) b.addAction(act("pause", "Pausar", android.R.drawable.ic_media_pause)) else b.addAction(act("resume", "Reanudar", android.R.drawable.ic_media_play))
        b.addAction(act("cancel", "Cancelar", android.R.drawable.ic_menu_close_clear_cancel))
        return b.build()
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    companion object {
        const val CH = "ytpro_dl"
        const val ID = 4101
        fun ensure(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) } catch (_: Exception) {}
        }
    }
}
