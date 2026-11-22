package dev.panoterm.term

import dev.panoterm.R
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.panoterm.Notifier

/**
 * Keeps the process (and the embedded Tailscale node) alive while SSH sessions
 * are open, so Claude keeps streaming and keepalives keep running in the background.
 */
class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val n = NotificationCompat.Builder(this, Notifier.CH_SESSION)
            .setSmallIcon(dev.panoterm.R.drawable.ic_stat_panoterm)
            .setContentTitle("PanoTerm")
            .setContentText(getString(R.string.service_sessions, count))
            .setOngoing(true)
            .setContentIntent(Notifier.openSessionIntent(this, null))
            .build()
        ServiceCompat.startForeground(
            this, Notifier.ID_SERVICE, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (wakeLock == null) {
            // Partial wake lock: keeps the CPU running for SSH keepalives while the screen is off.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PanoTerm:ssh")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_COUNT = "count"

        fun update(ctx: Context, openSessions: Int) {
            val i = Intent(ctx, SessionService::class.java)
            if (openSessions > 0) {
                i.putExtra(EXTRA_COUNT, openSessions)
                runCatching { ctx.startForegroundService(i) }
            } else {
                ctx.stopService(i)
            }
        }
    }
}
