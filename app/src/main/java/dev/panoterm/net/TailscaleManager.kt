package dev.panoterm.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import core.Core
import core.Node
import core.StatusListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.NetworkInterface

/** Owns the embedded Tailscale node (tsnet in the Go core). */
class TailscaleManager(private val ctx: Context) {
    data class Peer(val name: String, val dns: String, val ip: String, val os: String, val online: Boolean)

    private val _state = MutableStateFlow("Off")
    val state: StateFlow<String> = _state
    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers
    private val _lastLog = MutableStateFlow("")
    val lastLog: StateFlow<String> = _lastLog

    @Volatile
    private var node: Node? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var callbackRegistered = false

    /** Called when the default network becomes available (after loss / change). */
    var onNetworkAvailable: (() -> Unit)? = null

    fun nodeOrNull(): Node? = node
    val isRunning: Boolean get() = _state.value == "Running"

    private val listener = object : StatusListener {
        override fun onStatus(state: String, authURL: String) {
            android.util.Log.i("PanoTS", "state=$state authURL=${authURL.isNotBlank()}")
            _state.value = state
            _authUrl.value = authURL.ifBlank { null }
            if (state == "Running") refreshPeers()
        }

        override fun onLog(line: String) {
            android.util.Log.i("PanoTS", line)
            _lastLog.value = line
        }
    }

    fun start() {
        if (node != null) return
        Core.setDebugLogs(dev.panoterm.BuildConfig.DEBUG)
        pushNetworkInfo()
        registerNetworkCallback()
        _state.value = "Starting"
        scope.launch {
            try {
                val n = Core.newNode(ctx.filesDir.absolutePath, hostname())
                node = n
                n.start("", listener)
            } catch (e: Exception) {
                node = null
                _state.value = "Error"
                _lastLog.value = e.message ?: e.toString()
            }
        }
    }

    fun refreshPeers() {
        scope.launch {
            val n = node ?: return@launch
            try {
                val arr = JSONArray(n.peersJSON())
                _peers.value = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Peer(o.optString("name"), o.optString("dns"), o.optString("ip"), o.optString("os"), o.optBoolean("online"))
                }
            } catch (e: Exception) {
                _lastLog.value = e.message ?: e.toString()
            }
        }
    }

    fun logout() {
        scope.launch {
            try {
                node?.logout()
                _peers.value = emptyList()
            } catch (e: Exception) {
                _lastLog.value = e.message ?: e.toString()
            }
        }
    }

    private fun hostname(): String {
        val model = Build.MODEL.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')
        return "panoterm-$model".take(60)
    }

    /** Go cannot enumerate interfaces or read resolv.conf on Android, so hand it both. */
    //
    // On Android 11+ java.net.NetworkInterface often hides the real uplinks (wlan0, rmnet...),
    // so interfaces and addresses come from ConnectivityManager's LinkProperties first,
    // merged with whatever NetworkInterface does expose (loopback etc.).
    private data class If(val name: String, var index: Int, var mtu: Int, var flags: String, val addrs: LinkedHashSet<String>)

    @Suppress("DEPRECATION")
    private fun pushNetworkInfo() {
        val ifs = LinkedHashMap<String, If>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                runCatching {
                    val flags = buildString {
                        if (ni.isUp) append('u')
                        if (ni.isLoopback) append('l')
                        if (ni.supportsMulticast()) append('m')
                        if (ni.isPointToPoint) append('p')
                    }.ifEmpty { "-" }
                    val addrs = ni.interfaceAddresses.mapNotNull { ia ->
                        ia.address?.hostAddress?.substringBefore('%')?.let { "$it/${ia.networkPrefixLength}" }
                    }
                    ifs[ni.name] = If(ni.name, ni.index, runCatching { ni.mtu }.getOrDefault(1500), flags, LinkedHashSet(addrs))
                }
            }
        } catch (_: Exception) {
        }

        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        var syntheticIndex = 1000
        for (net in cm.allNetworks) {
            val lp = cm.getLinkProperties(net) ?: continue
            val name = lp.interfaceName ?: continue
            val entry = ifs.getOrPut(name) {
                val idx = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: syntheticIndex++
                If(name, idx, if (lp.mtu > 0) lp.mtu else 1500, "um", LinkedHashSet())
            }
            if (!entry.flags.contains('u')) entry.flags = "u" + entry.flags.replace("-", "")
            lp.linkAddresses.forEach { la ->
                la.address.hostAddress?.substringBefore('%')?.let { entry.addrs.add("$it/${la.prefixLength}") }
            }
        }

        val sb = StringBuilder()
        ifs.values.forEach { i ->
            sb.append(i.name).append('\t').append(i.index).append('\t').append(i.mtu).append('\t')
                .append(i.flags).append('\t').append(i.addrs.joinToString(",")).append('\n')
        }
        if (dev.panoterm.BuildConfig.DEBUG) android.util.Log.i("PanoTS", "interfaces:\n$sb")
        Core.setInterfaces(sb.toString())

        val active = cm.getLinkProperties(cm.activeNetwork)
        Core.setDefaultRouteInterface(active?.interfaceName ?: "")
        val dns = active?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }.orEmpty()
        Core.setDNSServers(dns.joinToString(","))
    }

    private fun registerNetworkCallback() {
        if (callbackRegistered) return
        callbackRegistered = true
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                pushNetworkInfo()
                onNetworkAvailable?.invoke()
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = pushNetworkInfo()
            override fun onLost(network: Network) = pushNetworkInfo()
        })
    }
}
