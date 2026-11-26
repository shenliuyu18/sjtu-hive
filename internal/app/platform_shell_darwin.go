//go:build darwin

package app

import "os/exec"

func openTarget(path string) error {
	return exec.Command("open", path).Start()
}

func openDirectory(path string) error {
	return exec.Command("open", path).Start()
}

func revealTarget(path string) error {
	return exec.Command("open", "-R", path).Start()
}
