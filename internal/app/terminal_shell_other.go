//go:build !windows

package app

import (
	"os"
	"path/filepath"
)

func availableShells() []ShellInfo {
	result := []ShellInfo{}
	seen := map[string]bool{}
	add := func(name, path string) {
		if path == "" || seen[path] {
			return
		}
		info, err := os.Stat(path)
		if err != nil || info.IsDir() {
			return
		}
		seen[path] = true
		result = append(result, ShellInfo{Name: name, Path: path})
	}
	add(filepath.Base(os.Getenv("SHELL")), os.Getenv("SHELL"))
	add("Zsh", "/bin/zsh")
	add("Bash", "/bin/bash")
	add("fish", "/opt/homebrew/bin/fish")
	add("fish", "/usr/local/bin/fish")
	add("sh", "/bin/sh")
	return result
}

func shellCommand() (string, []string, string, error) {
	candidates := []string{os.Getenv("SHELL"), "/bin/zsh", "/bin/bash", "/bin/sh"}
	for _, candidate := range candidates {
		if candidate == "" {
			continue
		}
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
			return candidate, []string{"-i"}, filepath.Base(candidate), nil
		}
	}
	return "", nil, "", errNoShell
}
