# 创维 9TA01_S81P（酷开 Android 9）：断电开机不自动回桌面

一次会话记录，2026-11-14。设备是国行创维 S81P，酷开系统；只针对这一台实测，不代表其它酷开机型。
相关背景见 `docs/devices/zh-cn-domestic-android-tv.md`。

## 设备

| 项 | 值 |
| --- | --- |
| `ro.product.brand` / `model` | `Skyworth` / `9TA01_S81P` |
| Android / SDK | 9 / 28 |
| 网络 ADB | `<tv ip>:5555` |
| launcher | `com.gathin.tvhome`，`versionCode 12` / `3.3` |
| 原厂桌面 | `com.tianci.movieplatform` / `com.coocaa.homepage.vast.HomePageActivity` |
| 息屏显示（AOD） | `com.coocaa.aod` |
| 用户自装接管工具 | `com.jim.tvhomehijack`（`BootReceiver` + `HijackService`），修复前已在运行 |

## 现象

覆盖安装 launcher 之后，桌面进程在跑、Home 也解析到 `com.gathin.tvhome/.MainActivity`，但屏幕是纯黑；
`adb reboot` 或断电重新通电开机后，桌面不会自己出现在最前面，需要手动拉起。

## 根因：三层叠加，缺一层都还会复发

1. **酷开 AOD 全屏黑窗盖住桌面。** `com.coocaa.aod` 是 uid 1000 的系统级悬浮窗
   （`SYSTEM_ALERT_WINDOW`，`ty=-20032003`，1920×1080，`isVisible=true`）。
   `dumpsys window windows` 里 `mCurrentFocus=Window{... AodUI}`，而 `mFocusedApp` 一直是我们自己的
   `MainActivity`；`uiautomator dump` 只能导出这个黑窗。所以不是 launcher 没启动，是看不见。
2. **默认 Home 没有持久写入。** 只执行 `adb install -r` 不会写 HOME 首选，覆盖安装还会把旧的首选清掉。
   必须用 `cmd package set-home-activity`。
3. **厂商开机时抢前台。** `mLastDisplayFreezeDuration ... due to
   com.tianci.movieplatform/com.coocaa.homepage.vast.HomePageActivity` 说明原厂桌面在开机阶段到过前台；
   launcher 自带的 `HomeKeyService` 当时根本没启用（无障碍列表里只有厂商的 `AssistanceService` 和用户自装的
   `HijackService`）。注意这台机器的 `query-activities --brief ... CATEGORY_HOME` 只返回一个候选
   （我们的 launcher），原厂桌面不是靠 HOME intent 起来的，所以**不要**把它加进
   `KNOWN_STOCK_LAUNCHERS` 去禁用——实测没有禁用它，禁用会连带拿掉酷开的影视平台。

## 修复（全部可逆，不禁用、不卸载任何包）

```bash
# 1) 持久默认 Home
adb shell 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'

# 2) 开机守护：给 launcher 的 HomeKeyService 授权并挂到无障碍列表（保留原有服务）
adb shell 'pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS'
adb shell 's=$(settings get secure enabled_accessibility_services); case "$s" in *com.gathin.tvhome/.HomeKeyService*) ;; null|"") settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService ;; *) settings put secure enabled_accessibility_services "$s:com.gathin.tvhome/.HomeKeyService" ;; esac; settings put secure accessibility_enabled 1'

# 3) 运行时配置：开机 3 分钟内把前台拉回桌面、重装后自恢复服务、原厂桌面出现就重定向
adb shell "printf '%s' '{\"power_on_home_always\":true,\"restore_key_services\":true,\"stock_home_redirect\":\"com.tianci.movieplatform\"}' > /sdcard/Android/data/com.gathin.tvhome/files/config.json"
```
> 若该文件已存在（主题配置），**不要整份覆盖**：读出 JSON 后只合并上面三个键再写回。
> `stock_home_redirect` 依赖 `MATCH_DISABLED_COMPONENTS` 能查到原厂 HOME activity，本机可能查不到，
> `power_on_home_always` 才是主力。

```bash
# 4) 关掉 AOD 悬浮窗；顺序必须是先 deny 再落盘，否则重启会丢
adb shell 'cmd appops set com.coocaa.aod SYSTEM_ALERT_WINDOW deny'
adb shell 'appops write-settings'
```

`AUTO_START` / `APP_AUTO_START` 这两个 app-op 在 Android 9 上不识别（`appops get` 里看不到），
给了也无害；其它创维/TCL 固件可能需要，所以安装脚本里保留。

**一键重放**：`DOMESTIC_TV=1 TV_HOST=<tv ip> launcher/build-and-install.sh`（Windows 用
`launcher\build-and-install.ps1 -TvHost <tv ip> -DomesticTv`）。该开关会自动带出
`HOME_KEY_SERVICE=1`、`RESTORE_KEY_SERVICES=1`、`STOCK_LAUNCHER=none`，合并式写入配置，
并默认关闭（不影响其它设备）。

> 本机限制：这台开发机上 `:app:assembleRelease` 会卡在既有的
> `lintVitalAnalyzeRelease: For input string: "36.1"`，所以完整安装脚本在这里跑不通；
> 上面 1–4 的 adb 命令才是本次实际落地并验证的路径。若想在这台机器上走一键流程，
> 先把 debug 包放到 release 产物路径（两者同为 debug 签名，可互相覆盖），再跳过构建：
>
> ```bash
> cp launcher/app/build/outputs/apk/debug/app-debug.apk launcher/app/build/outputs/apk/release/app-release.apk
> SKIP_BUILD=1 DOMESTIC_TV=1 TV_HOST=<tv ip> launcher/build-and-install.sh
> ```
>
> 在能正常构建 release 的机器上直接用标准形式即可。脚本会重新覆盖安装 APK 并重启 Home，
> 当前这台电视已经修好，不必为了验证再跑一遍。

## 验证（断电 → 通电 → 遥控器电源键开机，全程未手动 `am start`、未注入按键）

| 检查 | 结果 |
| --- | --- |
| `uptime` | `1 min`，确实是新的一次冷启动 |
| `cmd package resolve-activity ... CATEGORY_HOME` | `com.gathin.tvhome/.MainActivity` |
| `mCurrentFocus` / `mFocusedApp` | 均为 `com.gathin.tvhome/.MainActivity`，AOD 黑窗消失 |
| 桌面进程 | 在跑 |
| `enabled_accessibility_services` | 厂商 `AssistanceService` + `HijackService` + 我们的 `HomeKeyService` 三项，`accessibility_enabled=1` |
| `cmd appops get com.coocaa.aod` | `SYSTEM_ALERT_WINDOW: deny`，重启后仍生效 |
| `config.json` | 三个键仍在 |
| `logcat` | 无 FATAL / AndroidRuntime |

`adb exec-out screencap -p` 截图 1.58 MB 真机确认：壁纸、天气卡片、Dock 应用名、顶栏 ★ 收藏按钮全部正常，
顶栏无挤压。用户回报「正常进入咱们桌面」。

## 通电不等于开机（这台机型的坑）

断电重新通电后电视停在**待机**：网卡和 `adbd` 都没起来，`ping` 100% 丢包、`adb connect ...:5555` 超时 10060，
必须按一次遥控器电源键才真正开机；`adb reboot` 软重启同样会落到待机。同类现象在
`examples/artel-43d1200b/README.md` 也有记录。复测时**只按电源键，别再按方向键/主页键/确认键**：
`power_on_home_always` 遇到「用户先按键」会让位，多按一下就无法判定是不是桌面自己拉起的；
开机后等 20–30 秒再连 ADB，检查时不要 `am start`。

## 恢复与已知代价

- 代价：酷开的**息屏显示（AOD）不再出现**。恢复（恢复后开机可能又变黑屏）：
  ```bash
  adb shell 'cmd appops set com.coocaa.aod SYSTEM_ALERT_WINDOW default; appops write-settings'
  ```
- 回退默认 Home：`adb shell 'cmd package set-home-activity --user 0 <原厂 HOME 组件>'`。
- 这些改动都在设备运行时设置里，**换机、恢复出厂、重装后需重放**；`launcher/build-and-install.sh`
  的 `DOMESTIC_TV=1` 分支就是为了不靠手工记命令。
- 未测试：禁用 `com.tianci.movieplatform`、关闭用户自装的 `com.jim.tvhomehijack`、以及长期待机/唤醒循环。
- 未验证：`power_on_home_always` 与 AOD 关闭各自单独生效的边界；实测通过的是三者同时生效的组合。
