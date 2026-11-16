package core

// Integration tests: run against an in-process SSH server (PTY, env, shell,
// window-change, sftp subsystem, direct-tcpip) so the full client path is exercised
// without a network or a device. Tailscale itself is not exercised here (UseTailnet=false).

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/binary"
	"encoding/pem"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

type testServer struct {
	addr    string
	hostKey ssh.PublicKey
	winch   chan [2]int
	env     chan string
	dir     string
}

func startServer(t *testing.T, authorized ssh.PublicKey) *testServer {
	t.Helper()
	_, hk, _ := ed25519.GenerateKey(rand.Reader)
	hostSigner, err := ssh.NewSignerFromKey(hk)
	if err != nil {
		t.Fatal(err)
	}
	cfg := &ssh.ServerConfig{
		PublicKeyCallback: func(_ ssh.ConnMetadata, k ssh.PublicKey) (*ssh.Permissions, error) {
			if bytes.Equal(k.Marshal(), authorized.Marshal()) {
				return nil, nil
			}
			return nil, io.EOF
		},
	}
	cfg.AddHostKey(hostSigner)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	ts := &testServer{
		addr:    ln.Addr().String(),
		hostKey: hostSigner.PublicKey(),
		winch:   make(chan [2]int, 4),
		env:     make(chan string, 8),
		dir:     t.TempDir(),
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go ts.serve(c, cfg)
		}
	}()
	return ts
}

func (ts *testServer) serve(c net.Conn, cfg *ssh.ServerConfig) {
	_, chans, reqs, err := ssh.NewServerConn(c, cfg)
	if err != nil {
		return
	}
	go ssh.DiscardRequests(reqs)
	for nc := range chans {
		switch nc.ChannelType() {
		case "session":
			ch, creqs, err := nc.Accept()
			if err != nil {
				continue
			}
			go ts.session(ch, creqs)
		case "direct-tcpip":
			var p struct {
				Host       string
				Port       uint32
				OrigHost   string
				OrigPort   uint32
			}
			if err := ssh.Unmarshal(nc.ExtraData(), &p); err != nil {
				nc.Reject(ssh.ConnectionFailed, "bad payload")
				continue
			}
			target, err := net.Dial("tcp", net.JoinHostPort(p.Host, fmtInt(int(p.Port))))
			if err != nil {
				nc.Reject(ssh.ConnectionFailed, err.Error())
				continue
			}
			ch, creqs, err := nc.Accept()
			if err != nil {
				target.Close()
				continue
			}
			go ssh.DiscardRequests(creqs)
			go func() { io.Copy(ch, target); ch.Close() }()
			go func() { io.Copy(target, ch); target.Close() }()
		default:
			nc.Reject(ssh.UnknownChannelType, "nope")
		}
	}
}

func (ts *testServer) session(ch ssh.Channel, reqs <-chan *ssh.Request) {
	for r := range reqs {
		switch r.Type {
		case "pty-req", "env":
			if r.Type == "env" {
				var kv struct{ K, V string }
				ssh.Unmarshal(r.Payload, &kv)
				ts.env <- kv.K + "=" + kv.V
			}
			r.Reply(true, nil)
		case "shell", "exec":
			r.Reply(true, nil)
			if r.Type == "exec" {
				var c struct{ Cmd string }
				ssh.Unmarshal(r.Payload, &c)
				if strings.HasPrefix(c.Cmd, "say ") { // one-shot command for Session.Exec
					ch.Write([]byte(strings.TrimPrefix(c.Cmd, "say ") + "\n"))
					ch.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{3}))
					ch.Close()
					return
				}
			}
			go func() {
				ch.Write([]byte("ready\r\n"))
				buf := make([]byte, 1024)
				for {
					n, err := ch.Read(buf)
					if n > 0 {
						ch.Write(append([]byte("echo:"), buf[:n]...))
					}
					if err != nil {
						return
					}
				}
			}()
		case "window-change":
			if len(r.Payload) >= 8 {
				cols := binary.BigEndian.Uint32(r.Payload[0:4])
				rows := binary.BigEndian.Uint32(r.Payload[4:8])
				ts.winch <- [2]int{int(cols), int(rows)}
			}
			if r.WantReply {
				r.Reply(true, nil)
			}
		case "subsystem":
			var s struct{ Name string }
			ssh.Unmarshal(r.Payload, &s)
			if s.Name != "sftp" {
				r.Reply(false, nil)
				continue
			}
			r.Reply(true, nil)
			srv, err := sftp.NewServer(ch, sftp.WithServerWorkingDirectory(ts.dir))
			if err != nil {
				ch.Close()
				return
			}
			go func() { srv.Serve(); ch.Close() }()
		default:
			if r.WantReply {
				r.Reply(false, nil)
			}
		}
	}
}

func fmtInt(i int) string {
	if i == 0 {
		return "0"
	}
	var b []byte
	for i > 0 {
		b = append([]byte{byte('0' + i%10)}, b...)
		i /= 10
	}
	return string(b)
}

// ---- helpers ----

type verifier struct {
	accept bool
	mu     sync.Mutex
	calls  []string
}

func (v *verifier) Verify(hostPort, keyType, fp, b64 string) bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.calls = append(v.calls, keyType+" "+fp)
	return v.accept
}

type collector struct {
	mu     sync.Mutex
	buf    bytes.Buffer
	closed chan string
}

func newCollector() *collector { return &collector{closed: make(chan string, 1)} }
func (c *collector) OnData(b []byte) {
	c.mu.Lock()
	c.buf.Write(b)
	c.mu.Unlock()
}
func (c *collector) OnClosed(reason string) { c.closed <- reason }
func (c *collector) waitFor(t *testing.T, s string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		c.mu.Lock()
		ok := strings.Contains(c.buf.String(), s)
		c.mu.Unlock()
		if ok {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	t.Fatalf("timeout waiting for %q; got %q", s, c.buf.String())
}

func splitHostPort(t *testing.T, addr string) (string, int) {
	h, p, err := net.SplitHostPort(addr)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, r := range p {
		n = n*10 + int(r-'0')
	}
	return h, n
}

func connect(t *testing.T, ts *testServer, kp *KeyPair, v HostKeyVerifier, l SessionListener) (*Session, error) {
	h, p := splitHostPort(t, ts.addr)
	return Connect(nil, &ConnectOptions{
		Host: h, Port: p, User: "me", PrivateKey: kp.PrivatePEM,
		Cols: 90, Rows: 30, Env: "MOBILE_TERM=1",
	}, v, l)
}

func pub(t *testing.T, kp *KeyPair) ssh.PublicKey {
	k, _, _, _, err := ssh.ParseAuthorizedKey([]byte(kp.PublicKey))
	if err != nil {
		t.Fatal(err)
	}
	return k
}

// ---- tests ----

func TestKeysRoundTrip(t *testing.T) {
	kp, err := GenerateEd25519("phone")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(kp.PublicKey, "ssh-ed25519 ") || !strings.HasSuffix(kp.PublicKey, " phone") {
		t.Fatalf("public key line: %q", kp.PublicKey)
	}
	again, err := ImportKey(kp.PrivatePEM, "", "phone")
	if err != nil {
		t.Fatal(err)
	}
	if again.Fingerprint != kp.Fingerprint {
		t.Fatalf("fingerprint mismatch %s vs %s", again.Fingerprint, kp.Fingerprint)
	}

	// Encrypted key: passphrase required, then accepted.
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	blk, err := ssh.MarshalPrivateKeyWithPassphrase(priv, "enc", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	enc := pem.EncodeToMemory(blk)
	if _, err := ImportKey(enc, "", "x"); err == nil || err.Error() != "PASSPHRASE_REQUIRED" {
		t.Fatalf("want PASSPHRASE_REQUIRED, got %v", err)
	}
	if _, err := ImportKey(enc, "wrong", "x"); err == nil {
		t.Fatal("wrong passphrase accepted")
	}
	if _, err := ImportKey(enc, "secret", "x"); err != nil {
		t.Fatal(err)
	}
}

func TestSessionEndToEnd(t *testing.T) {
	kp, _ := GenerateEd25519("t")
	ts := startServer(t, pub(t, kp))
	v := &verifier{accept: true}
	c := newCollector()

	s, err := connect(t, ts, kp, v, c)
	if err != nil {
		t.Fatal(err)
	}
	if len(v.calls) != 1 || !strings.Contains(v.calls[0], "SHA256:") {
		t.Fatalf("verifier calls: %v", v.calls)
	}
	select {
	case e := <-ts.env:
		if e != "MOBILE_TERM=1" {
			t.Fatalf("env %q", e)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("env not sent")
	}

	c.waitFor(t, "ready")
	if err := s.Write([]byte("hello\r")); err != nil {
		t.Fatal(err)
	}
	c.waitFor(t, "echo:hello")

	if err := s.Resize(120, 40); err != nil {
		t.Fatal(err)
	}
	select {
	case w := <-ts.winch:
		if w != [2]int{120, 40} {
			t.Fatalf("winch %v", w)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no window-change")
	}

	// SFTP upload (image for Claude)
	data := []byte("\x89PNG fake image")
	p, err := s.UploadFile("my shot.png", data)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(p, ".cache/panoterm/") || !strings.HasSuffix(p, "my_shot.png") {
		t.Fatalf("remote path %q", p)
	}
	matches, _ := filepath.Glob(filepath.Join(ts.dir, ".cache", "panoterm", "*my_shot.png"))
	if len(matches) != 1 {
		t.Fatalf("uploaded file not found under %s", ts.dir)
	}
	got, _ := os.ReadFile(matches[0])
	if !bytes.Equal(got, data) {
		t.Fatal("uploaded content differs")
	}

	// Local port forward to a TCP echo server "on the remote side".
	echoLn, _ := net.Listen("tcp", "127.0.0.1:0")
	defer echoLn.Close()
	go func() {
		for {
			cc, err := echoLn.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(cc, cc); cc.Close() }()
		}
	}()
	_, echoPort := splitHostPort(t, echoLn.Addr().String())
	local, err := s.ForwardLocal("127.0.0.1", echoPort)
	if err != nil {
		t.Fatal(err)
	}
	fc, err := net.Dial("tcp", "127.0.0.1:"+fmtInt(local))
	if err != nil {
		t.Fatal(err)
	}
	fc.Write([]byte("ping"))
	buf := make([]byte, 4)
	fc.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := io.ReadFull(fc, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("forward echo: %q %v", buf, err)
	}
	fc.Close()
	s.StopForward(local)
	if _, err := net.DialTimeout("tcp", "127.0.0.1:"+fmtInt(local), time.Second); err == nil {
		t.Fatal("forward still listening after StopForward")
	}

	// Side-channel exec (autocomplete): output comes back, non-zero exit is not an error,
	// and nothing leaks into the interactive terminal.
	before := func() string { c.mu.Lock(); defer c.mu.Unlock(); return c.buf.String() }()
	got2, err := s.Exec("say service/ server-logs/", 3000)
	if err != nil || got2 != "service/ server-logs/\n" {
		t.Fatalf("exec: %q %v", got2, err)
	}
	time.Sleep(100 * time.Millisecond)
	if after := func() string { c.mu.Lock(); defer c.mu.Unlock(); return c.buf.String() }(); after != before {
		t.Fatal("exec output leaked into the terminal stream")
	}

	s.Close()
	select {
	case r := <-c.closed:
		if r == "" {
			t.Fatal("empty close reason")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("OnClosed not called")
	}
}

func TestHostKeyRejected(t *testing.T) {
	kp, _ := GenerateEd25519("t")
	ts := startServer(t, pub(t, kp))
	_, err := connect(t, ts, kp, &verifier{accept: false}, newCollector())
	if err == nil || !strings.Contains(err.Error(), "host key rejected") {
		t.Fatalf("want host key rejected, got %v", err)
	}
}

func TestWrongKeyRejected(t *testing.T) {
	good, _ := GenerateEd25519("good")
	bad, _ := GenerateEd25519("bad")
	ts := startServer(t, pub(t, good))
	if _, err := connect(t, ts, bad, &verifier{accept: true}, newCollector()); err == nil {
		t.Fatal("unauthorized key was accepted")
	}
}

// fakeHW mimics the Kotlin Keystore signer with a software ECDSA key.
type fakeHW struct{ k *ecdsa.PrivateKey }

func (f fakeHW) SignSHA256(data []byte) ([]byte, error) {
	h := sha256.Sum256(data)
	return ecdsa.SignASN1(rand.Reader, f.k, h[:])
}

func TestHardwareKeyConnect(t *testing.T) {
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	spki, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
	kp, err := HardwarePublicKey(spki, "strongbox")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(kp.PublicKey, "ecdsa-sha2-nistp256 ") {
		t.Fatalf("pub line %q", kp.PublicKey)
	}
	ts := startServer(t, pub(t, kp))
	h, p := splitHostPort(t, ts.addr)
	c := newCollector()
	s, err := ConnectHardware(nil, &ConnectOptions{Host: h, Port: p, User: "me", Cols: 80, Rows: 24}, spki, fakeHW{k}, &verifier{accept: true}, c)
	if err != nil {
		t.Fatal(err)
	}
	c.waitFor(t, "ready")
	s.Close()
}

func TestSetInterfaces(t *testing.T) {
	SetInterfaces("wlan0\t30\t1500\tum\t192.168.0.5/24,fe80::1/64\nlo\t1\t65536\tul\t127.0.0.1/8\n")
	ifs, _ := getInterfaces()
	if len(ifs) != 2 || ifs[0].Name != "wlan0" || len(ifs[0].AltAddrs) != 2 || !ifs[1].IsLoopback() {
		t.Fatalf("interfaces: %+v", ifs)
	}
	SetDNSServers("8.8.8.8, bogus ,1.1.1.1")
	envMu.Lock()
	defer envMu.Unlock()
	if len(dnsServers) != 2 {
		t.Fatalf("dns: %v", dnsServers)
	}
}
