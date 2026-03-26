# SillyTavern Launcher Mobile / 酒馆移动启动器

<div align="center">

**简体中文** | [English](#english-version)

[![License](https://img.shields.io/github/license/al01cn/sillytavern-launcher-mobile)](https://github.com/al01cn/sillytavern-launcher-mobile/blob/main/LICENSE)
[![Android](https://img.shields.io/badge/Android-24+-green.svg)](https://www.android.com/)
[![Release](https://img.shields.io/github/v/release/al01cn/sillytavern-launcher-mobile)](https://github.com/al01cn/sillytavern-launcher-mobile/releases)

一个让您在 Android 设备上本地运行完整 SillyTavern 体验的移动启动器

</div>

---

## 📖 简介 / Introduction

SillyTavern Launcher Mobile 是一款专为 Android 打造的集成启动器。它通过在应用内嵌入 Node.js 运行时和 SillyTavern 核心，让您能够直接在手机上本地运行完整的酒馆界面，无需依赖外部服务器或配置复杂的 Termux 环境。

SillyTavern Launcher Mobile is an Android-exclusive application designed to bridge the gap between desktop AI suites and mobile convenience. By embedding a Node.js runtime and the SillyTavern core, it allows you to run the complete interface locally on your phone without relying on external servers or manual Termux setups.

> **[!IMPORTANT]**
> 
> **当前状态 / Current Status:** 本项目目前处于 **Demo/原型阶段** / This project is in the **Demo/Proof-of-Concept stage**.
> 
> **平台支持 / Platform Support:** 目前仅支持 Android 端。由于开发者暂无 iOS 设备且 iOS 系统对本地运行时环境限制较多，目前暂无开发苹果端 App 的计划。
> 
> Currently, only Android is supported. We are unable to develop an iOS version at this time due to the lack of Apple hardware and the restrictive nature of the iOS ecosystem regarding local runtimes.

---

## ✨ 主要功能 / Features

### 中文

- **🚀 一键启动** - 无需复杂配置，点击按钮即可部署并开启酒馆环境
- **📦 全集成方案** - 内置 Node.js、Git 以及所有必要的运行环境依赖
- **🔧 插件支持** - 自动初始化 Git 环境，完美支持酒馆插件系统的扩展
- **🌐 原生交互** - 采用 Android 原生 WebView，提供沉浸式的全屏流畅交互
- **🔒 隐私保护** - 100% 本地运行，所有聊天记录和配置均保存在您的手机中

### English

- **🚀 Instant Setup** - Deploy and start the SillyTavern environment with a single tap
- **📦 All-in-One** - Pre-bundled with Node.js, Git, and all necessary dependencies
- **🔧 Extension Ready** - Automatic Git initialization to support SillyTavern plugins
- **🌐 Native WebView** - Smooth, full-screen interaction within the native Android container
- **🔒 Privacy First** - 100% local execution; your data never leaves your device

---

## 📋 系统要求 / Prerequisites

| 项目 / Item | 要求 / Requirement |
|------------|-------------------|
| **Android 版本** / Version | 7.0 (API 24) 或更高 / or higher |
| **存储空间** / Storage | 至少 1GB 可用空间 / At least 1GB free space |
| **架构** / Architecture | armeabi-v7a, arm64-v8a, x86_64 |

> **💡 提示 / Note:** 由于应用内置了完整的 SillyTavern 源码及 Git 环境，安装包体积约为 **500MB+**，解压后占用空间会进一步增加。
> 
> The APK size is approximately **500MB+** as it includes the full SillyTavern source code and Git environment.

---

## 🛠️ 技术栈 / Technology Stack

### 核心容器 / Core Container

- **Node.js Runtime** - 基于 libnode 提供的嵌入式 Node.js 运行时
- **Embedded JavaScript Engine** - Cross-platform JavaScript runtime

### 版本管理 / Version Control

- **Git Binary** - 集成了来自 Termux 的 Git 二进制可执行文件
- **Integrated from Termux** - Git binaries compatible with Android

### 解压引擎 / Compression Engine

- **Apache Commons Compress** - 处理 7z 源码包
- **7z Archive Extraction** - Handle compressed source code packages

### 构建环境 / Build Environment

- **NDK**: 28.2
- **Gradle**: 9.1
- **CMake**: 3.22.1

---

## 📥 安装方法 / Installation

### 方式一：直接安装（推荐）/ Direct Install (Recommended)

从 [Releases](https://github.com/al01cn/sillytavern-launcher-mobile/releases) 页面下载最新的 APK 文件安装即可。

Download the latest APK from the [Releases](https://github.com/al01cn/sillytavern-launcher-mobile/releases) page and install it.

### 方式二：从源码构建 / Build from Source

```bash
# 克隆仓库 / Clone repository
git clone https://github.com/al01cn/sillytavern-launcher-mobile.git
cd sillytavern-launcher-mobile

# 或使用 Gitee 镜像 / Or use Gitee mirror
git clone https://gitee.com/al01/sillytavern-launcher-mobile.git
cd sillytavern-launcher-mobile

# 构建调试版本 / Build debug version
./gradlew assembleDebug

# APK 输出位置 / APK output location
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 🚀 使用指南 / Usage Guide

### 首次使用 / First Time Use

1. **资源初始化** / Initialization  
   首次启动时，应用会自动解压内置的资源并配置环境。由于包体较大，请耐心等待完成。
   
   On first launch, the app will automatically extract resources and configure the environment. This may take a while due to the large package size.

2. **启动服务** / Start Server  
   点击界面上的 **"一键启动酒馆"** 按钮。
   
   Tap the **"Start SillyTavern"** button on the interface.

3. **开始体验** / Start Experience  
   控制台显示服务就绪后，应用会自动跳转至酒馆的 Web 交互界面。
   
   Once the backend is ready, the app will automatically switch to the SillyTavern web interface.

---

## 📝 注意事项 / Important Notes

### 📦 包体说明 / Package Size

为了实现开箱即用，我们把完整的 Node.js 环境、Git 执行文件以及酒馆源码全部打包进了 APK，因此安装包会显得比较"臃肿"（约 500MB+）。

To achieve out-of-the-box functionality, we've bundled the complete Node.js environment, Git executables, and SillyTavern source code into the APK, making the installation package relatively "large" (approximately 500MB+).

### ⚠️ 免责声明 / Disclaimer

本项目为一个**开发者 Demo**。尽管功能可用，但在不同机型上可能存在 WebView 兼容性问题。

This project is a **Developer Demo**. While functional, there may be WebView compatibility issues across different device models.

### 🔐 权限说明 / Permissions

- **存储权限** - 用于管理 SillyTavern 文件和配置
- **网络权限** - 用于本地服务通信（127.0.0.1）
- **Storage** - For managing SillyTavern files and configurations
- **Network** - For local service communication (127.0.0.1)

---

## 🙏 鸣谢 / Acknowledgments

### 核心项目 / Core Projects

- **[SillyTavern](https://github.com/SillyTavern/SillyTavern)** (v1.16.0) - 核心 AI 聊天界面 / Core AI chat interface
- **[Node.js](https://nodejs.org/)** (libnode) - 跨平台 JavaScript 运行时 / Cross-platform JavaScript runtime

### 特别感谢 / Special Thanks

- **[Termux](https://termux.dev/)** - 提供了可在 Android 上运行的 Git 二进制执行文件支持 / Provided compatible Git binaries for Android

---

## 📞 支持与反馈 / Support & Feedback

如有问题或建议，请通过以下方式联系我们：

If you have any questions or suggestions, please contact us via:

- [GitHub Issues](https://github.com/al01cn/sillytavern-launcher-mobile/issues)
- [Gitee Issues](https://gitee.com/al01/sillytavern-launcher-mobile/issues)

---

<div align="center">

**Made with ❤️ for the AI Community**

[GitHub Repository](https://github.com/al01cn/sillytavern-launcher-mobile) | [Gitee Mirror](https://gitee.com/al01/sillytavern-launcher-mobile)

</div>

---

# English Version

## 📖 Introduction

SillyTavern Launcher Mobile is an Android-exclusive application designed to bridge the gap between desktop AI suites and mobile convenience. By embedding a Node.js runtime and the SillyTavern core, it allows you to run the complete interface locally on your phone without relying on external servers or manual Termux setups.

> **[!IMPORTANT]**
> 
> **Current Status:** This project is in the **Demo/Proof-of-Concept stage**.
> 
> **Platform Support:** Currently, only Android is supported. We are unable to develop an iOS version at this time due to the lack of Apple hardware and the restrictive nature of the iOS ecosystem regarding local runtimes.

## ✨ Features

- **🚀 Instant Setup** - Deploy and start the SillyTavern environment with a single tap
- **📦 All-in-One** - Pre-bundled with Node.js, Git, and all necessary dependencies
- **🔧 Extension Ready** - Automatic Git initialization to support SillyTavern plugins
- **🌐 Native WebView** - Smooth, full-screen interaction within the native Android container
- **🔒 Privacy First** - 100% local execution; your data never leaves your device

## 📋 Prerequisites

| Item | Requirement |
|------|-------------|
| **Android Version** | 7.0 (API 24) or higher |
| **Storage** | At least 1GB free space |
| **Architecture** | armeabi-v7a, arm64-v8a, x86_64 |

> **Note:** The APK size is approximately **500MB+** as it includes the full SillyTavern source code and Git environment.

## 🛠️ Technology Stack

### Core Components

- **Node.js Runtime** - Embedded JavaScript engine based on libnode
- **Git Binary** - Integrated from Termux for Android compatibility
- **Compression** - Apache Commons Compress for 7z archive extraction

### Build Environment

- **NDK**: 28.2
- **Gradle**: 9.1
- **CMake**: 3.22.1

## 📥 Installation

### Option 1: Direct Install (Recommended)

Download the latest APK from the [Releases](https://github.com/al01cn/sillytavern-launcher-mobile/releases) page and install it.

### Option 2: Build from Source

```bash
# Clone repository
git clone https://github.com/al01cn/sillytavern-launcher-mobile.git
cd sillytavern-launcher-mobile

# Or use Gitee mirror
git clone https://gitee.com/al01/sillytavern-launcher-mobile.git
cd sillytavern-launcher-mobile

# Build debug version
./gradlew assembleDebug
```

## 🚀 Usage

### First Time Use

1. **Initialization** - On first launch, the app will automatically extract resources. This may take a while due to the large package size.

2. **Start Server** - Tap the **"Start SillyTavern"** button on the interface.

3. **Experience** - Once the backend is ready, the app will automatically switch to the SillyTavern web interface.

## 📝 Important Notes

### Package Size

To achieve out-of-the-box functionality, we've bundled the complete Node.js environment, Git executables, and SillyTavern source code into the APK, making the installation package relatively "large" (approximately 500MB+).

### Disclaimer

This project is a **Developer Demo**. While functional, there may be WebView compatibility issues across different device models.

### Permissions

- **Storage** - For managing SillyTavern files and configurations
- **Network** - For local service communication (127.0.0.1)

## 🙏 Acknowledgments

### Core Projects

- **[SillyTavern](https://github.com/SillyTavern/SillyTavern)** (v1.16.0) - Core AI chat interface
- **[Node.js](https://nodejs.org/)** (libnode) - Cross-platform JavaScript runtime

### Special Thanks

- **[Termux](https://termux.dev/)** - For providing compatible Git binaries for Android

## 📞 Support & Feedback

If you have any questions or suggestions, please contact us via:

- [GitHub Issues](https://github.com/al01cn/sillytavern-launcher-mobile/issues)
- [Gitee Issues](https://gitee.com/al01/sillytavern-launcher-mobile/issues)

---

<div align="center">

**Made with ❤️ for the AI Community**

[GitHub Repository](https://github.com/al01cn/sillytavern-launcher-mobile) | [Gitee Mirror](https://gitee.com/al01/sillytavern-launcher-mobile)

</div>
