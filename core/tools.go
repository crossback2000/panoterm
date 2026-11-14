//go:build tools

// Keeps golang.org/x/mobile/bind in go.mod so `gomobile bind` works after `go mod tidy`.
package core

import _ "golang.org/x/mobile/bind"
