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

## 音量键「自己跑到 0 / 100」：固件长按加速，不是守护程序

用户报「加音量自动拉到 100，减音量自动拉到 0」。测量结论：**全部是酷开固件 `com.tianci.system`
自己的按键重复 + 长按加速**，与 launcher、`com.jim.tvhomehijack` 无关。

取证方法（shell 已在 `input` 组，`/dev/input/event7` 可写，不需要 root）：

```bash
# 按住 3 秒，每秒读一次
adb shell 'media volume --stream 3 --set 60; sendevent /dev/input/event7 1 114 1; sendevent /dev/input/event7 0 0 0;
           sleep 1; settings get system volume_music_speaker;
           sleep 1; settings get system volume_music_speaker;
           sleep 1; settings get system volume_music_speaker;
           sendevent /dev/input/event7 1 114 0; sendevent /dev/input/event7 0 0 0'
```

| 操作 | 结果 | 折算 |
| --- | --- | --- |
| 单击 50 ms | 11 → 10 | 1 格 |
| 按住 0.6 s | 11 → 4 | 约 12 格/秒 |
| 按住 3 s，每秒采样 | 60 → 48 → 23 → 0 | 第 1 秒 12 格，第 2 秒 25 格：**1 秒后开始加速** |
| 松开后 2 s、5 s | 数值不再变化 | 松手即停，没有卡键 |
| 把 `HomeKeyService` 整个从无障碍列表移除后重测 | 与上面完全一致 | 我们的服务不参与 |
| 把 `consume_volume_keys` 设成 true 后重测 | 60 → 2（1.2 s） | **更糟**：固件照样执行，我们的重复是叠加的 |

要点：

- `dumpsys audio` 的 `volume changes` 里每一笔都是 `from com.tianci.system`，没有一笔来自 `com.gathin.tvhome`。
- `ro.config.media_vol_steps=100`（普通 Android TV 是 15~30 格），配合加速，长按 4 秒就能横穿整个量程，
  观感就是「一按冲到底」。
- 这台机器**不要**开 `consume_volume_keys`：固件在无障碍过滤之前就已经处理过一次音量键，吞掉按键只会让
  我们自己的步进叠加在固件步进上（实测 1.2 秒从 60 掉到 2）。
- 另一个独立发现：`config.json` 里 `key_filter: false` 在这台固件上**不生效**——重绑服务后 `dumpsys
  accessibility` 里 `主页键` 仍是 `capabilities=8`（`FLAG_REQUEST_FILTER_KEY_EVENTS`），
  `setServiceInfo` 清掉的标志被系统又装回去了。它不影响音量结论（服务整个停掉行为不变），
  但想彻底不参与按键过滤只能不启用该服务。
- 无解的部分：加速逻辑在固件内部，没有用户可关的开关（`SkySystemService.apk` 里只有 `key action
  accelerate`、`downRepeatCount` 这类代码，以及酒店模式的 `setHotelMaxmunVolume` 限幅）。
  实际建议是短按（一次一格）而不是按住。

## 视频壁纸（Apple Aerial）在这台机上的三个坑

视频壁纸**没有界面开关**，只能写 `config.json`：`"wallpaper_video_enabled": true`（内置 28 段 Apple Aerial，
`wallpaper_video_manifest` 默认 `aerial`；也可以填自己的 `{"videos":[{"url":"..."}]}` 清单 URL 或 assets 文件名）。
开了之后桌面纯色、不出画，是三个独立问题叠在一起：

| 现象 | 原因 | 修法 |
| --- | --- | --- |
| 一开就闪退，`RuntimeException: UnsupportedOperationException: TextureView doesn't support displaying a background drawable` | `setupVideoLayer` 给 `TextureView` 设了背景色，它不允许 | 背景色留在 root 上 |
| 不闪退了，但背景是纯色，`logcat` 里只有 `video layer up: 28 clips`，没有 `video size` | 自己的 `setSurfaceTextureListener` **覆盖**了 `setVideoTextureView` 装上的那个，播放器收不到纹理可用通知，一直 `state=2` 缓冲，`surface=false` | 保留播放器的监听器，把 `applyCrop` 串在它前面调用 |
| 出画后又黑屏/回退静态 | 内置清单是 `https://sylvan.apple.com/...`，这个域名在国内只有 80 端口可用：`http` 返回 `206`，`https` 连不上（同期 `apple.com`、`gitee.com` 的 443 都正常） | `WallpaperVideo.playable` 把 sylvan 的 https 换成 http，其他域名原样保留 |

验证（`screen 1920x1080`、`video size 1920x1080` 出现在 `logcat`，`dumpsys gfxinfo com.gathin.tvhome` 的
`Total frames rendered` 从 24 涨到 863，隔 3 秒两张截图 md5 不同，画面是 Aerial 的地球夜景）。

另外两个容易误会的点：

- 设置里那行 **“360 壁纸（live）”的 live 指在线拉图，不是动态视频**，开了它仍然是静态照片。
- 只要用户收藏并选中过某张静态壁纸（`WallpaperFavorites.selected`），`Config.load` 会强制
  `wallpaperVideoEnabled = false` —— 静态壁纸优先级高于视频壁纸，这是设计如此。要让视频壁纸生效，
  先在设置里取消选中收藏的那一张。

## 程序坞里只有「应用圈」一个系统应用可选

这台固件把桌面应用分成两套入口：只有 6 个应用声明了 TV 入口（`CATEGORY_LEANBACK_LAUNCHER`），
其中系统应用只有 `com.tianci.appstore`（应用圈）；设置、媒体播放器、电视管家、主题、屏保等二十来个只声明
普通的 `MAIN` + `LAUNCHER`。桌面默认只列声明 TV 入口的那些，所以“编辑程序坞”时能挑的只有这 6 个，
想放个“设置”进去无处可选。

```bash
# 有 TV 入口的（桌面默认显示）
adb shell 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER'
# 只有普通入口的（默认不上桌面）
adb shell 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER'
```

最初的修法是加一个 `home_all_apps` 开关：写 `"home_all_apps": true` 到 `config.json`（或 `config.xml` 的同名项）
之后，桌面列出全部可启动应用，长按卡片即为「添加到程序坞 / 移动 / 从主屏幕移除」。这些卡片用普通 `MAIN` 启动，
不像 TV 应用那样带 leanback 分类；实测 `com.coocaa.os.softsettings`（设置）、`com.tianci.localmedia`（媒体播放器）、
`com.coocaa.tvmanager`（电视管家）用 `am start -a android.intent.action.MAIN -n <组件>` 都能正常打开。

全开也把固件里的后台服务摆上了桌面（`CooDataServer`、`SkyAOD`、`tvhome 接管` 之类），不影响使用，
嫌乱就长按卡片「从主屏幕移除」，或用 `hidden_apps` 一次排掉。

### 后来的做法：不进桌面，直接从应用库放程序坞

`home_all_apps` 把二十多个系统应用一起摊到桌面上，太乱，所以这个开关**已删除**。现在走应用库：
应用库的目录本来就枚举全部已安装包（两套入口都查，还兜了没有入口的记录），长按任意一张卡片多出
「添加到程序坞」这一行；选中后先给它补一张桌面卡片（写 `library_apps.txt`），再进入程序坞选位模式，
按确定替换掉那一格，被换下来的应用落到这张新卡原来的位置。

程序坞本身没有独立数据：它就是桌面前 `COLUMNS` 个格子（`AppAdapter` 的 `dockFilled()` + `order.txt` 的
`docked`），所以“进程序坞”必须先有桌面卡片，这也是之前只能从桌面上已有卡片里挑的原因。

## 断网开机停在原生桌面：是开机时序，不是守护被断网

现象：**有网**时断电开机正常进我们的桌面；**拔掉网线/路由器没网**时断电开机，最后停在酷开原生桌面。

排查结论（无需抓包即可确定）：

- `com.jim.tvhomehijack`（开机守护）1.0 的 `classes.dex` 里没有任何网络类，全部引用只有
  `Activity` / `View` / `TextView` / `Log` / `Intent`；它的两个入口是 `BootReceiver`（`BOOT_COMPLETED`）和
  `HijackService`（无障碍窗口事件转 `startActivity` 回桌面）。两者都不需要网络，所以「没网 → 守护不工作」
  不是权限或联网问题。
- 真正的原因是桌面这边**只发了一次**：`HomeKeyService` 在开机窗口内只 `postDelayed(800 ms)` 打开一次 Home。
  没网时酷开先停在「网络未连接」页（不是回答 HOME 的那个 activity），等它自己收场、原生桌面回来时，
  早就过了那 800 ms；而 `stock_home_redirect` 走的 `queryIntentActivities` 在这台机上取不到原厂 HOME
  activity，集合是空的，重定向也就没有生效点。
- 另外，普通 App **不能**替守护发 `BOOT_COMPLETED`（受保护广播，非系统 uid 直接 `SecurityException`），
  所以「桌面替守护广播一次」这条路走不通，只能在桌面侧兜底。

修复（`launcher`，不依赖网络、不依赖守护在不在）：

- `power_on_home_always` 从一击改成链式重试：0.8 / 4 / 10 / 22 / 45 秒各试一次，**直到我们自己的窗口
  成为前台**（`homeInFront`，由窗口事件置位）或用户按了键（`powerOnUntil` 清零）就停。断网开机厂商界面
  晚十几秒才收场也能被拉回来。
- `stock_home_redirect` 增加**按包名**命中：原厂包的任何窗口（含开机那张网络提示页）都算，不再只认
  回答 HOME 的那个 activity 类名。

复验方法（需要真机、且这次要**断网**）：拔掉电视网线 → 断电 → 通电 → 只按一次遥控器电源键 →
等 60–90 秒，看是否回到我们的桌面；`dumpsys window | grep mCurrentFocus` 应是
`com.gathin.tvhome/.MainActivity`。断网时 ADB 连不上，属正常，恢复网络后再回读设置。

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
