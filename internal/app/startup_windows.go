//go:build windows

package app

import (
	"fmt"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strings"
	"unicode/utf16"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const autostartTaskName = "SJTU Hive"

// ApplyStartupPriority raises the process priority when launched by the logon
// task so first paint does not compete with other startup apps.
func ApplyStartupPriority() {
	autostart := false
	for _, arg := range os.Args[1:] {
		if strings.EqualFold(arg, "--autostart") {
			autostart = true
			break
		}
	}
	if !autostart {
		return
	}
	handle, err := windows.GetCurrentProcess()
	if err != nil {
		return
	}
	_ = windows.SetPriorityClass(handle, windows.ABOVE_NORMAL_PRIORITY_CLASS)
}

// migrateAutostartName clears entries written under the legacy product name and
// the old Run-key mechanism, which duplicated the newer logon task.
func migrateAutostartName() {
	_ = deleteRunValue(LegacyAppName)
}

func runKey() (registry.Key, error) {
	return registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`,
		registry.QUERY_VALUE|registry.SET_VALUE)
}

func deleteRunValue(name string) error {
	key, err := runKey()
	if err != nil {
		return err
	}
	defer key.Close()
	if err := key.DeleteValue(name); err != nil && err != registry.ErrNotExist {
		return err
	}
	return nil
}

func setOpenAtLogin(enabled bool) error {
	if !enabled {
		_ = deleteRunValue(AppName)
		_ = deleteRunValue(LegacyAppName)
		return deleteAutostartTask()
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	// Remove any Run-key entry so the logon task is the only launcher.
	_ = deleteRunValue(AppName)
	_ = deleteRunValue(LegacyAppName)
	if err := createAutostartTask(exe); err == nil {
		return nil
	}
	// Fall back to the classic Run key when the task scheduler is unavailable.
	key, err := runKey()
	if err != nil {
		return err
	}
	defer key.Close()
	return key.SetStringValue(AppName, fmt.Sprintf("%q --autostart", exe))
}

func openAtLoginValue() (bool, error) {
	if autostartTaskExists() {
		return true, nil
	}
	key, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`,
		registry.QUERY_VALUE)
	if err != nil {
		return false, err
	}
	defer key.Close()
	if _, _, err := key.GetStringValue(AppName); err == nil {
		return true, nil
	} else if err != registry.ErrNotExist {
		return false, err
	}
	_, _, err = key.GetStringValue(LegacyAppName)
	if err == registry.ErrNotExist {
		return false, nil
	}
	return err == nil, err
}

func autostartTaskExists() bool {
	command := exec.Command("schtasks", "/Query", "/TN", autostartTaskName)
	hideConsoleWindow(command)
	return command.Run() == nil
}

func deleteAutostartTask() error {
	if !autostartTaskExists() {
		return nil
	}
	command := exec.Command("schtasks", "/Delete", "/TN", autostartTaskName, "/F")
	hideConsoleWindow(command)
	return command.Run()
}

// createAutostartTask registers a per-user logon task with zero delay and above
// normal task priority, so the app starts before Explorer's delayed Run-key entries.
func createAutostartTask(exe string) error {
	account := os.Getenv("USERDOMAIN") + `\` + os.Getenv("USERNAME")
	if current, err := user.Current(); err == nil && current.Username != "" {
		account = current.Username
	}
	if account == `\` || account == "" {
		return fmt.Errorf("无法确定当前用户")
	}
	xml := autostartTaskXML(exe, account)
	dir := filepath.Join(os.Getenv("LOCALAPPDATA"), AppName, "cache")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	target := filepath.Join(dir, "autostart-task.xml")
	if err := writeUTF16File(target, xml); err != nil {
		return err
	}
	defer os.Remove(target)
	command := exec.Command("schtasks", "/Create", "/TN", autostartTaskName, "/XML", target, "/F")
	hideConsoleWindow(command)
	if output, err := command.CombinedOutput(); err != nil {
		return fmt.Errorf("创建自启动任务失败：%v %s", err, strings.TrimSpace(string(output)))
	}
	return nil
}

func autostartTaskXML(exe, account string) string {
	escapedExe := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace(exe)
	escapedAccount := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace(account)
	return `<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Author>` + escapedAccount + `</Author>
    <Description>SJTU Hive 启动时自动运行（高优先级、零延迟）</Description>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <UserId>` + escapedAccount + `</UserId>
      <Delay>PT0S</Delay>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>` + escapedAccount + `</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>6</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>"` + escapedExe + `"</Command>
      <Arguments>--autostart</Arguments>
    </Exec>
  </Actions>
</Task>`
}

func writeUTF16File(path, content string) error {
	units := utf16.Encode([]rune(content))
	data := make([]byte, 0, 2+len(units)*2)
	data = append(data, 0xFF, 0xFE)
	for _, unit := range units {
		data = append(data, byte(unit), byte(unit>>8))
	}
	return os.WriteFile(path, data, 0o644)
}
