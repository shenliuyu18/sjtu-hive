# SJTU Hive · 校园桌面与 Android

面向上海交大学生的本地优先校园客户端，当前版本 **1.3.0**。桌面端使用 **Wails v2 + Go + React**，Android 端使用 **Kotlin + Jetpack Compose**；两端共享同一套校园服务接口与产品结构。

功能覆盖 Canvas 资料、校园邮箱、交我办日程与考试、办事大厅（测试功能）、课堂视频、交大云盘、水源社区、选课社区、传承交大、TODO、全局搜索，以及本地笔记/文件工作台与终端。

> 本应用是参考交大公开接口的独立实现，未复制任何第三方客户端的源码或界面素材，与上海交通大学无隶属关系。

- 官网：<https://shenliuyu18.github.io/sjtu-hive/>
- 发布页：<https://github.com/shenliuyu18/sjtu-hive/releases>
- 更新记录：[CHANGELOG.md](CHANGELOG.md)

![概览](docs/screenshots/01-overview.png)

## 桌面端功能

| 模块 | 能力 |
| --- | --- |
| 概览 | 今日课程、临近 DDL、未读邮件、校园连接状态、最近文件、快捷操作与交我办消息 |
| Canvas 资料 | 优先复用 jAccount；课程/学期/文件树/模块/作业/页面/讨论同步；PDF、图片、文本、Markdown、HTML、DOCX、XLSX、PPTX（含内嵌视频）与旧版 DOC/PPT/XLS 内置预览；下载、批量导出、加入本地项目 |
| 邮箱 | 交大邮箱预设或第三方 IMAP/SMTP；文件夹与未读计数；摘要优先同步、正文按需获取；附件；写信、回复、转发；草稿自动保存；本地回收站；HTML 邮件深色适配与不可达图片降级 |
| 云盘 | pan.sjtu.edu.cn（腾讯 coFile）：jAccount 单点登录、目录浏览、流式预览、配额；多文件/文件夹上传下载队列；单条与全部暂停/继续、取消、重试；下载 Range 续传、上传分片续传；删除移入云盘回收站并支持恢复/永久删除；分享创建、列表与取消 |
| 办事大厅（测试功能） | 交我办应用目录、分类、收藏、最近使用；在办/已办/抄送流程；在应用内独立 WebView2 窗口打开办理页并复用校园登录态。默认关闭，需在「设置 → 测试功能」开启 |
| 消息与通知 | 交我办站内消息定时同步；独立通知页支持全部/未读筛选、全部已读、按来源跳转；Windows 系统通知。启动后的首次同步把上次退出期间收到的新消息合并为一条系统通知，通知页仍逐条保留 |
| 考试与日程 | 教学网考试查询与考前提醒；交我办日历课程、考试、会议、讲座与个人日程；每小时同步；周/月视图；本地日程新建与编辑 |
| 课堂视频 | 学期/课程/录像列表；单画面与双画面；播放代理；JWT 自动续取；语音文稿跟随、点击跳转与导出 TXT；断点续看；本地下载 |
| 水源社区 | Discourse User API Key 授权（jAccount 回退）；最新/精品/分类/标签/搜索；主题与楼层；回复、发主题、私信；点赞、收藏、通知；图片上传；用户主页与徽章；secure uploads 本地代理 |
| 选课社区 | course.sjtu.plus 独立账号；热门课程、搜索、课程详情与评价；我的课程（选课记录/已关注/已屏蔽）与学期筛选 |
| 传承交大 | 复用 jAccount；课程搜索、资料列表与详情、0 积分领取、内置预览与下载 |
| TODO | 本地待办与 Canvas 作业 DDL 聚合；完成状态；列表/月历；写入月历并双向联动；到期提醒 |
| 全局搜索 | 课程资料、邮件、日程、TODO、项目文件与设置项的本地搜索 |
| 工作台 | 本地项目、文件树、标签页、CodeMirror 编辑、Markdown + KaTeX + mermaid、Office/PDF/图片预览与全屏、ConPTY 终端（Git Bash 优先）、Git 分支显示与切换 |
| 备份 | 本地导出/导入（`.zip`）、加密备份（`*.hivebackup`）、WebDAV 上传/列表/恢复；导入前自动保存本机快照 |
| 设置 | 账户与连接、外观与字号、高优先级开机自启、关闭行为、通知、测试功能、导航入口、缓存、备份、更新、条款与隐私 |
| 托盘与更新 | Win32 托盘图标；启动后静默检查 GitHub Releases，设置页可手动检查、下载并校验 SHA-256 后启动安装器 |
| 会话续期 | 校园 Cookie 失效时静默走 jAccount 单点登录，续期失败才提示重新登录 |

不包含 Agent 相关能力（聊天、会话、skills、MCP、模型供应商、远程项目/SSH 等）。

## Android 功能

`android/` 与 `android-hive/` 是同一套 Android 工程，后者用于本地签名构建，文件保持同步。

- 底部页签默认是 **概览 / 日程 / 云盘 / 设置**；「办事大厅」是测试功能，需在「设置 → 测试功能」开启后才出现页签。消息入口在概览右上角。
- 概览、日程、课堂视频（自动横屏、玻璃控件、单/双画面）、水源、选课社区、传承交大、邮箱、Canvas 以全屏页或覆盖层提供。
- 云盘：目录浏览、预览、下载队列（Range 续传、单条/全部暂停继续、取消、重试）、多选与文件夹上传、分片续传、删除与回收站（恢复/永久删除）、分享创建/列表/取消。
- 办事大厅（测试功能，默认关闭）：目录、流程数据与应用图标与桌面端一致；办理页全屏 WebView，返回手势关闭。
- 更新：检查 GitHub Releases、应用内下载 APK、SHA-256 校验，经 `FileProvider` 拉起系统安装器。
- 系统通知：交我办新消息与未读邮件由 WorkManager 每 15 分钟检查，正常退出应用后仍会继续；开机或应用升级后自动重新登记。首次使用需授予通知权限。
- 预览支持 PDF、图片、文本、音视频、DOCX/XLSX、PPTX 逐页渲染（含内嵌视频）与旧版 DOC/PPT/XLS 只读预览，均可全屏。

## 安装与首次配置

**Windows 10/11 x64**：安装包会检查 WebView2，用户级 NSIS 安装不要求管理员权限；绿色版解压后直接运行 `SJTU-Hive.exe`。

**Android 13+（minSdk 33）**：安装 Release APK；应用内更新需允许「安装未知应用」。

1. **jAccount**：在「设置 → 账户与连接」登录。桌面端会打开独立 Edge 配置窗口，登录成功后读取 `.sjtu.edu.cn` Cookie 并加密保存；日程、消息、办事大厅、云盘、课堂视频与传承交大复用该会话。
2. **校园邮箱**：填写邮箱地址与密码/授权码；交大邮箱默认 `mail.sjtu.edu.cn` IMAP 993 / SMTP 465，第三方邮箱可自定义服务器。
3. **Canvas**：默认复用 jAccount；也可在 `oc.sjtu.edu.cn` 新建访问令牌后填入。
4. **水源社区**：点击「登录并授权」，在授权页确认后把密文粘贴回应用；也支持 jAccount 回退。
5. **选课社区**：使用 course.sjtu.plus 的独立账号登录，应用只保存会话。
6. **办事大厅**：在设置中开启「测试功能 → 办事大厅」后显示入口。

## 数据与安全

- 所有数据保存在本机；没有应用账号系统、遥测或云端中转。
- Windows 凭据（邮箱密码、校园 Cookie、水源 Key、选课社区会话）使用 **Windows DPAPI** 加密；Android 校园会话保存在应用私有存储，邮箱密码等使用 AndroidKeyStore AES/GCM。
- SQLite：`%APPDATA%\SJTU Hive\sjtu.db`；加密凭据：`%APPDATA%\SJTU Hive\secrets\`；日志：`%APPDATA%\SJTU Hive\logs\`。
- 缓存：`%LOCALAPPDATA%\SJTU Hive\cache\`；更新包：`%LOCALAPPDATA%\SJTU Hive\updates\`；WebView2：`%LOCALAPPDATA%\SJTU Hive\EBWebView\`；Edge 校园登录配置：`%LOCALAPPDATA%\SJTU Hive\edge-campus\`。
- 默认项目目录：`文档\SJTU Notes`（首次启动创建；移除登记不会删除磁盘文件）。
- 邮件与水源 HTML 在沙箱 iframe / DOMPurify 后渲染；外链交给系统浏览器；办理页只允许打开 `*.sjtu.edu.cn`。
- 预览资源通过本机 `127.0.0.1` 随机端口与随机令牌提供；课堂视频走代理，Cookie 不暴露给渲染层。
- 联网请求只直达你主动使用的校园服务、邮箱服务器、WebDAV 与 GitHub Releases；更新检查只读取公开版本信息。

## 开发

```sh
# 前置：Go 1.25+、Wails v2.16、Node 24+、NSIS（打包）
export GOPROXY=https://goproxy.cn,direct
export GOSUMDB=off GOTOOLCHAIN=local

go run github.com/wailsapp/wails/v2/cmd/wails@v2.16.0 build -platform windows/amd64
go run github.com/wailsapp/wails/v2/cmd/wails@v2.16.0 build -platform windows/amd64 -nsis -installscope user -webview2 download
go test ./... -count=1
```

前端可单独预览：`cd frontend && npm install && npm run dev`；浏览器模式下由 `src/lib/mock.ts` 提供演示数据。

### Android 构建

```sh
cd android
gradle :app:assembleDebug   # 或 :app:assembleRelease（需本地 keystore.properties）
```

Release APK 使用维护者本地正式签名；`keystore.properties` 与 `keystore/` 不入库。R8 规则见 `app/proguard-rules.pro`，每次发布归档 `mapping.txt`。

版本约定：对外只发布稳定的 `x.y.z` 版本；开发构建使用递增的预发布号，仅在本地使用。

## 已知限制

- 校园接口属于学校内部服务，可能随时调整；接口变化时相关模块可能暂时不可用。
- 交我办消息、办事大厅与云盘依赖 jAccount 会话；会话过期时先静默续期，jAccount 本身过期后需重新登录。云盘 `user_token` 有效期 30 天，`access_token` 约 30 分钟自动续取。
- 云盘上传续传依赖服务端 `confirmKey`；服务端会话过期后需从头上传该文件。Android 传输队列在进程被杀后标记为暂停，不提供前台服务常驻下载。
- 办事大厅是测试功能，默认关闭；办理页由学校页面自身渲染，少数表单的弹窗、外部下载等行为取决于页面实现，应用只允许打开交大域名。
- 日程来自交我办日历，本地日程不写回学校系统。
- 桌面端单窗口、单标签组，固定快捷键，不支持自定义键位。
- 旧版 Office 为只读轻量渲染，复杂排版、宏与嵌入对象可能丢失；PPTX 只呈现静态页面，不播放切换动画。
- 邮箱一次配置一个账户；远程图片若当前网络无法解析其域名，会隐藏并默认显示纯文本，可在详情顶部切回原始排版。
- 课堂视频「全量缓存」以本地下载实现，未做转码/合并；流中断后由播放器重试。
- 更新不会静默安装：Windows 与 Android 都需要确认；Windows 安装程序开始前会结束正在运行的实例。
- Android 通知依赖 WorkManager 的 15 分钟最小周期，正常退出后仍继续；在系统设置中「强行停止」或深度省电会暂停轮询，重新打开后恢复。
- Windows 启动后的首次同步窗口内不逐条弹窗，同步到的新消息合并为一条系统通知；通知页仍逐条保留。

## 许可

应用源码以 MIT License 发布（见 `LICENSE`）。第三方库与素材许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。Android 端部分玻璃组件来自 legado-with-MD3，按 GPL-3.0 使用。
