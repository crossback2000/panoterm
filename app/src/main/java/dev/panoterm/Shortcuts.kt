package dev.panoterm

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.Toast
import dev.panoterm.data.Host

/** Launcher shortcuts: long-press the app icon, or pin a host to the home screen for one-tap connect. */
object Shortcuts {
    private fun info(ctx: Context, h: Host, rank: Int = 0): ShortcutInfo {
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(Notifier.EXTRA_HOST, h.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return ShortcutInfo.Builder(ctx, "host-${h.id}")
            .setShortLabel(h.title.take(24))
            .setLongLabel("${h.user}@${h.address}".take(40))
            .setIcon(Icon.createWithResource(ctx, R.mipmap.ic_launcher))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    /** Publishes the first few hosts as dynamic shortcuts. */
    fun refresh(ctx: Context, hosts: List<Host>) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        val max = sm.maxShortcutCountPerActivity.coerceAtMost(4)
        runCatching { sm.dynamicShortcuts = hosts.take(max).mapIndexed { i, h -> info(ctx, h, i) } }
    }

    fun pin(ctx: Context, h: Host) {
        val sm = ctx.getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(ctx, "이 런처는 바로가기 고정을 지원하지 않습니다", Toast.LENGTH_SHORT).show()
            return
        }
        sm.requestPinShortcut(info(ctx, h), null)
    }
}
