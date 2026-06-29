# MediaVault

Android 原生媒体库：本地文件夹与 WebDAV / FTP / SMB 远程目录，浏览、搜索、刮削、续播。不依赖 WebView 或 CGI。

| 项目 | 说明 |
|------|------|
| 包名 | `com.mediavault` |
| 最低系统 | Android 8.0（API 26） |
| 当前版本 | **0.3.61**（versionCode 65） |

## 安装

- **Releases**：在 [GitHub Releases](https://github.com/liyw0205/MediaVault/releases) 下载 `MediaVault_<版本>_debug.apk`，安装后按需授予存储/网络权限。
- **自行编译**：见下文「从源码构建」。

## 功能概览

| 区域 | 说明 |
|------|------|
| 底栏 | 主页、搜索、合集、刮削、**设置** |
| 顶栏 | **主页**：重读库；**刮削**：右上角设置（右侧侧栏）；**设置**：连播与数据清理，可跳转刮削侧栏 |
| 主页 | 推荐、观看历史、按目录分页；卡片显示续播进度 |
| 播放 | 全屏播放、手势 seek、字幕、截图（保存到 **Pictures/MediaVault**）、进度记忆（含远程） |
| 刮削侧栏 | 刮削选项、管理媒体目录入口、数据占用与清理 |
| 刮削 | 按目录增量扫描；NFO / 同目录封面；远程可 sidecar 或抽帧；**进度浮窗可收起/展开** |
| 远程播放 | 网络拉流，已播放片段可本地缓存，减少重复下载 |

## 使用说明

### 添加本机媒体

1. 在 **刮削** Tab 顶栏打开 **管理媒体目录** → **添加文件夹**，用系统文档选择器选目录并授权。
2. 回到刮削页选目录，开始扫描；或从 **主页** 按目录浏览已入库条目。

### 添加远程目录

1. **刮削** Tab → **管理媒体目录** → 点 **WebDAV** / **FTP** / **SMB**，填写主机、账号等并保存。
2. 可用 **测试连接** 浏览远程目录试播。
3. 在刮削页对远程路径执行扫描，条目写入媒体库。

### 数据与清理

在 **刮削** Tab 点右上角 **设置**，从右侧侧栏可改刮削选项、打开 **管理媒体目录**、**数据占用与清理**。  
也可在底栏 **设置** → **打开刮削选项与管理目录** 进入同一侧栏。

## 从源码构建

### 环境

- JDK 17
- Android SDK（`compileSdk` / `targetSdk` 34）
- 本仓库根目录即 Gradle 工程（含 `gradlew`）

### Linux / macOS / Termux

```bash
git clone https://github.com/liyw0205/MediaVault.git
cd MediaVault
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"   # 按本机路径修改

./gradlew assembleDebug --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

在 `devwork` 工作区也可用脚本（会复制为带版本号文件名；若缺少 `gradle/wrapper/gradle-8.9-bin.zip` 会自动从官方下载，构建时优先用本地 zip）：

```bash
bash pack_mediavault.sh
# → MediaVault_0.3.61_debug.apk
```

### CI

推送到 `main` 且变更涉及应用源码时，GitHub Actions 会自动 `assembleDebug`，并把 APK 附到对应版本的 Release（见 `.github/workflows/release-apk.yml`）。

## 调试日志

脚本 `log_mediavault.sh` 用于抓取与播放、远程、刮削相关的 logcat。

**在电脑上（Linux / macOS，需已 `adb devices` 连接手机）：**

```bash
./log_mediavault.sh              # 录到 Ctrl+C
./log_mediavault.sh 120            # 录 120 秒
LOG_DIR=~/Downloads ./log_mediavault.sh
```

**在 Termux（本机直接 logcat）：**

```bash
./log_mediavault.sh
```

可选环境变量：`APP_PACKAGE`（默认 `com.mediavault`）、`LOG_DIR`。

## 应用私有数据（调试）

通过 `adb` 在已 root 或 `run-as` 可用时查看，例如：

- 媒体列表、目录与远程配置、刮削记录、封面与远程缓存、播放进度等  
- 包路径：`/data/user/0/com.mediavault/files/`

应用内 **数据** 菜单可查看大致占用并清理，无需 adb。

## 仓库说明

- 公开仓库：[liyw0205/MediaVault](https://github.com/liyw0205/MediaVault)
- 请勿提交：`build/`、`local.properties`、签名密钥
- 说明与脚本以仓库内 `README.md`、`log_mediavault.sh` 为准；本地 `devwork/MediaVault` 为日常开发副本，发布前请同步到 Git 仓库。

## 许可

以仓库内 LICENSE 文件为准；若无则版权归项目维护者所有。