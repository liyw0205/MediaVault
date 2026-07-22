# MediaVault

MediaVault 是一个 Android 原生媒体库应用，用于管理本机文件夹与 WebDAV / FTP / SMB 远程目录中的视频资源。应用提供媒体入库、搜索、合集、刮削、续播、远程播放缓存和全屏播放器，不依赖 WebView 或 CGI。

| 项目 | 说明 |
|------|------|
| 包名 | `com.mediavault` |
| 最低系统 | Android 8.0（API 26） |
| 编译目标 | Android SDK 34 |
| 当前版本 | **0.4.14**（versionCode 82） |

## 0.4.x 方向

0.4.x 的功能面已经基本完整，当前主线是稳定一套同时适配手机和横屏大屏的 UI，而不是扩张新业务能力。

- **一个 APK、一套业务**：不拆独立 Android TV 应用，不引入第二个 `applicationId`。
- **竖屏手机态 + 横屏融合态**：横屏或 Android TV 类设备自动进入融合 UI；竖屏保持手机触控布局。
- **不提供手动“界面”开关**：界面由设备配置和方向自动决定。
- **触控与遥控共存**：融合态保留触控，同时加强焦点链、D-pad 与返回键行为。

## 功能概览

| 区域 | 说明 |
|------|------|
| 媒体库 | 本机目录、WebDAV / FTP / SMB；浏览、搜索、合集、续播进度 |
| 刮削 | 增量扫描；NFO、同目录封面、文件名解析、远程 sidecar / 抽帧 |
| 播放 | 全屏播放、手势 seek、字幕、截图、进度记忆、连播策略 |
| 远程播放 | 网络拉流，已播放片段本地缓存，减少重复下载 |
| 数据管理 | 应用内查看媒体库、刮削记录、封面和远程缓存占用并清理 |

## 信息架构

- 底部导航 / 横屏导航轨：**主页、搜索、合集、刮削**。
- **刮削 / 数据 / 目录管理** 只从 **刮削 Tab -> 顶栏设置 -> 右侧侧栏** 进入。
- 没有底栏“设置”Tab。
- 连播设置在播放器 **列表 -> 连播** 中调整。

## 安装

在 [GitHub Releases](https://github.com/liyw0205/MediaVault/releases) 下载 `MediaVault_<版本>_debug.apk`，安装后按需授予存储和网络权限。

## 使用

### 添加本机媒体

1. 进入 **刮削** Tab。
2. 打开右上角 **设置**，进入 **管理媒体目录**。
3. 点 **添加文件夹**，通过系统文档选择器授权目录。
4. 回到刮削页，对目录执行增量刮削或重扫。

### 添加远程目录

1. 进入 **刮削** Tab -> **设置** -> **管理媒体目录**。
2. 选择 **WebDAV** / **FTP** / **SMB**，填写主机、账号和路径等配置。
3. 使用 **测试连接** 浏览远程目录。
4. 保存后在刮削页扫描远程目录，条目会写入媒体库。

### 播放与续播

- 从主页、搜索、合集或详情页进入播放。
- 播放器支持快进/快退、手势 seek、字幕、截图和连播。
- 本机与远程条目都会记录播放进度。

## 从源码构建

### 环境要求

- JDK 17
- Android SDK 34
- Gradle Wrapper（仓库内已包含）

### 常规环境

```bash
git clone https://github.com/liyw0205/MediaVault.git
cd MediaVault
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

./gradlew assembleDebug --no-daemon
```

产物位于：`app/build/outputs/apk/debug/app-debug.apk`

### Termux / 本地工作区

Termux 下必须使用仓库脚本，它会临时设置可执行的 aarch64 `aapt2`，并在退出时恢复 `gradle.properties`。

```bash
bash pack_mediavault.sh
# 生成 ../MediaVault_0.4.14_debug.apk
```

不要把 `android.aapt2FromMavenOverride` 长期写入 `gradle.properties`。

## 调试日志

脚本 `log_mediavault.sh` 用于抓取与播放、远程和刮削相关的 logcat。

```bash
./log_mediavault.sh
./log_mediavault.sh 120
LOG_DIR=~/Downloads ./log_mediavault.sh
```

可选环境变量：

- `APP_PACKAGE`：默认 `com.mediavault`
- `LOG_DIR`：日志输出目录

## 应用数据

应用私有数据位于 `/data/user/0/com.mediavault/files/`，包含媒体库、目录配置、远程配置、刮削记录、封面、远程缓存和播放进度等。

应用内 **数据** 菜单可以查看占用并清理，无需 adb。

## 开发与发布

开发主线遵循以下约束：

- 优先维护既有功能和横屏融合 UI 稳定性。
- 竖屏触控和横屏 D-pad 都必须保持可用。
- 新业务能力默认不加入，除非明确决定。
- 每版至少验证冷启动、四 Tab、搜索、刮削侧栏、远程浏览/播放和播放器。

发布前建议流程：

```bash
bash pack_mediavault.sh
git status --short
```

推送到 `main` 后，GitHub Actions 会执行 `assembleDebug`，并把 APK 附到对应版本 Release（见 `.github/workflows/release-apk.yml`）。

## 仓库说明

- 公开仓库：[liyw0205/MediaVault](https://github.com/liyw0205/MediaVault)
- 请勿提交：`build/`、`.gradle/`、`local.properties`、签名密钥、本地 APK、logcat 日志
- 本地开发备忘和会话交接文档不属于仓库内容，以仓库内 README 和提交记录为用户可见说明

## 许可

以仓库内 LICENSE 文件为准；若无 LICENSE，则版权归项目维护者所有。
