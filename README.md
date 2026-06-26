# MediaVault

独立原生 Android 媒体库（无 CGI / WebView），信息架构与刮削习惯对齐 Neribox `neribox_web.sh`（Neribox 仅作对照，不混线开发）。

- 包名：`com.mediavault`
- 当前版本：**0.3.10**（播放器沉浸浮层与松手 seek；搜索列表异步封面；主页推荐持久化与自动种子一次；深色主题与 MvDialog）

## 功能概要

- 底栏：主页 / 搜索 / 合集 / 刮削；顶栏：重读、数据、导入 library.json、设置
- **播放器**：进入即沉浸（底栏 1dp 细线进度）；单击显控件；进度条/长按左右滑 **松手后** seek；倍速中心 **2x**；双击左/中/右快退/暂停/快进（时间吐司）
- **搜索**：防抖、RecyclerView 缓存；卡片封面 IO 采样 + LRU，标签单行
- **设置**：本地媒体根；WebDAV/FTP/SMB 远程表单与列表；WebDAV 测试连接
- **刮削页**：各媒体根单独刮削 / 移除库内 / 重新刮削
- SAF 刮削、前台服务、`scrape-record.tsv` 增量续扫
- 主页推荐（持久化 + 无列表时自动随机一次）、历史与分页

## 构建（Termux / Linux）

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
bash pack_mediavault.sh   # 在 devwork 目录，指向 MediaVault 工程
```

或在仓库根（clone 后）：

```bash
bash pack_mediavault.sh
```

`gradle.properties` 在 Termux 上建议保留：

- `android.aapt2FromMavenOverride=.../aapt2`
- `kotlin.compiler.execution.strategy=in-process`

产出：`MediaVault_<versionName>_debug.apk`（如 `MediaVault_0.3.10_debug.apk`）。

## 数据

应用私有目录：`library.json`、`roots.list`、`remotes.json`、`scrape-record.tsv`、`covers/` 等。

## 维护说明

日常开发目录：`devwork/MediaVault`。发布 GitHub 时同步到 `MediaVault_git`（排除 `build/`、`local.properties`）。

详细对照与补丁说明见 Hermes 技能 `neribox-media-library`：`references/standalone-native-media-app-mediavault.md`、`references/mediavault-037-search-scroll-jank-and-player-chrome.md`。