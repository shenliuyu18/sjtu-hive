//go:build windows

package app

import (
	"os/exec"
	"syscall"
)

// hideConsoleWindow stops console-subsystem helpers (git, taskkill, code) from
// flashing a black console window when the GUI app spawns them.
func hideConsoleWindow(command *exec.Cmd) {
	if command == nil {
		return
	}
	if command.SysProcAttr == nil {
		command.SysProcAttr = &syscall.SysProcAttr{}
	}
	command.SysProcAttr.HideWindow = true
	command.SysProcAttr.CreationFlags |= 0x08000000 // CREATE_NO_WINDOW
}
