# SJTU 架构与实现说明

## 1. 目标与边界

目标：在 Windows 上提供一个本地优先的校园桌面应用，覆盖 Canvas、邮箱、日程、课堂视频、水源等高频能力，界面原创、轻量，后端使用 Go。

- 包含：Canvas 资料、邮箱、课表、课堂视频、水源、TODO、全局搜索、工作台（笔记/文件/预览/终端）、通知、设置、数据备份。
- 不包含：聊天/会话、Pi、skills、MCP、模型供应商、packages、web_search/web_fetch、远程项目/SSH、Agent 文件变更审阅。
- 不读取、不导入其他校园客户端的凭据或数据；所有账户在应用内手动配置。
- 单机应用：无服务端、无账号系统、无云同步。

## 2. 分层

```
React 19 + TypeScript + Tailwind 4
        │  window.go.app.App.Call(channel, argsJSON)
        │  window.runtime.EventsOn(...)
        ▼
Wails v2 (WebView2)
        ▼
internal/app (Go)
  ├─ store        SQLite (modernc.org/sqlite, 纯 Go) + schema.sql
  ├─ secrets      Windows DPAPI 加密凭据
  ├─ settings     设置读写与系统开机启动
  ├─ media        127.0.0.1 资源/媒体服务（随机端口 + 能力令牌 + Range）
  ├─ campus       校园 Cookie 会话 + Edge CDP 登录 + 静默续期
  ├─ canvas       Canvas REST 同步、分页、下载、导出
  ├─ mail         IMAP 摘要优先同步、按需正文、SMTP 发送、草稿/回收站
  ├─ timetable    本地日程增删改；课程与校园日程读取交我办日历
  ├─ schoolCalendar 交我办日历同步（课程/考试/会议/讲座）
  ├─ todo         Canvas DDL 聚合 + 本地待办 + 日历联动
  ├─ exams        教学网考试查询与考前提醒
  ├─ classroom    交大视频 API、播放代理、文稿、下载
  ├─ cloudPan     云盘（腾讯 coFile）：目录、传输队列、回收站、分享
  ├─ portalMessages 交我办站内消息同步与去重
  ├─ portalServices 办事大厅：应用目录、在办/已办、独立 WebView2 窗口
  ├─ shuiyuan     Discourse User API Key、信息流、楼层、写操作
  ├─ course       选课社区（course.sjtu.plus）独立账号
  ├─ share        传承交大：资料检索、领取、预览
  ├─ workspace    本地项目/文件树/文本保存/预览/项目资源
  ├─ terminal     ConPTY（go-pty）
  ├─ search       跨模块 LIKE 查询 + 项目文件扫描
  ├─ notifications 轮询、去重、Windows Toast
  ├─ update       检查 GitHub Releases、下载并校验 SHA-256、拉起安装器
  ├─ tray         Win32 托盘图标与「关闭到托盘」
  └─ backup       VACUUM INTO + ZIP 导出/导入、加密备份、WebDAV
```

### 2.1 前后端协议

- 单一绑定：`window.go.app.App.Call(channel, argsJSON)`，`argsJSON` 是参数数组的 JSON；Go 侧 `Router` 按 `domain.action` 分发。
- 后端推送：`runtime.EventsEmit`；前端 `on(name, callback)`。事件包括 `canvas.state`、`mail.sync`、`todo.state`、`timetable.changed`、`workspace.changed`、`terminal.data`、`classroom.download`、`notifications.changed`。
- 前端在 `window.go` 不存在时使用 `src/lib/mock.ts` 的演示数据，便于浏览器预览；Wails 中始终使用真实服务。

### 2.2 关键设计

**资源服务**：`internal/app/media.go` 启动只监听 `127.0.0.1` 的 HTTP 服务，随机能力令牌映射到文件/字节/目录/上游代理；本地文件使用 `http.ServeContent` 支持 Range；视频代理转发 `Range` 并保留校园 Cookie 在 Go 侧。渲染层只拿到短期 URL。

**校园登录**：`campus.go` 维护自定义 Cookie Jar（支持 host-only 与域 Cookie，DPAPI 持久化）。`edge_windows.go` 启动独立 Edge profile，通过 CDP 的 `Storage.getCookies` 读取 `.sjtu.edu.cn` Cookie；登录成功后访问一次视频站以获取后续 Cookie，再关闭 Edge。Cookie 粘贴作为兜底。

**课堂视频**：按交大视频公开接口解析学期、课程、录像、画面与文稿；画面 URL 校验 `https://live.sjtu.edu.cn/*.mp4` 后注册到本地代理。播放器请求本地代理，Go 侧携带 Cookie 请求上游；文稿按毫秒时间戳驱动跳转，位置写入 SQLite。

**邮箱**：IMAP 先同步信封/标记/大小等摘要，打开邮件时按 UID 获取正文并解析 MIME；附件写入本地缓存。SMTP 使用 `go-mail`，交大 465 端口走隐式 TLS；发送后尝试 APPEND 到服务器「已发送」。删除仅进入本地回收站，不影响服务器。

**工作台**：文件直接落磁盘，不用数据库保存笔记内容。保存时校验 SHA-256 revision，保留 BOM 与原始换行风格，使用同目录临时文件原子替换；1 MB 以上只读。Markdown 保留原文，前端做渲染（KaTeX/mermaid）。

**终端**：`go-pty` 创建 ConPTY；优先 Git Bash，缺失时 PowerShell；输出以 base64 事件推送，xterm.js 渲染；支持 resize 与中文输入。

## 3. 数据

主要表（共 24 张，完整定义见 `internal/app/schema.sql`）：`settings`、`meta`、`projects`、`recent_files`、`canvas_courses/sources/cache`、`mail_accounts/folders/messages/drafts`、`calendar_events`、`school_events`、`school_calendar_meta`、`todo_items`、`exam_items`、`video_progress`、`video_downloads`、`portal_messages`、`portal_message_meta`、`cloud_transfers`、`notifications`、`workspace_tabs`、`shuiyuan_sources`。

凭据不进入 SQLite：`canvas-token`、`mail-credentials`、`campus-session`、`shuiyuan-key` 以 DPAPI 密文保存于 `%APPDATA%\SJTU Hive\secrets\`。

## 4. UI

- 无边框窗口、40px 自定义标题栏、可折叠侧栏、页面头部、命令面板、通知抽屉、亮/暗主题。
- 页面：概览、资料、邮箱、日程、办事大厅（测试功能）、课堂视频、云盘、水源社区、选课社区、传承交大、TODO、工作台、通知、设置。
- 工作台单标签组（轻量化取舍）；文件编辑/预览/终端共用内容区。
- 所有尺寸由 rem 派生；字号设置整体缩放文字、间距、图标与圆角。

## 5. 测试策略

- Go 单元/集成测试：DPAPI、Cookie、Canvas 分页与完整同步/下载/导出、学期参数、日程查询口径、媒体 Range、工作台保存不变量、邮件 MIME、TODO 计数、搜索、VACUUM、ConPTY。
- 前后端契约测试（`channel_contract_test.go`）：静态解析后端 `router.Handle` 与前端 `call()`，保证前端调用的 channel 全部注册、且不存在重复注册——这是字符串协议唯一的编译期保障。
- 版本一致性：`node tools/sync-version.mjs --check` 校验五处版本号与 `VERSION` 相同，CI 与发版脚本都会先跑。
- 浏览器 UI 验收：使用真实 React 构建 + mock bridge，在 1440×900 下逐页截图，并对首次引导/连接弹窗做几何断言。
- 原生验收：Wails 构建、真实窗口启动、`frontend ready` 日志、NSIS 安装/卸载。
- 真实交大服务：需要用户在本机手动登录后做最终验收；开发环境未持有真实凭据。

## 6. 已知限制

- 单标签组、无分屏、无自定义键位；托盘图标与「关闭到托盘」已支持。
- PPTX 与旧版 Office 都是内置渲染，不依赖系统 Office；复杂排版、宏与嵌入对象可能丢失，PPTX 只呈现静态页面、不播放切换动画。
- 邮箱一次一个账户；无 OAuth。
- 水源社区为实验性功能；不追求完整社区复刻。
- 自动更新在应用内下载并校验 SHA-256，但安装仍需用户确认，不会静默升级。
- 课堂视频下载不做转码/合并，断流不做无缝续接。
