//go:build darwin && cgo

package app

/*
#cgo LDFLAGS: -framework Security -framework CoreFoundation
#include <Security/Security.h>
#include <Security/SecRandom.h>
#include <CoreFoundation/CoreFoundation.h>
#include <string.h>

static OSStatus hive_master_key(unsigned char *out) {
	CFStringRef service = CFStringCreateWithCString(NULL, "com.sjtuhive.desktop", kCFStringEncodingUTF8);
	CFStringRef account = CFStringCreateWithCString(NULL, "master-key", kCFStringEncodingUTF8);
	if (service == NULL || account == NULL) {
		return errSecAllocate;
	}
	const void *qkeys[] = { kSecClass, kSecAttrService, kSecAttrAccount, kSecReturnData, kSecMatchLimit };
	const void *qvals[] = { kSecClassGenericPassword, service, account, kCFBooleanTrue, kSecMatchLimitOne };
	CFDictionaryRef query = CFDictionaryCreate(NULL, qkeys, qvals, 5,
		&kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
	CFTypeRef result = NULL;
	OSStatus status = SecItemCopyMatching(query, &result);
	CFRelease(query);
	if (status == errSecSuccess && result != NULL) {
		CFDataRef data = (CFDataRef)result;
		status = errSecDecode;
		if (CFDataGetLength(data) == 32) {
			memcpy(out, CFDataGetBytePtr(data), 32);
			status = errSecSuccess;
		}
		CFRelease(result);
		CFRelease(service);
		CFRelease(account);
		return status;
	}
	if (result != NULL) {
		CFRelease(result);
	}

	unsigned char key[32];
	if (SecRandomCopyBytes(kSecRandomDefault, 32, key) != errSecSuccess) {
		CFRelease(service);
		CFRelease(account);
		return errSecAllocate;
	}
	CFDataRef data = CFDataCreate(NULL, key, 32);
	const void *akeys[] = { kSecClass, kSecAttrService, kSecAttrAccount, kSecValueData };
	const void *avals[] = { kSecClassGenericPassword, service, account, data };
	CFDictionaryRef add = CFDictionaryCreate(NULL, akeys, avals, 4,
		&kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
	status = SecItemAdd(add, NULL);
	CFRelease(add);
	CFRelease(data);
	if (status == errSecSuccess) {
		memcpy(out, key, 32);
	}
	CFRelease(service);
	CFRelease(account);
	return status;
}
*/
import "C"

import (
	"fmt"
	"unsafe"
)

func keychainMasterKey() ([]byte, error) {
	key := make([]byte, 32)
	status := C.hive_master_key((*C.uchar)(unsafe.Pointer(&key[0])))
	if status != 0 {
		return nil, fmt.Errorf("访问 macOS 钥匙串失败（OSStatus %d）", int(status))
	}
	return key, nil
}

func masterKey() ([]byte, error) {
	// 一旦走过文件密钥兜底（例如钥匙串被锁定），后续必须继续使用同一把，
	// 否则新旧密文会解不开。
	if hasFileSecretKey() {
		return fileSecretKey()
	}
	key, err := keychainMasterKey()
	if err != nil {
		return fileSecretKey()
	}
	return key, nil
}

func protectBytes(plain []byte) ([]byte, error) {
	key, err := masterKey()
	if err != nil {
		return nil, err
	}
	return sealWithKey(key, plain)
}

func unprotectBytes(sealed []byte) ([]byte, error) {
	key, err := masterKey()
	if err != nil {
		return nil, err
	}
	return openWithKey(key, sealed)
}
