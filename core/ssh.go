package core

// SSH sessions over the embedded tailnet (or a direct TCP connection).

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

// HostKeyVerifier is called during the handshake. It may block (e.g. while a
// dialog is shown) for up to ~2 minutes. Return true to accept the key.
type HostKeyVerifier interface {
	Verify(hostPort, keyType, fingerprint, keyBase64 string) bool
}

// SessionListener receives terminal output and the close event.
type SessionListener interface {
	OnData(data []byte)
	OnClosed(reason string)
}

// ConnectOptions is filled in from Kotlin.
type ConnectOptions struct {
	Host       string
	Port       int
	User       string
	PrivateKey []byte // OpenSSH/PEM private key, unencrypted
	UseTailnet bool
	Cols       int
	Rows       int
	Command    string // empty = login shell
	Env        string // "KEY=VALUE" lines, sent with SSH setenv (server AcceptEnv permitting)
}

type Session struct {
	client    *ssh.Client
	sess      *ssh.Session
	stdin     io.WriteCloser
	l         SessionListener
	done      chan struct{}
	closeOnce sync.Once
	writeMu   sync.Mutex

	sftpMu     sync.Mutex
	sftpClient *sftp.Client // shared, opened on first use (files.go)
}

func Connect(n *Node, o *ConnectOptions, v HostKeyVerifier, l SessionListener) (*Session, error) {
	if o == nil {
		return nil, errors.New("nil options")
	}
	signer, err := ssh.ParsePrivateKey(o.PrivateKey)
	if err != nil {
		return nil, fmt.Errorf("private key: %w", err)
	}
	return openSession(n, o, signer, v, l)
}

// ConnectHardware authenticates with a non-exportable key held by Android Keystore /
// StrongBox. publicKeySPKI is the X.509 SubjectPublicKeyInfo of the key; hs signs.
func ConnectHardware(n *Node, o *ConnectOptions, publicKeySPKI []byte, hs HardwareSigner, v HostKeyVerifier, l SessionListener) (*Session, error) {
	if o == nil {
		return nil, errors.New("nil options")
	}
	signer, err := newHardwareSigner(publicKeySPKI, hs)
	if err != nil {
		return nil, err
	}
	return openSession(n, o, signer, v, l)
}

func openSession(n *Node, o *ConnectOptions, signer ssh.Signer, v HostKeyVerifier, l SessionListener) (*Session, error) {
	if l == nil {
		return nil, errors.New("nil listener")
	}
	var err error
	port := o.Port
	if port <= 0 {
		port = 22
	}
	addr := net.JoinHostPort(o.Host, strconv.Itoa(port))

	cfg := &ssh.ClientConfig{
		User: o.User,
		Auth: []ssh.AuthMethod{ssh.PublicKeys(signer)},
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			if v == nil {
				return errors.New("no host key verifier")
			}
			if !v.Verify(addr, key.Type(), ssh.FingerprintSHA256(key), base64.StdEncoding.EncodeToString(key.Marshal())) {
				return errors.New("host key rejected")
			}
			return nil
		},
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	var conn net.Conn
	if o.UseTailnet {
		if n == nil {
			return nil, errors.New("tailscale node is not available")
		}
		conn, err = n.dial(ctx, addr)
	} else {
		var d net.Dialer
		conn, err = d.DialContext(ctx, "tcp", addr)
	}
	if err != nil {
		return nil, fmt.Errorf("connect %s: %w", addr, err)
	}

	// Leave room for the user to read the host key dialog.
	conn.SetDeadline(time.Now().Add(150 * time.Second))
	c, chans, reqs, err := ssh.NewClientConn(conn, addr, cfg)
	if err != nil {
		conn.Close()
		return nil, err
	}
	conn.SetDeadline(time.Time{})
	client := ssh.NewClient(c, chans, reqs)

	sess, err := client.NewSession()
	if err != nil {
		client.Close()
		return nil, err
	}
	for _, kv := range strings.Split(o.Env, "\n") {
		if k, val, ok := strings.Cut(strings.TrimSpace(kv), "="); ok && k != "" {
			_ = sess.Setenv(k, val) // usually rejected unless AcceptEnv allows it
		}
	}
	cols, rows := o.Cols, o.Rows
	if cols <= 0 {
		cols = 80
	}
	if rows <= 0 {
		rows = 24
	}
	modes := ssh.TerminalModes{ssh.ECHO: 1, ssh.TTY_OP_ISPEED: 115200, ssh.TTY_OP_OSPEED: 115200}
	if err := sess.RequestPty("xterm-256color", rows, cols, modes); err != nil {
		client.Close()
		return nil, fmt.Errorf("pty: %w", err)
	}
	stdin, err := sess.StdinPipe()
	if err != nil {
		client.Close()
		return nil, err
	}
	stdout, err := sess.StdoutPipe()
	if err != nil {
		client.Close()
		return nil, err
	}
	if o.Command != "" {
		err = sess.Start(o.Command)
	} else {
		err = sess.Shell()
	}
	if err != nil {
		client.Close()
		return nil, fmt.Errorf("start: %w", err)
	}

	s := &Session{client: client, sess: sess, stdin: stdin, l: l, done: make(chan struct{})}
	go s.readLoop(stdout)
	go s.keepAlive()
	go func() {
		err := sess.Wait()
		if err != nil {
			s.finish("session ended: " + err.Error())
		} else {
			s.finish("session ended")
		}
	}()
	return s, nil
}

func (s *Session) readLoop(r io.Reader) {
	buf := make([]byte, 32*1024)
	for {
		k, err := r.Read(buf)
		if k > 0 {
			b := make([]byte, k)
			copy(b, buf[:k])
			s.l.OnData(b)
		}
		if err != nil {
			return
		}
	}
}

func (s *Session) keepAlive() {
	t := time.NewTicker(15 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-s.done:
			return
		case <-t.C:
		}
		errc := make(chan error, 1)
		go func() {
			_, _, err := s.client.SendRequest("keepalive@openssh.com", true, nil)
			errc <- err
		}()
		select {
		case err := <-errc:
			if err != nil {
				s.finish("connection lost: " + err.Error())
				return
			}
		case <-time.After(20 * time.Second):
			s.finish("connection lost (keepalive timeout)")
			return
		case <-s.done:
			return
		}
	}
}

func (s *Session) finish(reason string) {
	s.closeOnce.Do(func() {
		close(s.done)
		s.closeForwards()
		s.closeSFTP()
		s.sess.Close()
		s.client.Close()
		s.l.OnClosed(reason)
	})
}

// Write sends user input to the remote PTY.
func (s *Session) Write(b []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	_, err := s.stdin.Write(b)
	return err
}

// Resize changes the remote PTY size.
func (s *Session) Resize(cols, rows int) error {
	if cols <= 0 || rows <= 0 {
		return nil
	}
	return s.sess.WindowChange(rows, cols)
}

func (s *Session) Close() {
	s.finish("closed")
}
