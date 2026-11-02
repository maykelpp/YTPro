package com.ytpro.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Recibe el resultado del instalador de Android y abre la ventana de confirmacion cuando hace falta. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val c = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (c != null) { c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(c) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.finish("")
            else -> Updater.finish(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Instalacion cancelada")
        }
    }
}
