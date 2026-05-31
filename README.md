# <img src="icons/icon.png" width="48" height="48" style="vertical-align: middle; margin-right: 10px;" /> 胶卷瘦身 (SlimVideo)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)]()
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-orange.svg)]()

**《胶卷瘦身 / SlimVideo》** 是一款专注于 Android 智能手机本地离线运行的**无感视频智能瘦身工具**。

传统的手机视频压缩软件在压缩后，往往会生成新文件到杂乱的子目录下，并自动重新命名（导致原本按拍摄时间排序的相册乱作一团）。本软件致力于解决这一痛点，在大原则**“完全不改变原视频目录、完全不改变原视频文件名（原位覆盖）”**的前提下，调用手机硬件编解码芯片进行静默压缩，智能同步系统相册，原地释放您珍贵的手机存储空间。

---

<p align="center">
  <img src="icons/icon.png" width="128" height="128" alt="SlimVideo App Icon" />
</p>

---

## 🌟 核心特色与功能

1. **🔒 本地安全转码，无网络隐私泄露**  
   全量转码及运算 100% 在本地运行，无任何服务器上传，保证相册隐私的绝对安全。
2. **🔄 完璧归赵：原地同位同名覆盖**  
   压缩完成后自动替换原片，完美继承原片名称及相册排序，不破坏相册的时间流。
3. **🎬 全分辨率等比自适应，完美无黑边**  
   内置**“等比边界拟合算法”**，完美适配竖屏视频（9:16）、经典视频（4:3）、正方形视频（1:1）及电影级画幅（21:9）的转码，彻底杜绝转码后左右或上下填充黑边的现象。
4. **📊 体积动态重估与防呆拦截**  
   切换档位时界面毫秒级动态预估体积。当检测到原片画质本身已低于目标档位时，自动禁用该档位，防范视频反向膨胀。
5. **🎨 多套高对比度极美主题随心切**  
   基于 Jetpack Compose 打造，支持优雅暗黑模式与“经典雅致白”主题色，界面状态栏无遮挡适配，交互流畅自然。
6. **🛠️ 极客诊断终端一键暴力转储 (Column Dumper)**  
   内置可视化调试诊断面板，能将任何 Android 系统底层数据库字段全量输出，支持一键复制，彻底攻克碎片化真机存储适配。

---

## 🚀 核心技术与架构设计

项目全面基于 **MVVM (Model-View-ViewModel)** 架构进行松耦合设计：

* **UI 展现层**：使用最新的 **Jetpack Compose** 声明式 UI，状态驱动更新。
* **业务逻辑层**：使用 Kotlin **Flow/StateFlow** 实现状态双向流动，解耦耗时计算。
* **后台引擎层**：基于 **Jetpack WorkManager** 前台高保活服务（Foreground Service），适配 Android 14 (API 34) 媒体处理权限规范。

---

## 📂 深度开发者文档索引

为了便于其他开发者进行复用和二次开发，项目编写了非常详尽的核心模块技术文档，请直接使用相对路径跳转阅读：

### 1. [📱 现代 Android 绝对路径获取与沙盒绕过方案](docs/原视频文件路径获取.md)
* *核心解决*：如何绕过 Android 13+ 照片选择器（Photo Picker）的虚拟沙盒欺骗（如 `/sdcard/.transforms/synthetic/...`），完美搜寻并定位相册真实物理路径。
* *关键技术点*：三轨解析策略（Direct Query + ID Mapping + 智能特征指纹 MediaStore 反向搜寻）。

### 2. [🎬 视频转码模块设计与自适应算法解析](docs/视频转码模块描述.md)
* *核心解决*：技术选型、智能压缩预设档位数学模型、**等比拟合无黑边算法实现**，以及 OpenGL 图形渲染上下文缺失下的**“双轨安全自适应降级重试机制”**。
* *关键技术点*：Media3 Transformer + MediaCodec 硬件加速、奇偶化整、WorkManager 前台保活。

---

## 🛠️ 项目工程结构一览

```
SlimVideo/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/slimvideo/
│   │       │   ├── data/             # 数据预设与 VideoItem 结构
│   │       │   ├── viewmodel/        # 绝对路径三轨解析与业务逻辑中心
│   │       │   ├── worker/           # H.265/H.264 后台硬解码转码引擎
│   │       │   └── MainActivity.kt   # 状态栏适配与极简多主题 UI 交互
│   │       └── AndroidManifest.xml   # 前台保活与媒体读写权限声明
│   └── build.gradle.kts
├── docs/
│   ├── 开发说明.md                   # 初始开发设计文档
│   ├── 原视频文件路径获取.md           # 绝对路径与沙盒解封技术精解
│   └── 视频转码模块描述.md             # 转码、等比缩放与降级策略精解
├── icons/
│   └── icon.png                      # App 高清正方形矢量原始图标
└── build.gradle.kts
```

---

## 📥 极速编译与运行指南

本项目完全基于 **Gradle Kotlin DSL** 构建，推荐使用最新的 **Android Studio** 进行导入。

### 1. 检查本地环境
* **JDK 版本**：Java 17 或 Java 21 
* **Android SDK**：API 21 - API 36 (Android 15 / Q+)
* **Gradle 版本**：9.4.1

### 2. 本地 Release 打包
无需连接设备，在项目根目录下通过 PowerShell 执行以下命令即可直接编译出高效率、已签名的 Release APK 包：
```powershell
# 配置 Java 运行环境并启动 Gradle 打包
$env:JAVA_HOME = "D:\Program Files\3.IDE\Android Studio\jbr"
.\gradlew.bat assembleRelease
```
*构建成功后，输出的安装包位于：`app/build/outputs/apk/release/app-release.apk`*

---

## 📄 开源许可证

本项目基于 **Apache License 2.0** 开源许可证，您可以自由分发、修改和复用本项目的任意代码模块。请确保在保留原作者版权声明的前提下，享受开源的乐趣！
