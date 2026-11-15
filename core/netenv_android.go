//go:build android

package core

import "tailscale.com/net/netmon"

func setDefaultRoute(name string) { netmon.UpdateLastKnownDefaultRouteInterface(name) }
