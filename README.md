# MediaVault

本机原生 Android 媒体库：浏览、搜索、刮削元数据，支持本地文件夹与 WebDAV / FTP / SMB 远程点播。不依赖网页壳或 CGI。

- 包名：`com.mediavault`
- 当前版本：**0.3.19**

## 能做什么

- **底栏**：主页、搜索、合集、刮削
- **顶栏**：重读库、查看/清理数据、设置（媒体根与远程）
- **播放**：全屏播放器；记住进度（含远程路径）；手势横向 seek；字幕与截图
- **远程**：在设置里添加 WebDAV / FTP / SMB，可浏览目录、试播、刮削入库；播放时从网络拉流，并缓存已看过的片段
- **主页**：推荐、观看历史、分页列表；卡片上显示续播进度
- **刮削**：按目录增量扫描视频；读 NFO / 同目录封面图；远程目录可抽帧或拉 sidecar 图做封面

## 构建

在 Termux 或已装 Android SDK 的 Linux 上：

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
cd devwork   # 或你的工程父目录
bash pack_mediavault.sh
```

生成：`MediaVault_<版本>_debug.apk`（例如 `MediaVault_0.3.18_debug.apk`）。

## 应用数据位置

私有目录内主要包括：媒体列表、目录与远程配置、刮削记录、封面缓存、播放进度等。可在顶栏「数据」里查看占用并逐项清理。

## 库内路径约定（开发用）

- 远程条目：`remote|<配置 id>|<POSIX 路径>`
- 播放 URI：`mediavault-remote://…`（由应用内 DataSource 拉流）

## 仓库与日常开发

- 日常改代码：`devwork/MediaVault`
- 推 GitHub：同步到 `MediaVault_git`（不要提交 `build/`、`local.properties`）
- 抓日志：`devwork/log_mediavault.sh`