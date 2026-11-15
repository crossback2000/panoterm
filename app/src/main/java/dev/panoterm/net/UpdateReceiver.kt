package dev.panoterm.net

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.panoterm.App
import dev.panoterm.BuildConfig
import dev.panoterm.MainActivity
import dev.panoterm.Notifier
import dev.panoterm.R

/** Receives PackageInstaller results and the "we were updated" broadcast. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATUS -> onStatus(ctx, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> notify(
                ctx, "PanoTerm ${BuildConfig.VERSION_NAME}로 업데이트됐어요",
                "눌러서 열기", PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
    }

    private fun onStatus(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val app = ctx.applicationContext as App
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // First self-update (or the system wants confirmation): show the system dialog.
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (app.isForeground) {
                    runCatching { ctx.startActivity(confirm) }
                } else {
                    notify(
                        ctx, "PanoTerm 업데이트 준비됨", "눌러서 설치를 확인하세요",
                        PendingIntent.getActivity(ctx, 1, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {} // process is replaced; MY_PACKAGE_REPLACED follows
            else -> app.updates.onInstallResult(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    private fun notify(ctx: Context, title: String, body: String, pi: PendingIntent) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, Notifier.CH_AGENT)
            .setSmallIcon(R.drawable.ic_stat_panoterm)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val ACTION_STATUS = "dev.panoterm.UPDATE_STATUS"
        private const val NOTIFICATION_ID = 0x2000
    }
}
