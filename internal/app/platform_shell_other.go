//go:build !windows && !darwin

package app

import (
	"os/exec"
	"path/filepath"
)

func openTarget(path string) error {
	return exec.Command("xdg-open", path).Start()
}

func openDirectory(path string) error {
	return exec.Command("xdg-open", path).Start()
}

func revealTarget(path string) error {
	return exec.Command("xdg-open", filepath.Dir(path)).Start()
}
