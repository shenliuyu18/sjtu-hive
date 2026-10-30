//go:build !windows

package app

import "os/exec"

func hideConsoleWindow(command *exec.Cmd) {}
