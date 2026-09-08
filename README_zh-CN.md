<div align="center">

# LogLab

**无需 root · 无需电脑 · 无需数据线，手机直接抓取手机日志的 Android 应用**

[English](README.md) | [简体中文](README_zh-CN.md)

[![Release](https://img.shields.io/github/v/release/resooo/loglab)](https://github.com/resooo/loglab/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](https://github.com/resooo/loglab/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

</div>

LogLab 是一款内嵌 ADB 协议实现的日志抓取工具。通过 Android 无线调试（Wireless Debugging）的 TLS 配对机制，让手机自己成为自己的 logcat 终端——全程不需要电脑，也不需要 root。

## ✨ 功能

- **日志抓取**：按缓冲区（main / system / crash 等）、TAG、进程、日志级别组合过滤，支持关键词检索
- **实时跟踪**：logcat -T 流式输出，后台 Service 保活；PID 跟随模式自动重新挂接重启的进程
- **启动抓取**：不等目标应用启动就开始收日志，抓「启动瞬间」不丢现场
- **应用崩溃监控**：常驻监控崩溃并保留堆栈报告；支持「今天 / 近7天」筛选、应用图标、一键补抓历史
- **一键导出**：导出为 `.log` / `.gz`，系统分享、保存到任意位置
- **应用内更新**：每 24 小时静默检查一次 GitHub Releases，显示更新说明，下载后直接拉起安装
- **127.0.0.1 优先连接**：App 与 adbd 同机，直连本机回环地址（端口由 mDNS 自动发现），换 Wi-Fi 不失效、不重扫（保留局域网 IP 回退）
- **双通道连接**：内置 ADB 协议直连（arm64），或 HostBridge 远程网关
- **双语界面**：跟随系统 / 中文 / English

## 📲 安装

1. 前往 [Releases](https://github.com/resooo/loglab/releases/latest) 下载最新 APK（arm64-v8a）
2. 安装时如提示未知来源，允许「安装未知应用」即可
3. 首次使用按引导完成一次无线调试配对（需要开发者选项中开启「无线调试」）

> 配对说明：无线调试的配对码/端口只在「使用配对码配对设备」弹窗打开期间有效，
> 部分系统（如 ColorOS）切走应用后会刷新配对码。推荐按 App 内引导使用**分屏/小窗**完成配对。

## 🔧 构建

```bash
git clone https://github.com/resooo/loglab.git
cd loglab
./gradlew assembleRelease
```

- 需要 JDK 17、Android SDK（compileSdk 35 / build-tools 34）
- release 签名默认读取 `app/keystore/debug.jks`（不入库）。本地调试可用 `assembleDebug`
- CI（GitHub Actions）通过 Secrets 注入签名环境变量：`KS_FILE`（base64 解码后的 jks 路径）、`KS_PASS`、`KEY_ALIAS`、`KEY_PASS`

## 🚀 发布流程

```bash
# 1. 更新 app/build.gradle.kts 中的 versionName / versionCode
# 2. 提交并打 tag（tag 必须与 versionName 一致）
git tag v1.8.0
git push origin main --tags
# 3. GitHub Actions 自动构建 release APK 并上传到该 Release
```

App 内「设置 → 检查更新」会拉取 `releases/latest`，按 tag 与本地版本号比较，发现新版即可在应用内下载安装。

## ⚠️ 隐私与免责声明

- 日志可能包含设备上的敏感信息（账号、Token、个人数据等），分享/导出前请自行确认
- 本应用仅使用 Android 官方无线调试能力（ADB over TLS），不申请 root，不上传任何数据
- 使用本软件产生的一切后果由使用者自行承担

## 📄 License

[MIT](LICENSE)
