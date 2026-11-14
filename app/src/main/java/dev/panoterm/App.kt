package dev.panoterm

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.panoterm.data.HostRepository
import dev.panoterm.data.KeyRepository
import dev.panoterm.data.KnownHosts
import dev.panoterm.data.Settings
import dev.panoterm.net.TailscaleManager
import dev.panoterm.term.SessionManager
import java.io.File

class App : Application() {
    lateinit var hosts: HostRepository
    lateinit var keys: KeyRepository
    lateinit var knownHosts: KnownHosts
    lateinit var settings: Settings
    lateinit var tailscale: TailscaleManager
    lateinit var sessions: SessionManager

    /** True while any activity is started (visible). */
    @Volatile var isForeground = false
        private set

    /** Session currently shown on the terminal screen, if any. */
    @Volatile var visibleSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifier.createChannels(this)
        hosts = HostRepository(File(filesDir, "hosts.json"))
        keys = KeyRepository(File(filesDir, "keys"))
        knownHosts = KnownHosts(File(filesDir, "known_hosts"))
        settings = Settings(this)
        tailscale = TailscaleManager(this)
        sessions = SessionManager(this)
        tailscale.onNetworkAvailable = { sessions.onNetworkAvailable() }
        hosts.onChanged = { sessions.onHostChanged(it) }
        tailscale.start()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: Activity) { started++; isForeground = true }
            override fun onActivityStopped(a: Activity) { started--; isForeground = started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
