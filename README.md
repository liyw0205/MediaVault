# MediaVault

独立原生 Android 媒体库（无 CGI / WebView），信息架构与刮削习惯对齐 Neribox `neribox_web.sh`（Neribox 仅作对照，不混线开发）。

- 包名：`com.mediavault`
- 当前版本：**0.3.3**（首页单选筛选、20 条分页与跳页、横条时长+简介、搜索空态仅标签、刮削页目录只读展示）

## 功能概要

- 底栏：主页 / 搜索 / 合集 / 刮削；顶栏：重读、数据、导入 library.json、设置
- **设置** 中通过 SAF 添加/编辑媒体库根目录；**刮削页** 仅只读展示已配置目录
- SAF 刮削（多线程 1–32）、前台服务批量入库、`scrape-record.tsv` 增量续扫
- 主页推荐（启动缓存 + 手动刷新）、历史、全部/按根筛选；详情页、合集、ExoPlayer 播放

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

产出：`MediaVault_0.3.3_debug.apk`（或 `app/build/outputs/apk/debug/app-debug.apk`）。

## 数据

应用私有目录：`library.json`、`roots.list`、`remotes.json`、`scrape-record.tsv`、`covers/` 等。

## 维护说明

日常开发目录：`devwork/MediaVault`。发布 GitHub 时同步到 `MediaVault_git`（排除 `build/`、`local.properties`）。

详细对照与补丁说明见 Hermes 技能 `neribox-media-library`：`references/standalone-native-media-app-mediavault.md`、`references/mediavault-033-home-single-select-row-subtitle-search-scrape-readonly.md`。