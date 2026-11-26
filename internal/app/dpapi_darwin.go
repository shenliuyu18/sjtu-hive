//go:build darwin

package app

// macOS 上不使用钥匙串：自签名应用每次构建指纹不同，SecItemAdd/Copy
// 会弹系统密码框，普通用户无法继续。这里改用 0600 权限的文件主密钥，
// 仍然以 AES-256-GCM 加密凭据，且只有当前用户可读。
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
