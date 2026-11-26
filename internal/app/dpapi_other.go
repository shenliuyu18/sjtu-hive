//go:build !windows && !darwin

package app

func protectBytes(plain []byte) ([]byte, error) {
	key, err := fileSecretKey()
	if err != nil {
		return nil, err
	}
	return sealWithKey(key, plain)
}

func unprotectBytes(sealed []byte) ([]byte, error) {
	key, err := fileSecretKey()
	if err != nil {
		return nil, err
	}
	return openWithKey(key, sealed)
}
