package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestFileBrowser(t *testing.T) {
	kp, _ := GenerateEd25519("t")
	ts := startServer(t, pub(t, kp))
	s, err := connect(t, ts, kp, &verifier{accept: true}, newCollector())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	os.MkdirAll(filepath.Join(ts.dir, "src"), 0o755)
	os.WriteFile(filepath.Join(ts.dir, "b.go"), []byte("package main\n"), 0o640)
	os.WriteFile(filepath.Join(ts.dir, "A.env"), []byte("KEY=1\n"), 0o600)
	os.Symlink(filepath.Join(ts.dir, "src"), filepath.Join(ts.dir, "zlink"))

	// Listing: absolute path back, folders (and links to folders) first.
	js, err := s.ListDir(ts.dir)
	if err != nil {
		t.Fatal(err)
	}
	var l dirListing
	if err := json.Unmarshal([]byte(js), &l); err != nil {
		t.Fatal(err)
	}
	if l.Path != ts.dir {
		t.Fatalf("path %q want %q", l.Path, ts.dir)
	}
	var names []string
	for _, e := range l.Entries {
		names = append(names, e.Name)
	}
	if got := strings.Join(names, ","); got != "src,zlink,A.env,b.go" {
		t.Fatalf("order: %s", got)
	}
	if !l.Entries[1].Link || !l.Entries[1].Dir {
		t.Fatalf("symlink to dir: %+v", l.Entries[1])
	}

	// Read, then save with the read's mtime/size: allowed, mode kept.
	p := filepath.Join(ts.dir, "b.go")
	fd, err := s.ReadFile(p, 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	if string(fd.Data) != "package main\n" || fd.Mode != 0o640 {
		t.Fatalf("read: %q mode %o", fd.Data, fd.Mode)
	}
	if _, err := s.WriteFile(p, []byte("package x\n"), fd.ModTime, fd.Size); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); string(b) != "package x\n" {
		t.Fatalf("content after save: %q", b)
	}
	if st, _ := os.Stat(p); st.Mode().Perm() != 0o640 {
		t.Fatalf("mode after save: %o", st.Mode().Perm())
	}
	if m, _ := filepath.Glob(filepath.Join(ts.dir, ".b.go.panoterm-*")); len(m) != 0 {
		t.Fatalf("temp file left: %v", m)
	}

	// Someone else changed it since the read: conflict, nothing written.
	fd, _ = s.ReadFile(p, 1<<20)
	os.WriteFile(p, []byte("changed by claude\n"), 0o640)
	os.Chtimes(p, time.Now().Add(time.Hour), time.Now().Add(time.Hour))
	if _, err := s.WriteFile(p, []byte("mine\n"), fd.ModTime, fd.Size); err == nil || !strings.HasPrefix(err.Error(), ConflictPrefix) {
		t.Fatalf("expected conflict, got %v", err)
	}
	if b, _ := os.ReadFile(p); string(b) != "changed by claude\n" {
		t.Fatalf("conflicting save wrote: %q", b)
	}
	// Overwrite anyway.
	if _, err := s.WriteFile(p, []byte("mine\n"), 0, 0); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); string(b) != "mine\n" {
		t.Fatalf("forced save: %q", b)
	}

	// Saving through a symlink edits the target and keeps the link.
	os.WriteFile(filepath.Join(ts.dir, "src", "t.txt"), []byte("a"), 0o644)
	lp := filepath.Join(ts.dir, "zlink", "t.txt")
	fd, err = s.ReadFile(lp, 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.WriteFile(lp, []byte("b"), fd.ModTime, fd.Size); err != nil {
		t.Fatal(err)
	}
	if fi, _ := os.Lstat(filepath.Join(ts.dir, "zlink")); fi.Mode()&os.ModeSymlink == 0 {
		t.Fatal("symlink replaced")
	}
	if b, _ := os.ReadFile(filepath.Join(ts.dir, "src", "t.txt")); string(b) != "b" {
		t.Fatalf("target: %q", b)
	}

	// Size limit and folders.
	if _, err := s.ReadFile(p, 2); err == nil {
		t.Fatal("size limit not enforced")
	}
	if _, err := s.ReadFile(filepath.Join(ts.dir, "src"), 1<<20); err == nil {
		t.Fatal("reading a folder should fail")
	}
}
