# 国产电视适配与「资源改国内」指南 (zh-cn domestic Android TV)

本指南面向中国用户，说明 CleanYourTV 在国产电视/盒子上的适配情况、如何新增设备去广告清单，
以及如何把依赖境外资源的插件改成国内可用。

## 1. 先分清: Android TV 还是 HarmonyOS

CleanYourTV 的原理是 `pm disable-user` 停用预装/广告包，**只适用于 Android / Google TV**。

| 系统 | 代表设备 | 能否用本工具 |
| --- | --- | --- |
| Android TV / 国货 Android 盒子 | 小米/Redmi 电视、TCL、海信/VIDDA、创维/酷开、长虹、康佳、当贝、微鲸、运营商 Android 机顶盒 | ✅ 适用 |
| HarmonyOS | **华为智慧屏、荣耀智慧屏** | ❌ 不适用 |

> 华为智慧屏 / 荣耀智慧屏运行 HarmonyOS，不是 Android，`pm disable-user` 及本仓库全部脚本均无效。
> 这类设备请走厂商自带的「隐私/广告管理」或第三方 HarmonyOS 工具，与本仓库路线无关。

## 2. 已覆盖的国产设备速查

仓库 `examples/` 已有大量国产机型实测清单（部分）：

- 小米 / Redmi：约 40 份（含小米电视、小米盒子、Redmi 电视/投影）
- TCL：约 80 份（含雷鸟 FFalcon、TCL 各区域型号）
- 海信 / VIDDA：约 16 份
- 创维 / 酷开：约 14 份
- 海尔、Xgimi、OPPO、OnePlus、PPTV、JMGO 等也有覆盖

**仍偏薄弱 / 空白的国产品牌**（本指南已补起始清单，见第 4 节）：长虹、康佳、当贝、微鲸。
华为/荣耀因 HarmonyOS 不在此列。

## 3. 在真机枚举包名（新增任何设备清单的第一步）

连上电视的无线调试后，先把真机所有包名拉下来，才能知道该禁哪些：

```bash
TV_HOST=<电视IP> tools/tv.py "pm list packages" > tv_packages.txt
# 按品牌关键字筛出候选：
grep -i changhong tv_packages.txt
grep -i konka    tv_packages.txt
grep -i dangbei  tv_packages.txt
grep -i whaley   tv_packages.txt
```

对照下面的「安全 / 危险」判断后再写进清单：

- **可停用（低风险）**：广告服务、推送、推荐内容、 analytics/统计、厂商应用商店（若不用）、OTA 之外的预装推广。
- **危险，别碰**：任何 `com.android.*` 核心包、`com.google.android.permissioncontroller`、
  `com.google.android.packageinstaller`、`com.android.systemui`、`com.android.settings`、
  以及**当前正在承担 HOME 的桌面**（见第 5 节 Home 防护）。

## 4. 本指南新增的国产起始清单

位于 `examples/`，通用部分取自 400+ 份实测清单的「安全基线」，品牌专属部分**默认注释**，
需按第 3 节在真机确认包名后再取消注释（项目规范：未实测的条目先注释）。

| 文件 | 说明 |
| --- | --- |
| `examples/zh-cn-generic-android-tv/debloat.txt` | 通用安全基线，适用于所有国产 Android TV |
| `examples/changhong-android-tv/debloat.txt` | 长虹起始清单 |
| `examples/konka-android-tv/debloat.txt` | 康佳起始清单（注意 `com.konka.livelauncher` 是 HOME） |
| `examples/dangbei-android-tv/debloat.txt` | 当贝投影/盒子起始清单 |
| `examples/whaley-android-tv/debloat.txt` | 微鲸起始清单 |

用法：

```bash
TV_HOST=<IP> tools/disable.sh examples/zh-cn-generic-android-tv/debloat.txt   # 停用
TV_HOST=<IP> tools/venv/bin/python -I tools/guard.py   examples/zh-cn-generic-android-tv/debloat.txt   # 守护(防 OTA 回启)
TV_HOST=<IP> tools/restore.sh examples/zh-cn-generic-android-tv/debloat.txt   # 还原
```

## 5. 守护: 防止 OTA / 重置把包重新启用

新工具 `tools/guard.py`（本指南随附）周期性连接电视，把清单里「又被系统启用」的包重新停用，
专治 OTA 升级后广告回来。它复用 `tv.py` 的连接与密钥，并继承 `disable.sh` 的全部安全闸
（核心包保护 + Home 防护），不会动核心包、不会让电视失去桌面。

```bash
# 单次巡检
TV_HOST=<IP> tools/venv/bin/python -I tools/guard.py examples/zh-cn-generic-android-tv/debloat.txt
# 每小时守护一次（建议用 nohup / 任务计划长期运行）
TV_HOST=<IP> tools/venv/bin/python -I tools/guard.py --loop --interval 3600 examples/zh-cn-generic-android-tv/debloat.txt
# 只报告不改动
TV_HOST=<IP> tools/venv/bin/python -I tools/guard.py --dry-run examples/zh-cn-generic-android-tv/debloat.txt
```

## 6. 资源改国内（插件层面）

仅 `photo-screensaver`（天气与壁纸）会访问网络；启动器本体资源均本地打包，离线可用。

### 6.1 天气: Open-Meteo → 中国天气网（已实现）

`photo-screensaver` 的 `Weather.java` 已改为**中国天气网优先**：

| 顺序 | 数据源 | 是否需要 Key | 说明 |
|---|---|---|---|
| 1 | `api.weather.com.cn/sk`（官方开放数据） | 需要 | `weather.txt` 第二行写了 Key 时才走这里 |
| 2 | `d1.weather.com.cn/sk_2d/<cityid>.html` | **不需要** | 只带 `Referer: http://www.weather.com.cn/` 头即可，已实测通过 |
| 3 | `api.open-meteo.com`（原实现） | 不需要 | 兜底：没配城市码且电视有定位时使用 |

返回的 `var dataSK={...}` / `{"sk":{...}}` / JSONP 数组三种形态都能解析，
最终显示形如 `杭州 15°  ⛅`（城市名 + 摄氏度 + 天气符号）。

**查城市编码（离线，不联网）**：`tools/city_codes.json` 内置了中国天气网官方发布的城市码表
（省 → 市 → 区县三级，共 3221 条，来源 `j.i8tq.com/weather2020/search/city.js`）：

```bash
python3 tools/weather_city.py 杭州      # -> 101210101  浙江  杭州  杭州
python3 tools/weather_city.py 西安      # -> 101110101  陕西  西安  西安
python3 tools/weather_city.py 海淀      # -> 101010200  北京  北京  海淀
```

> 中国天气网的搜索接口 `toy1.weather.com.cn/search` 会限流且部分城市名命中不到，
> 所以本仓库直接内置了码表，不依赖它。

**配置到电视**：把城市码写进插件的 files 目录下的 `weather.txt`（一行，可选第二行放 Key）：

```bash
TV_HOST=<电视IP> tools/venv/bin/python -I tools/tv.py \
  "mkdir -p /sdcard/Android/data/com.gathin.photodream/files"
TV_HOST=<电视IP> tools/venv/bin/python -I tools/tv.py \
  "echo 101210101 > /sdcard/Android/data/com.gathin.photodream/files/weather.txt"
```

配置后**不再需要电视的定位权限**（`Scene.java` 已改为：取不到定位时，只要配了城市码就照常显示）。

### 6.2 壁纸: 360 壁纸（已实现）

`addons/photo-screensaver/download_360_wallpapers.py` 用 360 壁纸的公开接口抓图到本地，
再由项目自带的 `sync.sh` 推送到电视——沿用仓库原有的「下载到本地 → 推送」架构，不改 APK 也能换壁纸源。

```bash
# 列出分类（限时壁纸 / 风景大片 / 小清新 / 影视剧照 …）
python3 addons/photo-screensaver/download_360_wallpapers.py --list-categories

# 下载（默认「今日推荐」分类 20 张到 addons/photo-screensaver/pictures/）
python3 addons/photo-screensaver/download_360_wallpapers.py --count 20

# 指定分类下载到自己的目录
python3 addons/photo-screensaver/download_360_wallpapers.py --category 9 --count 30 --out ~/Pictures/tv

# 推送到电视并设为屏保
TV_HOST=<电视IP> addons/photo-screensaver/sync.sh ~/Pictures/tv
```

脚本已按 `sync.sh` 的约束处理：文件名只含字母数字与 `-_.`、扩展名为 `.jpg`、单张不超过 10 MB、数量不超过 100 张。

### 6.3 新闻: BBC → 国内 RSS

`news-screensaver` 默认 Feed 是 `https://feeds.bbci.co.uk/news/world/rss.xml`（国内常不可达）。
Feed 通过 `feeds.txt` 配置（每行一个 https 地址，可选 `名称 | 地址`，`#` 注释），通过 `setup.sh` 写入。
国内可用 RSS 候选（请以真机/网络实测可达为准）：

```
新华网 | https://www.news.cn/rss/20250203.xml
央视新闻 | https://news.cctv.com/rss/...
财新 | https://www.caixin.com/rss/...
少数派 | https://sspai.com/feed
```

> 注意：插件要求 https 且 TV 需能直连该地址。若地址失效，请在电视端或 `feeds.txt` 中替换为可达源。

改默认 Feed（可选，需重新编译插件）：将 `Config.java` 的
`DEFAULT_FEEDS = Collections.singletonList("https://feeds.bbci.co.uk/news/world/rss.xml")`
替换为上述国内地址之一。

## 7. 贡献回上游的建议

1. 在真机按第 3 节枚举并验证包名，写 `examples/<品牌>-<型号>/debloat.txt`。
2. 设备识别信息（零售型号、ADB product/model、Android 版本）补到 `docs/devices/<设备>.md`。
3. 遵守现有安全规范：不禁用核心包与 HOME；未实测条目先注释。
4. 提交 PR 时附上「已实测运行时长 / 截图」，便于维护者合并。
