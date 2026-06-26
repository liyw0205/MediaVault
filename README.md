# MediaVault

独立原生 Android 媒体库（无 CGI / WebView），信息架构与刮削习惯对齐 Neribox `neribox_web.sh`（Neribox 仅作对照，不混线开发）。

- 包名：`com.mediavault`
- 当前版本：**0.3.14**（远程扫库与播放；设置测试连接；列表续播进度条）

## 功能概要

- 底栏：主页 / 搜索 / 合集 / 刮削；顶栏：重读、数据、导入 library.json、设置
- **播放器**：沉浸底栏 1dp 细线；单击显控件；**仅横向**长按/滑动松手 seek；纵向滑动交给系统；倍速/快进快退/拖动仅屏幕中央提示（无 Toast）
- **进度记忆**：按 path 保存（含远程 `remote|<id>|<path>`）；手势 seek、进度条、±10s、每 10s、退出写入；再次进入自动续播（近片尾清零）
- **远程（0.3.14）**：库内 path 约定 `remote|<configId>|<posixPath>`；ExoPlayer 经 `RemoteDataSource` 拉流播放 WebDAV/FTP/SMB；刮削页展示远程根并支持增量/重扫/移除库内条目
- **设置 · 远程浏览**：WebDAV 默认根 `/dav`、端口 5244；根路径旁可浏览远程目录（文件夹+文件同列）；**视频文件可点播放**（未保存配置时用预览连接）
- **设置**：本地媒体根；WebDAV/FTP/SMB；**测试连接**（从列表选择一项）
- **主页/搜索卡片**：封面底部显示续播进度条（有记录时）
- **刮削**：本地 SAF + 远程并行枚举；前台服务；`scrape-record.tsv` 增量续扫
- 主页推荐、历史与分页

## 构建（Termux / Linux）

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
bash pack_mediavault.sh
```

产出：`MediaVault_<versionName>_debug.apk`（如 `MediaVault_0.3.14_debug.apk`）。

## 数据

应用私有目录：`library.json`、`roots.list`、`remotes.json`、`scrape-record.tsv`、`covers/`、播放进度等。

## 维护说明

日常开发目录：`devwork/MediaVault`。发布 GitHub 时同步到 `MediaVault_git`（排除 `build/`、`local.properties`）。

调试 logcat：`devwork/log_mediavault.sh`（Neribox 用 `log.sh`）。