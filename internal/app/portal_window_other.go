//go:build !windows

package app

import "errors"

func (s *PortalServices) OpenForm(string, string) error {
	return errors.New("内嵌办理窗口仅在 Windows 桌面端可用")
}
