package core

// Hardware-backed SSH keys: the private key never leaves Android Keystore /
// StrongBox. Go only sees the public key and asks Kotlin to sign.

import (
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/asn1"
	"errors"
	"fmt"
	"io"
	"math/big"
	"strings"

	"golang.org/x/crypto/ssh"
)

// HardwareSigner is implemented in Kotlin with java.security.Signature
// ("SHA256withECDSA") over a Keystore EC P-256 key. It receives the raw data
// to sign and returns an ASN.1 DER ECDSA signature.
type HardwareSigner interface {
	SignSHA256(data []byte) ([]byte, error)
}

type hwSigner struct {
	pub ssh.PublicKey
	hs  HardwareSigner
}

func newHardwareSigner(spki []byte, hs HardwareSigner) (ssh.Signer, error) {
	if hs == nil {
		return nil, errors.New("nil hardware signer")
	}
	pub, err := parseECDSAPublic(spki)
	if err != nil {
		return nil, err
	}
	return &hwSigner{pub: pub, hs: hs}, nil
}

func parseECDSAPublic(spki []byte) (ssh.PublicKey, error) {
	k, err := x509.ParsePKIXPublicKey(spki)
	if err != nil {
		return nil, fmt.Errorf("public key: %w", err)
	}
	ec, ok := k.(*ecdsa.PublicKey)
	if !ok || ec.Curve.Params().BitSize != 256 {
		return nil, errors.New("hardware key must be EC P-256")
	}
	return ssh.NewPublicKey(ec)
}

func (s *hwSigner) PublicKey() ssh.PublicKey { return s.pub }

func (s *hwSigner) Sign(_ io.Reader, data []byte) (*ssh.Signature, error) {
	der, err := s.hs.SignSHA256(data)
	if err != nil {
		return nil, fmt.Errorf("hardware sign: %w", err)
	}
	var sig struct{ R, S *big.Int }
	if _, err := asn1.Unmarshal(der, &sig); err != nil {
		return nil, fmt.Errorf("hardware signature: %w", err)
	}
	return &ssh.Signature{
		Format: ssh.KeyAlgoECDSA256,
		Blob:   ssh.Marshal(struct{ R, S *big.Int }{sig.R, sig.S}),
	}, nil
}

// HardwarePublicKey returns the authorized_keys line and fingerprint for a Keystore key.
func HardwarePublicKey(spki []byte, comment string) (*KeyPair, error) {
	pub, err := parseECDSAPublic(spki)
	if err != nil {
		return nil, err
	}
	line := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(pub)))
	if c := strings.TrimSpace(comment); c != "" {
		line += " " + c
	}
	return &KeyPair{PublicKey: line, KeyType: pub.Type(), Fingerprint: ssh.FingerprintSHA256(pub)}, nil
}
