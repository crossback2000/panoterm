package dev.panoterm.term

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private val main = Handler(Looper.getMainLooper())

    // The lock is taken with a timeout as a safety net, then re-armed every hour for
    // as long as sessions are open. Previously it was acquired once for 6h and never
    // renewed, so after 6h the CPU slept with the screen off, keepalives stalled and
    // the session dropped with "keepalive timeout".
    private val renew = object : Runnable {
        override fun run() {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            main.postDelayed(this, WAKE_LOCK_RENEW_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val n = NotificationCompat.Builder(this, Notifier.CH_SESSION)
            .setSmallIcon(dev.panoterm.R.drawable.ic_stat_panoterm)
            .setContentTitle("PanoTerm")
            .setContentText("SSH 세션 ${count}개 연결 유지 중")
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
                .apply { setReferenceCounted(false) }
            renew.run()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(renew)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_COUNT = "count"
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 60 * 60 * 1000L

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
