//go:build darwin

package app

import (
	"bytes"
	"encoding/xml"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

const launchAgentLabel = "com.sjtuhive.desktop"

func launchAgentPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "LaunchAgents", launchAgentLabel+".plist"), nil
}

func escapeXML(value string) string {
	var buffer bytes.Buffer
	_ = xml.EscapeText(&buffer, []byte(value))
	return buffer.String()
}

func setOpenAtLogin(enabled bool) error {
	path, err := launchAgentPath()
	if err != nil {
		return err
	}
	if !enabled {
		runLaunchctl("bootout", "gui/"+strconv.Itoa(os.Getuid()), path)
		runLaunchctl("unload", "-w", path)
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	plist := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>%s</string>
  <key>ProgramArguments</key>
  <array>
    <string>%s</string>
    <string>--autostart</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>ProcessType</key><string>Interactive</string>
</dict>
</plist>
`, launchAgentLabel, escapeXML(exe))
	if err := os.WriteFile(path, []byte(plist), 0o644); err != nil {
		return err
	}
	if err := runLaunchctl("bootstrap", "gui/"+strconv.Itoa(os.Getuid()), path); err != nil {
		return runLaunchctl("load", "-w", path)
	}
	return nil
}

func runLaunchctl(args ...string) error {
	command := exec.Command("launchctl", args...)
	if output, err := command.CombinedOutput(); err != nil {
		return fmt.Errorf("launchctl %s: %s", strings.Join(args, " "), strings.TrimSpace(string(output)))
	}
	return nil
}

func openAtLoginValue() (bool, error) {
	path, err := launchAgentPath()
	if err != nil {
		return false, err
	}
	_, statErr := os.Stat(path)
	if errors.Is(statErr, os.ErrNotExist) {
		return false, nil
	}
	return statErr == nil, statErr
}

func migrateAutostartName() {}

// ApplyStartupPriority is a no-op on macOS.
func ApplyStartupPriority() {}
