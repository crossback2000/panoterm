package core

// SSH key generation and import. Private keys returned here are unencrypted;
// the Kotlin side immediately wraps them with an Android Keystore key.

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/pem"
	"errors"
	"strings"

	"golang.org/x/crypto/ssh"
)

type KeyPair struct {
	PrivatePEM  []byte // OpenSSH private key (unencrypted)
	PublicKey   string // authorized_keys line
	KeyType     string
	Fingerprint string // SHA256:...
}

// GenerateEd25519 creates a new Ed25519 key pair.
func GenerateEd25519(comment string) (*KeyPair, error) {
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	return fromPrivate(priv, comment)
}

// ImportKey parses an OpenSSH / PEM private key. If it is encrypted and no
// passphrase is given, the error message is exactly "PASSPHRASE_REQUIRED".
func ImportKey(data []byte, passphrase, comment string) (*KeyPair, error) {
	var raw any
	var err error
	if passphrase != "" {
		raw, err = ssh.ParseRawPrivateKeyWithPassphrase(data, []byte(passphrase))
	} else {
		raw, err = ssh.ParseRawPrivateKey(data)
	}
	if err != nil {
		var pm *ssh.PassphraseMissingError
		if errors.As(err, &pm) {
			return nil, errors.New("PASSPHRASE_REQUIRED")
		}
		return nil, err
	}
	if p, ok := raw.(*ed25519.PrivateKey); ok {
		raw = *p
	}
	return fromPrivate(raw, comment)
}

func fromPrivate(k any, comment string) (*KeyPair, error) {
	blk, err := ssh.MarshalPrivateKey(k, comment)
	if err != nil {
		return nil, err
	}
	signer, err := ssh.NewSignerFromKey(k)
	if err != nil {
		return nil, err
	}
	pub := signer.PublicKey()
	line := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(pub)))
	if c := strings.TrimSpace(comment); c != "" {
		line += " " + c
	}
	return &KeyPair{
		PrivatePEM:  pem.EncodeToMemory(blk),
		PublicKey:   line,
		KeyType:     pub.Type(),
		Fingerprint: ssh.FingerprintSHA256(pub),
	}, nil
}
