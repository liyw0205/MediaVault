# MediaVault

独立原生 Android 媒体库（无 CGI / WebView），对齐 Neribox `neribox_web.sh` 的信息架构与刮削习惯。

- 包名：`com.mediavault`
- 当前版本：**0.3.0**（本地 sidecar/NFO、标签、合集、播放器手势与字幕）

## 功能概要

- 主页 / 搜索 / **合集** / 刮削（四底栏）
- SAF 选目录刮削（仅视频文件；同目录吸收 NFO、字幕、封面）
- 后台前台服务批量刮削（可切 Tab）
- 详情页、合集分组、按合集生成播放列表
- Media3 ExoPlayer 全屏播放（手势、外置/内嵌字幕、截图）

## 构建（Termux / Linux）

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
bash pack_mediavault.sh
```

工程根目录即本仓库；`gradle.properties` 在 Termux 上建议保留：

- `android.aapt2FromMavenOverride=.../aapt2`
- `kotlin.compiler.execution.strategy=in-process`

产出：`app/build/outputs/apk/debug/app-debug.apk`（可用脚本复制为 `MediaVault_0.3.0_debug.apk`）。

## 数据

应用私有目录：`library.json`、`roots.list`、`remotes.json`、`scrape-record.tsv`、`covers/` 等。

## 维护说明

本仓库由本地 `devwork/MediaVault` 同步；详细补丁与 Neribox 对照见 Hermes 技能 `neribox-media-library` 下 `references/standalone-native-media-app-mediavault.md`、`references/mediavault-03-sidecar-player-collections.md`。