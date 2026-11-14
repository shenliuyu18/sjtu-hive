package app

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// 前后端只靠字符串约定 channel 名，没有任何编译期检查。
// 这组测试静态扫描两侧源码，保证前端调用的每个 channel 后端都真的注册过，
// 把"运行到那一步才发现对不上"提前到测试阶段。

var frontendCallPattern = regexp.MustCompile(`\bcall(?:<[^>]*>)?\(\s*['"]([A-Za-z][A-Za-z0-9._]*)['"]`)

// backendChannels 解析 internal/app 下的 Go 源码，收集 router.Handle("x") 注册的 channel。
func backendChannels(t *testing.T) map[string][]string {
	t.Helper()
	found := map[string][]string{}
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatalf("读取后端目录失败：%v", err)
	}
	files := token.NewFileSet()
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		parsed, err := parser.ParseFile(files, name, nil, 0)
		if err != nil {
			t.Fatalf("解析 %s 失败：%v", name, err)
		}
		ast.Inspect(parsed, func(node ast.Node) bool {
			call, ok := node.(*ast.CallExpr)
			if !ok || len(call.Args) == 0 {
				return true
			}
			selector, ok := call.Fun.(*ast.SelectorExpr)
			if !ok || selector.Sel.Name != "Handle" {
				return true
			}
			literal, ok := call.Args[0].(*ast.BasicLit)
			if !ok || literal.Kind != token.STRING {
				return true
			}
			channel, err := strconv.Unquote(literal.Value)
			if err != nil || channel == "" {
				return true
			}
			found[channel] = append(found[channel], name)
			return true
		})
	}
	return found
}

// frontendChannels 扫描 frontend/src，收集 call('x') / call<T>('x') 里的 channel。
func frontendChannels(t *testing.T) map[string][]string {
	t.Helper()
	root := filepath.Join("..", "..", "frontend", "src")
	found := map[string][]string{}
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		if !strings.HasSuffix(path, ".ts") && !strings.HasSuffix(path, ".tsx") {
			return nil
		}
		content, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		for _, match := range frontendCallPattern.FindAllStringSubmatch(string(content), -1) {
			found[match[1]] = append(found[match[1]], filepath.Base(path))
		}
		return nil
	})
	if err != nil {
		t.Fatalf("扫描前端源码失败：%v", err)
	}
	return found
}

func TestFrontendChannelsExistInBackend(t *testing.T) {
	backend := backendChannels(t)
	frontend := frontendChannels(t)
	if len(frontend) == 0 {
		t.Fatal("没有扫描到任何前端 channel 调用，检查扫描路径是否失效")
	}

	var missing []string
	for channel, files := range frontend {
		if _, ok := backend[channel]; !ok {
			missing = append(missing, channel+"  ← "+strings.Join(files, ", "))
		}
	}
	sort.Strings(missing)
	if len(missing) > 0 {
		t.Fatalf("以下 channel 前端在调用、后端没有注册（会报「未知操作」）：\n  %s",
			strings.Join(missing, "\n  "))
	}
	t.Logf("已校验 %d 个前端 channel，全部在后端注册", len(frontend))
}

func TestNoDuplicateChannelRegistration(t *testing.T) {
	backend := backendChannels(t)
	var duplicates []string
	for channel, files := range backend {
		if len(files) > 1 {
			duplicates = append(duplicates, channel+"  ← "+strings.Join(files, ", "))
		}
	}
	sort.Strings(duplicates)
	if len(duplicates) > 0 {
		t.Fatalf("channel 被重复注册，Router 会在启动时 panic：\n  %s", strings.Join(duplicates, "\n  "))
	}
}

// 只做提示不做断言：后端有些 channel 是内部调用或预留的，前端未必每个都用。
func TestChannelCoverageReport(t *testing.T) {
	backend := backendChannels(t)
	frontend := frontendChannels(t)
	unused := make([]string, 0, len(backend))
	for channel := range backend {
		if _, ok := frontend[channel]; !ok {
			unused = append(unused, channel)
		}
	}
	sort.Strings(unused)
	t.Logf("后端注册 %d 个 channel，前端调用 %d 个，前端未使用 %d 个",
		len(backend), len(frontend), len(unused))
	if len(unused) > 0 && len(unused) <= 40 {
		t.Logf("前端未调用：%s", strings.Join(unused, ", "))
	}
}
