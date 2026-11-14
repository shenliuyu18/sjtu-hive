# 第三方许可说明

本项目以 GNU General Public License v3.0 发布（见 `LICENSE`）。以下列出主要第三方依赖；完整版本与许可文本以各依赖包内的 LICENSE 文件为准。

## 桌面与构建

- Wails v2 — MIT
- Go 标准库与 golang.org/x/* — BSD-3-Clause
- Node.js / Vite — MIT
- NSIS — zlib/libpng（仅安装包使用）

## 前端

- React、React DOM — MIT
- TypeScript — Apache-2.0
- Tailwind CSS — MIT
- Radix UI — MIT
- Zustand — MIT
- lucide-react — ISC
- date-fns — MIT
- sonner — MIT
- CodeMirror 6 与 @uiw/react-codemirror — MIT
- @xterm/xterm、@xterm/addon-fit — MIT
- react-markdown、remark-gfm、remark-math、rehype-katex — MIT
- KaTeX（含字体）— MIT
- Mermaid — MIT
- pdfjs-dist — Apache-2.0
- docx-preview — MIT
- SheetJS (xlsx) — Apache-2.0
- DOMPurify — MPL-2.0 或 Apache-2.0（双许可）

## Go 后端

- modernc.org/sqlite — BSD-3-Clause
- github.com/emersion/go-imap — MIT
- github.com/emersion/go-message — MIT
- github.com/wneessen/go-mail — MIT
- github.com/microcosm-cc/bluemonday — BSD-3-Clause
- github.com/aymanbagabas/go-pty — MIT
- github.com/gen2brain/beeep — MIT
- github.com/gorilla/websocket — BSD-3-Clause

## Android

- AndroidX（Compose BOM、Activity、Core、Lifecycle、WorkManager、WebKit、DocumentFile）— Apache-2.0
- Kotlin 与 Kotlin Coroutines — Apache-2.0
- Haze（dev.chrisbanes.haze）— Apache-2.0
- Miuix（top.yukonga.miuix.kmp）— Apache-2.0
- Backdrop、Capsule（io.github.kyant0）— Apache-2.0
- OkHttp — Apache-2.0
- Coil — Apache-2.0
- JavaMail for Android（com.sun.mail:android-mail / android-activation）— CDDL-1.0 或 GPL-2.0 with Classpath Exception（双许可）

## 改编自其他项目的源码

`android/app/src/main/java/io/legado/app/` 下的以下文件改编自 [legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) 与 [weishu/KernelSU](https://github.com/tiann/KernelSU)。本项目整体即采用 GPL-3.0，与这些文件的许可一致；原始版权声明保留在文件头部：

- `ui/widget/components/FloatingBottomBar.kt` — 部分代码来自 weishu/KernelSU，文件头已标注
- `ui/widget/components/GlassDefaults.kt`、`ui/widget/components/AppSlider.kt`
- `ui/theme/HiveThemeBridge.kt`
- `ui/animation/DampedDragAnimation.kt`、`ui/animation/InteractiveHighlight.kt`
- `ui/util/DragGestureInspector.kt`
- `domain/model/settings/AppUiConfiguration.kt`、`R.kt`

`com.sjtuhive.app` 包下的其余 Android 代码为原创实现，第三方依赖见上一节。

## 配色参数参考

Android 端明暗主题与六套预设配色（雾蓝/黛青/暮紫/松石/琥珀/石墨）的**颜色参数与推导规则**参考自 [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go)（AGPL-3.0）；实现为独立 Kotlin 代码（OKLCH 现推、对比度守卫），未复制其 Dart 源码。本项目整体采用 GPL-3.0，与 AGPL-3.0 兼容。

## 参考与声明

桌面端与 Android 端 `com.sjtuhive.app` 包的代码为独立实现，未复制其他校园客户端的源代码、组件或界面素材；接口依据上海交通大学公开页面整理，与上海交通大学无隶属关系。

应用内使用的校园服务名称、接口与数据归各自权利人所有。

应用内使用的校园服务名称、接口与数据归各自权利人所有。
