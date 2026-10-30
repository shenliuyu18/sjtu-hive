package app

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
)

var ErrSecretNotFound = errors.New("凭据不存在")

type SecretStore struct {
	dir string
}

func NewSecretStore(dir string) (*SecretStore, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	return &SecretStore{dir: dir}, nil
}

func (s *SecretStore) path(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" || strings.ContainsAny(name, `/\..:`) {
		return "", errors.New("凭据名称无效")
	}
	return filepath.Join(s.dir, name+".bin"), nil
}

func (s *SecretStore) Save(name string, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return s.SaveRaw(name, data)
}

func (s *SecretStore) SaveRaw(name string, data []byte) error {
	path, err := s.path(name)
	if err != nil {
		return err
	}
	sealed, err := protectBytes(data)
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(s.dir, name+".*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if _, err := temp.Write(sealed); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempName, path)
}

func (s *SecretStore) Load(name string, target any) (bool, error) {
	data, ok, err := s.LoadRaw(name)
	if err != nil || !ok {
		return ok, err
	}
	if err := json.Unmarshal(data, target); err != nil {
		return true, err
	}
	return true, nil
}

func (s *SecretStore) LoadRaw(name string) ([]byte, bool, error) {
	path, err := s.path(name)
	if err != nil {
		return nil, false, err
	}
	sealed, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	plain, err := unprotectBytes(sealed)
	if err != nil {
		return nil, true, err
	}
	return plain, true, nil
}

func (s *SecretStore) Delete(name string) error {
	path, err := s.path(name)
	if err != nil {
		return err
	}
	err = os.Remove(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

func (s *SecretStore) Exists(name string) bool {
	path, err := s.path(name)
	if err != nil {
		return false
	}
	_, err = os.Stat(path)
	return err == nil
}
