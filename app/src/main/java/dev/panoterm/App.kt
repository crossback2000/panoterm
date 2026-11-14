package dev.panoterm

import android.app.Application
import dev.panoterm.data.HostRepository
import dev.panoterm.data.KeyRepository
import dev.panoterm.data.KnownHosts
import dev.panoterm.net.TailscaleManager
import dev.panoterm.term.SessionManager
import java.io.File

class App : Application() {
    lateinit var hosts: HostRepository
    lateinit var keys: KeyRepository
    lateinit var knownHosts: KnownHosts
    lateinit var tailscale: TailscaleManager
    lateinit var sessions: SessionManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        hosts = HostRepository(File(filesDir, "hosts.json"))
        keys = KeyRepository(File(filesDir, "keys"))
        knownHosts = KnownHosts(File(filesDir, "known_hosts"))
        tailscale = TailscaleManager(this)
        sessions = SessionManager(this)
        tailscale.start()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
