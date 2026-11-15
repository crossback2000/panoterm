package dev.panoterm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CH_SESSION = "session"
    const val CH_AGENT = "agent"
    const val ID_SERVICE = 1
    const val EXTRA_SESSION = "sessionId"
    const val EXTRA_HOST = "hostId"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SESSION, "연결 유지", NotificationManager.IMPORTANCE_LOW).apply {
                description = "SSH 세션이 열려 있는 동안 표시됩니다"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_AGENT, "Claude / 터미널 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "입력 대기, 작업 완료, 터미널 벨"
            }
        )
    }

    fun openSessionIntent(ctx: Context, sessionId: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (sessionId != null) i.putExtra(EXTRA_SESSION, sessionId)
        return PendingIntent.getActivity(
            ctx, sessionId?.hashCode() ?: 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun agent(ctx: Context, sessionId: String, title: String, body: String) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, CH_AGENT)
            .setSmallIcon(R.drawable.ic_stat_panoterm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openSessionIntent(ctx, sessionId))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        // One notification per session (a newer one replaces the older).
        NotificationManagerCompat.from(ctx).notify(0x1000 + (sessionId.hashCode() and 0xfff), n)
    }
}
