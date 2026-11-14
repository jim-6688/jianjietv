# 吾爱TV助手（手机版）

Android 原生手机 App，用 ADB-over-TCP 直连同一局域网里的 Android TV，不依赖电脑、不需要 root、
不需要手机上有 `adb` 二进制。与 TV 端 `launcher/`、仓库 `tools/` 是同一套安全规则。

## 本轮交付范围

用户确认的口径：形态为 **Android 原生 App**；本轮打通 **1/2/3**；「卸载」遵循
**系统包只禁用、第三方才可卸载、核心包一律不动**；ADB **保持开启 + App 内一键关闭**。

| # | 功能 | 状态 | 说明 |
|---|---|---|---|
| 1 | 按电视品牌引导开启 ADB | ✅ 已实现 | `Brands.java` 覆盖常见品牌；非 Android TV 明确停止；不引导工厂菜单 |
| 2 | 手机自动扫描 ADB 端口发现设备 | ✅ 已实现 | mDNS 自动发现（`_adb._tcp`）+ 深度扫描 + 手动 IP，诊断信息明确 |
| 3 | 发现设备后安装 TV 版并加入守护 | ✅ 已实现 | sync 推送 APK → `pm install -r` → 启动校验 → 设为默认 Home → 启用按键助手 |
| 4 | 管理 TV 软件（卸载/禁用） | ✅ 已实现 | `AppManagerActivity` 列第三方（卸载）/系统（停用·启用），带保护名单 |
| 5 | 手机管理 TV 数据源 | ✅ 已实现 | `SourcesActivity` 直连 TV `ConfigServer`（8899）：切源 / 上传 py，回显当前源 |
| 6 | TV 遥控器与品牌遥控码 | ✅ 已实现 | 通用键 + 应用快捷键按品牌自动选专属键码（`RemoteCodes.java`） |
| 7 | 手机配置同步到 TV | ✅ 已实现 | `SyncActivity` 编辑列数/dock/hidden/键码 → 生成 config.json → 推电视 → 重启桌面 |

七个功能已全部完成。主页「设备操作」与「功能汇总」页都能直达。

## 目录

```
mobile/
  app/src/main/java/com/gathin/tvhome/mobile/
    MainActivity / BrandGuideActivity / ScanActivity / SetupActivity / MoreActivity
    AppManagerActivity  功能4：TV 软件管理
    SourcesActivity     功能5：TV 数据源管理（HTTP 对接 TV ConfigServer）
    RemoteActivity      功能6：TV 遥控器（含品牌键码）
    SyncActivity        功能7：配置同步到 TV
    ToolActivity        通用页面基类（标题 + 状态条 + 内容区）
    Brands.java         品牌开启路径资料
    RemoteCodes.java    品牌识别 + 应用专属键码表（可扩展）
    Prefs.java          当前电视 IP 与守护状态（仅本机，不入库）
    adb/AdbPacket.java  ADB 包头、CRC32、包读写
    adb/AdbKey.java     RSA 2048、adb 公钥格式、TOKEN 签名
    adb/Adb.java        CNXN/AUTH/OPEN/WRTE/OKAY/CLSE、shell、sync push、关闭 ADB
```

## UI 方案

手机版界面用**自绘的 Material Outlined 风格矢量图标集**（`res/drawable/ic_*.xml`，24dp 描边、
纯 `<path>` 表达），配色走 `res/values/colors.xml` 的品牌令牌：`brand #006D5B` / `brand_container`
`#DCF1EA` / `on_surface` 等。卡片用 `FrameLayout` + 圆角描边 shape（`bg_card.xml` / `bg_card_status.xml`）
加框架自带的水波纹（`?android:attr/selectableItemBackground`）实现，顶栏是自定义 header（标题 +
返回键），不依赖任何三方 UI 库。

> 为什么不用 Material 组件：本机只有 SDK 34 + AGP 8.5.2，`com.google.android.material:material`
> 会强制拉入 `androidx.core:core:1.16.0`，要求 compileSdk 35 / AGP 8.6，离线环境升不上去。
> 因此 UIC 全部用框架原生控件 + 自绘矢量图标，零额外运行时依赖（APK 仅 ~3.4MB）。

## 构建

```bash
cd launcher && export JAVA_HOME=/c/tools/jdk21 && ./gradlew :app:assembleDebug   # 先产出 TV 版
cd ../mobile && export JAVA_HOME=/c/tools/jdk21 && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

`mobile/app/build.gradle` 的 `copyTvApk` 会把 `launcher/app/build/outputs/apk/debug/app-debug.apk`
复制到 build 生成目录作为 `assets/tvhome.apk`，**不入库**。没有现成 TV APK 时手机版仍可构建，
但第 3 步会提示先构建 TV 版。

## 与 TV 端的关系

- 安装顺序照 `launcher/build-and-install.sh` 的 Home guard：先问 SDK ≥ 26、先装、先用
  `am start -W` 确认桌面能起来，才 `cmd package set-home-activity`。
- 本轮**不禁用原厂桌面**，所以不会出现「两个 Home 都没了」；回退只需在电视设置里改默认桌面。
- 按键助手 `HomeKeyService` / `InputKeyService` 的启用会**保留**电视上原本已启用的其它无障碍服务。
- 网络 ADB 5555 是明文端口。用户已确认保持开启以便随时管理，App 第 3 步提供「一键关闭」
  （`settings put global adb_enabled 0` + `setprop service.adb.tcp.port -1`）并用 TCP 探测校验；
  若仍开放，会提示到开发者选项手动关闭，且提醒无线调试还有独立端口。

## 已知限制 / 真机验证项

- ADB 协议客户端是纯 Java 新实现，包编解码与安全规则有 JVM 单测覆盖，但**尚未在真机上跑通**
  （验证时那台 S81 电视的 5555 被拒绝连接）。sync 推送、RSA 授权、各功能端到端需实机确认。
- `ro.adb.secure=1` 的电视第一次连接会弹「允许 USB 调试」；App 会送公钥并提示勾选「一律允许」
  后重试。Android 11+ 的**无线调试配对码**（SPAKE2/TLS）本版不支持，需走「网络 ADB 调试」路径。
- 功能 6 的品牌专属键码只内置了通用（Netflix=286）与小米（YouTube=190）等少数已知码，
  `RemoteCodes.java` 是可扩展表，待把 `docs/devices/` 更多机型 scancode 补进来。
- 功能 7 推送后通过 `am force-stop com.gathin.tvhome` 重启桌面让 config.json 生效；
  极少数固件 force-stop 后不会自动重启 Home，此时按一次电视主页键即可。
- 功能 5 依赖电视端 `ConfigServer` 的 8899 端口（TV 端桌面进程常驻启动）；若电视桌面没运行则连不上。
