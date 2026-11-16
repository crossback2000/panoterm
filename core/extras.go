package core

// Session extras: SFTP upload (images for Claude Code) and local port forwarding
// (preview a dev server running on the remote host in the phone's browser).

import (
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

// UploadFile stores data under ~/.cache/panoterm/ on the server and returns the
// absolute remote path (suitable for pasting into a Claude Code prompt).
func (s *Session) UploadFile(name string, data []byte) (string, error) {
	c, err := sftp.NewClient(s.client)
	if err != nil {
		return "", fmt.Errorf("sftp: %w", err)
	}
	defer c.Close()

	dir := ".cache/panoterm"
	if err := c.MkdirAll(dir); err != nil {
		return "", fmt.Errorf("mkdir %s: %w", dir, err)
	}
	base := path.Base(strings.ReplaceAll(name, "\\", "/"))
	base = strings.Map(func(r rune) rune {
		if r == '/' || r == ' ' || r < 0x20 {
			return '_'
		}
		return r
	}, base)
	if base == "" || base == "." {
		base = "upload"
	}
	p := path.Join(dir, fmt.Sprintf("%d-%s", time.Now().Unix(), base))
	f, err := c.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_TRUNC)
	if err != nil {
		return "", err
	}
	if _, err := f.Write(data); err != nil {
		f.Close()
		return "", err
	}
	if err := f.Close(); err != nil {
		return "", err
	}
	if abs, err := c.RealPath(p); err == nil {
		return abs, nil
	}
	return p, nil
}

// Exec runs a command on the server over a separate channel (no PTY, nothing shows
// in the terminal) and returns its stdout, capped at 256 KB. A non-zero exit status is
// not an error. Used for autocomplete lookups (directory listings, command names).
func (s *Session) Exec(command string, timeoutMs int) (string, error) {
	sess, err := s.client.NewSession()
	if err != nil {
		return "", err
	}
	defer sess.Close()
	out := &capWriter{max: 256 << 10}
	sess.Stdout = out
	done := make(chan error, 1)
	go func() { done <- sess.Run(command) }()
	if timeoutMs <= 0 {
		timeoutMs = 4000
	}
	select {
	case err := <-done:
		var exitErr *ssh.ExitError
		if err != nil && !errors.As(err, &exitErr) {
			return out.String(), err
		}
		return out.String(), nil
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		sess.Signal(ssh.SIGKILL)
		return out.String(), errors.New("timeout")
	}
}

type capWriter struct {
	mu  sync.Mutex
	buf []byte
	max int
}

func (w *capWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if room := w.max - len(w.buf); room > 0 {
		if len(p) > room {
			w.buf = append(w.buf, p[:room]...)
		} else {
			w.buf = append(w.buf, p...)
		}
	}
	return len(p), nil
}

func (w *capWriter) String() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return string(w.buf)
}

type forwards struct {
	mu sync.Mutex
	m  map[int]net.Listener
}

var fwdBySession sync.Map // *Session -> *forwards

func (s *Session) fwd() *forwards {
	v, _ := fwdBySession.LoadOrStore(s, &forwards{m: map[int]net.Listener{}})
	return v.(*forwards)
}

// ForwardLocal listens on 127.0.0.1:<random> on the phone and tunnels each
// connection to remoteHost:remotePort as seen from the SSH server.
// Returns the local port.
func (s *Session) ForwardLocal(remoteHost string, remotePort int) (int, error) {
	if remoteHost == "" {
		remoteHost = "localhost"
	}
	target := net.JoinHostPort(remoteHost, strconv.Itoa(remotePort))
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	f := s.fwd()
	f.mu.Lock()
	f.m[port] = ln
	f.mu.Unlock()

	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				rc, err := s.client.Dial("tcp", target)
				if err != nil {
					c.Close()
					return
				}
				pipe(c, rc)
			}()
		}
	}()
	return port, nil
}

// StopForward closes a forward created by ForwardLocal.
func (s *Session) StopForward(localPort int) {
	f := s.fwd()
	f.mu.Lock()
	ln := f.m[localPort]
	delete(f.m, localPort)
	f.mu.Unlock()
	if ln != nil {
		ln.Close()
	}
}

func (s *Session) closeForwards() {
	v, ok := fwdBySession.LoadAndDelete(s)
	if !ok {
		return
	}
	f := v.(*forwards)
	f.mu.Lock()
	defer f.mu.Unlock()
	for p, ln := range f.m {
		ln.Close()
		delete(f.m, p)
	}
}

func pipe(a, b net.Conn) {
	var once sync.Once
	closeBoth := func() { a.Close(); b.Close() }
	go func() { io.Copy(a, b); once.Do(closeBoth) }()
	io.Copy(b, a)
	once.Do(closeBoth)
}
