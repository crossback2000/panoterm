package core

// Android-specific network plumbing.
//
// On Android 11+ Go cannot enumerate network interfaces via netlink, and there is
// no /etc/resolv.conf. Kotlin pushes both from the Java APIs (NetworkInterface,
// ConnectivityManager) through SetInterfaces / SetDNSServers.

import (
	"context"
	"net"
	"strconv"
	"strings"
	"sync"

	"tailscale.com/net/netmon"
)

var (
	envMu      sync.Mutex
	ifCached   []netmon.Interface
	dnsServers []string
)

func init() {
	netmon.RegisterInterfaceGetter(getInterfaces)

	// Mutate (not replace) the default resolver so packages that captured the
	// pointer during init also see the change.
	net.DefaultResolver.PreferGo = true
	net.DefaultResolver.Dial = dialDNS
}

func getInterfaces() ([]netmon.Interface, error) {
	envMu.Lock()
	defer envMu.Unlock()
	return append([]netmon.Interface(nil), ifCached...), nil
}

// SetInterfaces replaces the interface list. One interface per line:
//
//	name \t index \t mtu \t flags \t addr/prefix,addr/prefix
//
// flags is any combination of u (up), l (loopback), m (multicast), p (point-to-point), or "-".
func SetInterfaces(spec string) {
	var out []netmon.Interface
	for _, line := range strings.Split(spec, "\n") {
		f := strings.Split(strings.TrimRight(line, "\r"), "\t")
		if len(f) < 4 || f[0] == "" {
			continue
		}
		idx, _ := strconv.Atoi(f[1])
		mtu, _ := strconv.Atoi(f[2])
		var flags net.Flags
		if strings.Contains(f[3], "u") {
			flags |= net.FlagUp | net.FlagRunning
		}
		if strings.Contains(f[3], "l") {
			flags |= net.FlagLoopback
		}
		if strings.Contains(f[3], "m") {
			flags |= net.FlagMulticast
		}
		if strings.Contains(f[3], "p") {
			flags |= net.FlagPointToPoint
		}
		var addrs []net.Addr
		if len(f) >= 5 {
			for _, a := range strings.Split(f[4], ",") {
				ip, ipnet, err := net.ParseCIDR(strings.TrimSpace(a))
				if err != nil {
					continue
				}
				ipnet.IP = ip
				addrs = append(addrs, ipnet)
			}
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Index: idx, MTU: mtu, Name: f[0], Flags: flags},
			AltAddrs:  addrs,
		})
	}
	envMu.Lock()
	ifCached = out
	envMu.Unlock()
}

// SetDefaultRouteInterface tells netmon which interface carries the default route
// (Android does not let Go read the routing table).
func SetDefaultRouteInterface(name string) {
	if name != "" {
		setDefaultRoute(name) // netenv_android.go; no-op elsewhere (host tests)
	}
}

// SetDNSServers sets the system DNS servers (comma separated IPs).
func SetDNSServers(csv string) {
	var s []string
	for _, v := range strings.Split(csv, ",") {
		if v = strings.TrimSpace(v); v != "" && net.ParseIP(v) != nil {
			s = append(s, v)
		}
	}
	envMu.Lock()
	dnsServers = s
	envMu.Unlock()
}

func dialDNS(ctx context.Context, network, _ string) (net.Conn, error) {
	envMu.Lock()
	servers := append([]string(nil), dnsServers...)
	envMu.Unlock()
	if len(servers) == 0 {
		servers = []string{"1.1.1.1", "8.8.8.8"}
	}
	var d net.Dialer
	var lastErr error
	for _, s := range servers {
		c, err := d.DialContext(ctx, network, net.JoinHostPort(s, "53"))
		if err == nil {
			return c, nil
		}
		lastErr = err
	}
	return nil, lastErr
}
