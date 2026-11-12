package app

import "testing"

func TestCompareVersionsStableAbovePrerelease(t *testing.T) {
	cases := []struct {
		left, right string
		want        int
	}{
		{"1.3.0", "1.3.0-beta.14", 1},
		{"1.3.0-beta.14", "1.3.0", -1},
		{"1.3.0", "1.2.9", 1},
		{"1.3.0-beta.14", "1.3.0-beta.3", 1},
		{"1.3.0", "1.3.0", 0},
		{"v1.3.0", "1.3.0", 0},
	}
	for _, item := range cases {
		got := compareVersions(item.left, item.right)
		if (got > 0) != (item.want > 0) || (got < 0) != (item.want < 0) || (got == 0) != (item.want == 0) {
			t.Fatalf("compareVersions(%q, %q) = %d, want %d", item.left, item.right, got, item.want)
		}
	}
}
