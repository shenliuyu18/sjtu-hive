//go:build windows

package app

import (
	"encoding/json"
	"testing"
)

func TestParseServiceApp(t *testing.T) {
	raw := json.RawMessage(`{
		"id": "app-1",
		"code": "student-card",
		"name": "学生证补办",
		"department": "学生处",
		"uri": "https://my.sjtu.edu.cn/ui/app/1",
		"tags": "校园#CategoryName:学生事务,#Other",
		"categoryIcon": [{"学生事务": 12, "sort": 1}],
		"ready": false,
		"rating": 4.5
	}`)
	app, ok := parseServiceApp(raw)
	if !ok {
		t.Fatal("app should parse")
	}
	if app.ID != "app-1" || app.Name != "学生证补办" || app.CategoryName != "学生事务" {
		t.Fatalf("unexpected app: %+v", app)
	}
	if app.TopCategoryName != "学生事务" || app.IconID != 12 {
		t.Fatalf("unexpected category icon: %+v", app)
	}
	if app.Ready || app.Rating != 4.5 {
		t.Fatalf("unexpected flags: %+v", app)
	}
	if _, ok := parseServiceApp(json.RawMessage(`{"id":"1"}`)); ok {
		t.Fatal("app without name should be skipped")
	}
}

func TestParseServiceProcess(t *testing.T) {
	todo := json.RawMessage(`{
		"id": "task-1",
		"name": "辅导员审批",
		"status": 1,
		"uri": "/task/1",
		"process": {
			"id": "proc-1",
			"name": "学生证补办申请",
			"app": {"name": "学生证补办"},
			"entry": "https://my.sjtu.edu.cn/ui/app/1",
			"milestone": {"percent": 40},
			"update": 1762400000
		},
		"assignTime": 1762400000
	}`)
	process, ok := parseServiceProcess(todo, "todo")
	if !ok {
		t.Fatal("todo should parse")
	}
	if process.ID != "task-1" || process.Name != "学生证补办申请" || process.StepName != "辅导员审批" {
		t.Fatalf("unexpected todo: %+v", process)
	}
	if process.AppName != "学生证补办" || process.Percent != 40 || process.Entry == "" {
		t.Fatalf("unexpected todo metadata: %+v", process)
	}
	if process.UpdateAt == "" {
		t.Fatal("todo should carry update time")
	}

	flat := json.RawMessage(`{"id":"p2","name":"成绩单打印","status":"done","uri":"https://my.sjtu.edu.cn/ui/app/2","update":1762400000,"read":true}`)
	done, ok := parseServiceProcess(flat, "completed")
	if !ok || done.ID != "p2" || !done.Read || done.Entry == "" {
		t.Fatalf("unexpected completed process: %+v ok=%v", done, ok)
	}
}

func TestIconDataSource(t *testing.T) {
	if got := iconDataSource("PHN2Zy8+"); got != "data:image/png;base64,PHN2Zy8+" {
		t.Fatalf("base64 payload: %s", got)
	}
	if got := iconDataSource("<svg></svg>"); got[:26] != "data:image/svg+xml;base64," {
		t.Fatalf("svg payload: %s", got)
	}
	if got := iconDataSource("https://example.com/icon.png"); got != "https://example.com/icon.png" {
		t.Fatalf("url payload: %s", got)
	}
}

func TestValidateServiceLink(t *testing.T) {
	cases := []struct {
		link string
		ok   bool
	}{
		{"https://my.sjtu.edu.cn/ui/app/1", true},
		{"https://jaccount.sjtu.edu.cn/oauth2/authorize", true},
		{"http://my.sjtu.edu.cn/ui/app/1", false},
		{"https://evil.example.com/form", false},
		{"javascript:alert(1)", false},
		{"https://sjtu.edu.cn.evil.com/", false},
	}
	for _, item := range cases {
		err := validateServiceLink(item.link)
		if (err == nil) != item.ok {
			t.Fatalf("validateServiceLink(%q) = %v, want ok=%v", item.link, err, item.ok)
		}
	}
}

func TestTransferItemsFromArgs(t *testing.T) {
	items := transferItemsFromArgs(json.RawMessage(`[{"path":"a/b.pdf","name":"b.pdf"},{"path":"  "}]`))
	if len(items) != 1 || items[0].Path != "a/b.pdf" || items[0].Name != "b.pdf" {
		t.Fatalf("unexpected items: %+v", items)
	}
	if len(transferItemsFromArgs(json.RawMessage(`not-json`))) != 0 {
		t.Fatal("invalid payload should be empty")
	}
}
