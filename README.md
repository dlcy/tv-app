# 湖南电信 IPTV Android 播放器（自制）

## 项目简介
本项目是一个 **专用于湖南电信 IPTV 网络环境的 Android 播放器应用**，用于实现电信 IPTV **单播与组播**节目的播放。
由于湖南电信 IPTV 对播放环境、时间戳、User-Agent 以及网络接入方式有严格限制，市面上暂无可直接使用的第三方应用，因此本项目采用 **自制 Android App** 的方式实现完整播放能力。
本项目仅在 **已完成 IPTV PPPoE 拨号、且可访问 IPTV 内网的网络环境** 下工作。
---

## 主要功能
* ✅ 支持 **湖南电信 IPTV**
* ✅ Android 原生应用（适配 Android TV / 机顶盒 / 电视）
* ✅ 支持 **单播（HTTP / m3u8）与组播（UDP / RTP）**
* ✅ 支持 **NTP 自动对时**
* ✅ 支持遥控器操作与频道号输入
* ✅ 支持自定义播放服务器、NTP 服务器
* ✅ 支持自定义 User-Agent 伪装

---
## 网络环境要求（必须）
> ⚠️ 本应用 **无法主动进行 PPPoE 拨号**

使用前必须满足以下条件之一：
1. 已通过路由器或上级设备完成 **IPTV 专线 PPPoE 拨号**
2. Android 设备可直接访问 **湖南电信 IPTV 内网服务器**
3. 播放前检查可访问（ping）的服务器（如 `10.x.x.x` / `172.16.x.x` 等内网地址）
未满足条件时，应用将拒绝播放。app/src/main/java/com/example/tvplayer/player/NetworkGuard.kt里面定义播放器检查（ping）的ip
---

## 播放原理说明
### 1️⃣ 单播播放（HTTP m3u8）
湖南电信 IPTV 单播地址 **必须携带时间参数**，否则服务器会拒绝播放。
#### 频道地址格式示例
```text
湖南都市,"http://{serverip}/000000002000/201500000151/index.m3u8?starttime={timestamp}"
```
#### 参数说明
* `{serverip}`
  IPTV 播放服务器地址（内网）
  示例：
  ```text
  1.1.1.1:5555
  ```
* `{timestamp}`（ISO 时间）
  ```text
  20260124T023012.00Z
  ```
* `{starttime}`（毫秒时间戳）
  ```text
  1630454400000
  ```
时间参数由 **NTP 校时结果 + 本地时间偏移** 动态生成，每次换台都会重新计算。
---

### 2️⃣ 组播播放（UDP / RTP）
* 使用 IPTV 内网组播地址
* 受网络限制，**最多同时播放 2 路**
* App 内部在换台时会强制释放播放器，避免被 IPTV 网关限流
---

## NTP 自动校时
* 应用内置 **NTP 时间同步机制**
* 不修改系统时间，仅在应用内部维护时间偏移
* 所有播放时间参数均基于 NTP 时间生成
* 支持自定义 NTP 服务器地址
---

## User-Agent 伪装（关键）
湖南电信 IPTV 会校验请求头中的 User-Agent，本项目支持自定义 UA。
默认示例：

```text
Dalvik/1.6.0 (Linux; U; Android 4.4.2; IPTV Build/KOT49H)
```
该 UA 用于模拟电信机顶盒/早期 Android IPTV 终端行为。
---

## 操作方式
### 遥控器支持
* ↑ / ↓ ：上一频道 / 下一频道
* ← / → ：频道列表
* OK ：确认播放
* 数字键：直接输入频道号换台

### 频道号输入
* 支持连续数字输入
* 超时自动确认并切换频道
---

## 配置项
以下参数可在应用内或配置文件中修改：
* IPTV 播放服务器地址 `{serverip}`
* NTP 服务器地址
* User-Agent
* 频道列表
---

## 相关项目（PC 验证工具）
在 Android 开发前，已通过 Python + VLC 在 PC 环境完成验证：
👉 **iptv-python**
[https://github.com/dlcy/iptv-python/](https://github.com/dlcy/iptv-python/)
该项目用于验证：
* IPTV 播放地址格式
* 时间戳规则
* User-Agent 要求
* IPTV 内网访问可行性
 
Android App 的播放逻辑即基于该验证结果实现。
---

## 免责声明
* 本项目仅用于 **个人技术研究与学习**
* 仅适用于已合法开通湖南电信 IPTV 服务的网络环境
* 请勿用于任何商业用途
---



