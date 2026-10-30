//go:build windows

package app

import (
	"os"
	"strings"

	"golang.org/x/sys/windows/registry"
)

// migrateAutostartName moves the Run key written under the old product name so the
// user does not end up with two entries after the rename.
func migrateAutostartName() {
	key, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`,
		registry.QUERY_VALUE|registry.SET_VALUE)
	if err != nil {
		return
	}
	defer key.Close()
	command, _, err := key.GetStringValue(LegacyAppName)
	if err != nil || command == "" {
		return
	}
	_ = key.DeleteValue(LegacyAppName)
	_ = key.SetStringValue(AppName, command)
}

func setOpenAtLogin(enabled bool) error {
	key, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()
	if !enabled {
		if err := key.DeleteValue(AppName); err != nil && err != registry.ErrNotExist {
			return err
		}
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	return key.SetStringValue(AppName, strings.Join([]string{`"` + exe + `"`, "--autostart"}, " "))
}

func openAtLoginValue() (bool, error) {
	key, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.QUERY_VALUE)
	if err != nil {
		return false, err
	}
	defer key.Close()
	_, _, err = key.GetStringValue(AppName)
	if err == registry.ErrNotExist {
		return false, nil
	}
	return err == nil, err
}
