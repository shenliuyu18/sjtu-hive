# Android 12 及以下适配方案

当前 `minSdk = 33`（Android 13），应用无法安装在 Android 12 及以下设备上。
本文给出降到 Android 8（API 26）的完整路径：哪些能直接降、哪些要改、哪些建议放弃。

## 一、版本门槛盘点

| 依赖 / 能力 | 要求 | 低于门槛时的表现 | 处理 |
|---|---|---|---|
| kyant backdrop 的 `vibrancy/blur/lens` | API 33+（RenderEffect） | 模糊、折射无效 | 已在 `HiveApp` 里 `isBlurEnabled = SDK_INT >= 33` 门控；低于 33 自动退化为半透明纯色胶囊 |
| `InteractiveHighlight`（按压液态高光） | 代码已判断 `SDK_INT >= TIRAMISU` | 不启用 | 无需改动 |
| `WebView.setWebContentsDebuggingEnabled` 等 | 全版本可用 | — | 无需改动 |
| 通知权限（POST_NOTIFICATIONS） | API 33+ 才需要请求 | API 26–32 直接拥有 | `Notifications.kt` 已按版本分支，无需改动 |
| Compose / Material3 | minSdk 21+ | — | 无影响 |
| JavaMail for Android | minSdk 26 左右 | — | minSdk 建议设 26，不建议更低 |
| EdgeToEdge / WindowInsets | API 23+，行为差异在 29/30/35 | 各版本插边不同 | 已走 `WindowInsets` API，无需大改 |

结论：**minSdk 降到 26（Android 8.0）技术上成立**，代价是玻璃质感整体降级。

## 二、降级策略（代码已就位的部分）

1. **底栏**：`FloatingBottomBar(isBlurEnabled = SDK_INT >= 33)`。
   关闭后组件画的是 `surfaceContainer` 加不透明度，胶囊仍在，只是没有模糊与折射。
   如需更接近玻璃的观感，可在低版本把 `bottomBarBlurAlpha`（当前 40）提到 70–80，
   用「更实的半透明」补偿没有模糊的损失。
2. **主色/配色系统**：全部基于 Compose 颜色计算（OKLCH 现推），无 API 门槛，
   Android 8 上六套预设与自定义配色行为完全一致。
3. **状态栏图标深浅**：`WindowCompat.getInsetsController` 从 API 23 起可用，无需改动。

## 三、降到 26 还需要做的事

1. `android/app/build.gradle.kts`：`minSdk = 26`，并跑一次全量 Lint
   （`gradle :app:lintRelease`），处理 NewApi 告警——预计集中在
   `java.time`（API 26 起可用，正好达标）与 WebView 相关。
2. 真机矩阵测试：至少覆盖 8.0、9、10、11、12 各一台，
   重点看底栏（无模糊时的对比度）、水源 WebView、通知点击跳转。
3. 课堂视频下载与 WorkManager：Android 12 有精确闹钟/前台服务限制，
   `video_downloads` 的调度需要回归一遍。
4. 安装包体积与 WebView 兼容：Android 8 的系统 WebView 版本普遍较旧，
   水源帖子的 JS（滚动加载桥）需要在老 WebView 上验证
   `insertAdjacentHTML`、`JavascriptInterface` 均为多年 API，风险低。

## 四、建议

- **不建议支持 Android 7 及以下**：Compose 与 OkHttp 的最低实践线都在 8，
  收益群体极小。
- 如果 12 以下用户反馈集中在「底栏不好看」，优先把半透明胶囊的
  `surfaceContainer` 不透明度按 API 分档调参，而不是自己实现 Pre-33 模糊
  （RenderScript 方案已废弃，自绘 blur 的性能在低端机上不可接受）。
