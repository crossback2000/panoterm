package core

// SFTP file browser: list directories, read a file for the viewer, save edits back.
// One SFTP channel per SSH session is opened on first use and reused until the session ends.

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"sort"
	"strings"

	"github.com/pkg/sftp"
)

// ConflictPrefix starts the error message of WriteFile when the file changed on the
// server since it was read (Kotlin offers "overwrite anyway").
const ConflictPrefix = "conflict:"

// FileData is a file read for the viewer/editor.
type FileData struct {
	Path    string // absolute, symlinks resolved
	Data    []byte
	Size    int64
	ModTime int64 // unix seconds (SFTP v3 precision)
	Mode    int64 // permission bits
}

type dirEntry struct {
	Name  string `json:"name"`
	Dir   bool   `json:"dir"`
	Link  bool   `json:"link"`
	Size  int64  `json:"size"`
	MTime int64  `json:"mtime"`
	Mode  string `json:"mode"`
}

type dirListing struct {
	Path    string     `json:"path"`
	Entries []dirEntry `json:"entries"`
}

// sftpc returns the session's shared SFTP client, opening it on first use.
func (s *Session) sftpc() (*sftp.Client, error) {
	s.sftpMu.Lock()
	defer s.sftpMu.Unlock()
	select {
	case <-s.done:
		return nil, errors.New("session closed")
	default:
	}
	if s.sftpClient != nil {
		return s.sftpClient, nil
	}
	c, err := sftp.NewClient(s.client)
	if err != nil {
		return nil, fmt.Errorf("sftp: %w", err)
	}
	s.sftpClient = c
	return c, nil
}

// sftpFailed drops the shared client after an error that means the channel is gone,
// so the next call opens a fresh one.
func (s *Session) sftpFailed(c *sftp.Client, err error) {
	if err == nil {
		return
	}
	if !errors.Is(err, sftp.ErrSSHFxConnectionLost) && !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
		return
	}
	s.sftpMu.Lock()
	if s.sftpClient == c {
		s.sftpClient = nil
	}
	s.sftpMu.Unlock()
	c.Close()
}

func (s *Session) closeSFTP() {
	s.sftpMu.Lock()
	c := s.sftpClient
	s.sftpClient = nil
	s.sftpMu.Unlock()
	if c != nil {
		c.Close()
	}
}

// resolve turns "", "~", "~/x" and relative paths into absolute ones (relative = home).
func resolve(c *sftp.Client, p string) (string, error) {
	p = strings.TrimSpace(p)
	home := func() (string, error) { return c.RealPath(".") }
	switch {
	case p == "" || p == "~":
		return home()
	case strings.HasPrefix(p, "~/"):
		h, err := home()
		if err != nil {
			return "", err
		}
		return path.Join(h, p[2:]), nil
	case !strings.HasPrefix(p, "/"):
		h, err := home()
		if err != nil {
			return "", err
		}
		return path.Join(h, p), nil
	}
	return path.Clean(p), nil
}

// ListDir returns the directory as JSON: {"path": abs, "entries": [...]}, folders first.
// Symlinks to folders count as folders.
func (s *Session) ListDir(dir string) (string, error) {
	c, err := s.sftpc()
	if err != nil {
		return "", err
	}
	abs, err := resolve(c, dir)
	if err != nil {
		s.sftpFailed(c, err)
		return "", err
	}
	infos, err := c.ReadDir(abs)
	if err != nil {
		s.sftpFailed(c, err)
		return "", err
	}
	out := dirListing{Path: abs, Entries: make([]dirEntry, 0, len(infos))}
	for _, fi := range infos {
		e := dirEntry{
			Name:  fi.Name(),
			Dir:   fi.IsDir(),
			Size:  fi.Size(),
			MTime: fi.ModTime().Unix(),
			Mode:  fi.Mode().String(),
		}
		if fi.Mode()&os.ModeSymlink != 0 {
			e.Link = true
			if t, err := c.Stat(path.Join(abs, fi.Name())); err == nil {
				e.Dir = t.IsDir()
				if !e.Dir {
					e.Size = t.Size()
				}
			}
		}
		out.Entries = append(out.Entries, e)
	}
	sort.Slice(out.Entries, func(i, j int) bool {
		a, b := out.Entries[i], out.Entries[j]
		if a.Dir != b.Dir {
			return a.Dir
		}
		return strings.ToLower(a.Name) < strings.ToLower(b.Name)
	})
	b, err := json.Marshal(out)
	return string(b), err
}

// ReadFile reads a regular file of at most maxBytes (larger files are refused).
func (s *Session) ReadFile(p string, maxBytes int64) (*FileData, error) {
	c, err := s.sftpc()
	if err != nil {
		return nil, err
	}
	abs, err := resolve(c, p)
	if err != nil {
		s.sftpFailed(c, err)
		return nil, err
	}
	if real, err := c.RealPath(abs); err == nil {
		abs = real
	}
	st, err := c.Stat(abs)
	if err != nil {
		s.sftpFailed(c, err)
		return nil, err
	}
	if st.IsDir() {
		return nil, errors.New("폴더입니다")
	}
	if !st.Mode().IsRegular() {
		return nil, errors.New("일반 파일이 아닙니다")
	}
	if maxBytes > 0 && st.Size() > maxBytes {
		return nil, fmt.Errorf("파일이 너무 큽니다 (%d KB, 최대 %d KB)", st.Size()>>10, maxBytes>>10)
	}
	f, err := c.Open(abs)
	if err != nil {
		s.sftpFailed(c, err)
		return nil, err
	}
	defer f.Close()
	limit := maxBytes
	if limit <= 0 {
		limit = 8 << 20
	}
	data, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil {
		s.sftpFailed(c, err)
		return nil, err
	}
	if int64(len(data)) > limit {
		return nil, errors.New("파일이 읽는 중에 커졌습니다")
	}
	return &FileData{
		Path:    abs,
		Data:    data,
		Size:    int64(len(data)),
		ModTime: st.ModTime().Unix(),
		Mode:    int64(st.Mode().Perm()),
	}, nil
}

// WriteFile saves data to p and returns the new modification time.
//
// expectModTime/expectSize (from ReadFile) guard against overwriting a change made on the
// server in the meantime (e.g. by Claude): if the file differs, the error starts with
// ConflictPrefix. Pass expectModTime <= 0 to skip the check (overwrite anyway).
//
// The data goes to a temporary file in the same folder that is then renamed over the
// original, so a dropped connection never leaves a half-written file. The original's
// permission bits are kept. If the folder is not writable, the file is written in place.
func (s *Session) WriteFile(p string, data []byte, expectModTime, expectSize int64) (int64, error) {
	c, err := s.sftpc()
	if err != nil {
		return 0, err
	}
	abs, err := resolve(c, p)
	if err != nil {
		s.sftpFailed(c, err)
		return 0, err
	}
	// Edit the target of a symlink, not replace the link.
	if real, err := c.RealPath(abs); err == nil {
		abs = real
	}
	mode := os.FileMode(0o644)
	st, statErr := c.Stat(abs)
	switch {
	case statErr == nil:
		if !st.Mode().IsRegular() {
			return 0, errors.New("일반 파일이 아닙니다")
		}
		mode = st.Mode().Perm()
		if expectModTime > 0 && (st.ModTime().Unix() != expectModTime || st.Size() != expectSize) {
			return 0, errors.New(ConflictPrefix + " 서버에서 파일이 바뀌었습니다")
		}
	case errors.Is(statErr, os.ErrNotExist):
		if expectModTime > 0 {
			return 0, errors.New(ConflictPrefix + " 서버에서 파일이 지워졌습니다")
		}
	default:
		s.sftpFailed(c, statErr)
		return 0, statErr
	}

	if err := writeAtomic(c, abs, data, mode); err != nil {
		if errors.Is(err, errTempNotAllowed) {
			err = writeInPlace(c, abs, data)
		}
		if err != nil {
			s.sftpFailed(c, err)
			return 0, err
		}
	}
	if st2, err := c.Stat(abs); err == nil {
		return st2.ModTime().Unix(), nil
	}
	return 0, nil
}

var errTempNotAllowed = errors.New("cannot create temporary file")

func writeAtomic(c *sftp.Client, abs string, data []byte, mode os.FileMode) error {
	var rnd [6]byte
	_, _ = rand.Read(rnd[:])
	tmp := path.Join(path.Dir(abs), "."+path.Base(abs)+".panoterm-"+hex.EncodeToString(rnd[:]))
	f, err := c.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL)
	if err != nil {
		if errors.Is(err, os.ErrPermission) {
			return errTempNotAllowed
		}
		return err
	}
	_ = c.Chmod(tmp, mode) // before any data is written
	if _, err := f.Write(data); err != nil {
		f.Close()
		_ = c.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		_ = c.Remove(tmp)
		return err
	}
	// posix-rename replaces the target atomically (OpenSSH); plain SFTP rename refuses to.
	if err := c.PosixRename(tmp, abs); err != nil {
		_ = c.Remove(abs)
		if err2 := c.Rename(tmp, abs); err2 != nil {
			_ = c.Remove(tmp)
			return err2
		}
	}
	return nil
}

func writeInPlace(c *sftp.Client, abs string, data []byte) error {
	f, err := c.OpenFile(abs, os.O_WRONLY|os.O_CREATE|os.O_TRUNC)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}
