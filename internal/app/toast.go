package app

import "github.com/gen2brain/beeep"

// systemToast delivers a Windows toast. Verified against the system notification
// store (%LOCALAPPDATA%\Microsoft\Windows\Notifications\wpndatabase.db): this path
// creates handler "SJTU" plus toast rows, while Shell_NotifyIcon balloons do not.
func systemToast(title, body string) error {
	beeep.AppName = AppName
	return beeep.Notify(title, body, "")
}
