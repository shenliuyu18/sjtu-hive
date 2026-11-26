//go:build windows

package app

import "os/exec"

func openTarget(path string) error {
	return exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", path).Start()
}

func openDirectory(path string) error {
	return exec.Command("explorer.exe", path).Start()
}

func revealTarget(path string) error {
	return exec.Command("explorer.exe", "/select,"+path).Start()
}
