//go:build windows

package app

import (
	"os"
	"os/exec"
	"strings"
)

func availableShells() []ShellInfo {
	result := []ShellInfo{}
	candidates := []struct {
		name string
		path string
	}{
		{"Git Bash", `C:\Program Files\Git\bin\bash.exe`},
		{"Git Bash", `C:\Program Files\Git\usr\bin\bash.exe`},
		{"Bash", `C:\Program Files\Git\bin\bash.exe`},
	}
	for _, candidate := range candidates {
		if info, err := os.Stat(candidate.path); err == nil && !info.IsDir() {
			already := false
			for _, item := range result {
				if strings.EqualFold(item.Path, candidate.path) {
					already = true
					break
				}
			}
			if !already {
				result = append(result, ShellInfo{Name: candidate.name, Path: candidate.path})
			}
		}
	}
	if path, err := exec.LookPath("bash.exe"); err == nil && !strings.Contains(strings.ToLower(path), "system32") {
		result = append(result, ShellInfo{Name: "Bash", Path: path})
	}
	for _, name := range []string{"pwsh.exe", "powershell.exe"} {
		if path, err := exec.LookPath(name); err == nil {
			result = append(result, ShellInfo{Name: strings.TrimSuffix(name, ".exe"), Path: path})
		}
	}
	return result
}

func shellCommand() (string, []string, string, error) {
	if info, err := os.Stat(`C:\Program Files\Git\bin\bash.exe`); err == nil && !info.IsDir() {
		return `C:\Program Files\Git\bin\bash.exe`, []string{"-i"}, "Git Bash", nil
	}
	if info, err := os.Stat(`C:\Program Files\Git\usr\bin\bash.exe`); err == nil && !info.IsDir() {
		return `C:\Program Files\Git\usr\bin\bash.exe`, []string{"-i"}, "Bash", nil
	}
	if path, err := exec.LookPath("bash.exe"); err == nil && !strings.Contains(strings.ToLower(path), "system32") {
		return path, []string{"-i"}, "Bash", nil
	}
	if path, err := exec.LookPath("pwsh.exe"); err == nil {
		return path, []string{"-NoLogo"}, "PowerShell", nil
	}
	if path, err := exec.LookPath("powershell.exe"); err == nil {
		return path, []string{"-NoLogo"}, "PowerShell", nil
	}
	return "", nil, "", errNoShell
}
