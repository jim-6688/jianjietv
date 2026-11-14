package com.gathin.tvhome.mobile;

/**
 * 功能 1：按电视品牌引导开启 ADB。资料从仓库 docs/devices/、PROMPT.md、docs/faq.md 归纳，
 * 只保留「在设置里能完成」的路径；工厂菜单一律不引导（AGENTS.md 明确禁止启动工厂包）。
 */
public final class Brands {

    public static final class Brand {
        public final String name;
        public final String[] steps;
        public final String note;
        /** false 表示这不是 Android TV，手机版不适用。 */
        public final boolean androidTv;

        Brand(String name, boolean androidTv, String note, String... steps) {
            this.name = name;
            this.androidTv = androidTv;
            this.note = note;
            this.steps = steps;
        }
    }

    private static final String DEV = "设置 → 关于本机 / 系统信息 → 连点「版本号」或「内部版本号」7 次，直到提示已进入开发者模式";
    private static final String IP = "设置 → 网络 → 记下电视的 IP；回到手机第 2 步扫描，或手动填这个 IP";

    private static final Brand[] ALL = {
            new Brand("小米 / Redmi / POCO TV", true,
                    "部分机型还要在「开发者选项」里打开「无线调试」；Android 11+ 的配对码方式本版暂不支持。",
                    DEV, "设置 → 更多设置 / 开发者选项 → 打开「USB 调试」和「网络 ADB 调试」", IP),
            new Brand("海信 / Vidda", true,
                    "不要进工厂菜单：仓库规则禁止启动 com.hisense.* 工厂包，某些板子会留下无法撤销的工厂标志。",
                    DEV, "设置 → 开发者选项 → USB 调试 / 网络调试 打开", IP),
            new Brand("TCL / 雷鸟 / FFALCON", true,
                    "Google TV 机型若只有「无线调试」，请用设置里的「网络 ADB 调试」开关，不要只开 USB 调试。",
                    DEV, "设置 → 系统 → 开发者选项 → USB 调试 + 网络 ADB 调试", IP),
            new Brand("创维 / 酷开（本机实测 9TA01_S81P，即 S81）", true,
                    "关键两点：①「USB 调试」≠「网络调试」——手机连的是网络调试，两个都要开；"
                            + "②创维很多机型开了开关也不立即监听 5555，必须「彻底断电重启」一次才生效。",
                    "设置 → 关于电视 → 连点「软件版本号」7 次 → 提示「您已处于开发者模式」",
                    "返回 → 系统设置 → 开发者选项 → 同时打开「USB 调试」和「网络 ADB 调试 / 网络调试」",
                    "⚠ 打开后必须「彻底断电再开机」一次（长按电源键到彻底关机，或拔插头几秒再插），"
                            + "否则 5555 不监听，手机第 2 步仍搜不到这台 S81",
                    "重启后回手机第 2 步：点「深度扫描」或手动填 192.168.6.11；连上后状态会显示 S81 型号",
                    "若开发者选项里找不到「网络调试」开关：设置 → 本机信息 → 遥控器输入 上上 下下 左右 左右"
                            + " 进工厂菜单找 ADB 开关（按仓库规则属最后手段且有风险；若提示密码试 123456）",
                    IP),
            new Brand("索尼 BRAVIA（Android TV）", true,
                    "部分遥控按键走 KEY_UNKNOWN，见 docs/devices/sony-bravia-*；第 6 步遥控码会用到。",
                    DEV, "设置 → 偏好设置 → 开发者选项 → USB 调试 / 网络调试", IP),
            new Brand("长虹 / 康佳 / 海尔", true,
                    "国产板常见「网络 ADB 调试」独立开关，两个都要打开。",
                    DEV, "设置 → 开发者选项 → USB 调试 + 网络 ADB 调试", IP),
            new Brand("当贝 / 天猫魔盒 / 电视盒子", true,
                    "盒子通常默认打开 5555，先直接用第 2 步扫描，找不到再走引导。",
                    DEV, "设置 → 开发者选项 → USB 调试", IP),
            new Brand("Amazon Fire TV", true,
                    "Fire OS 5 上 pm disable-user 可能被拒；软件管理会先做一次探测，拒绝就停止并说明。",
                    "设置 → 我的 Fire TV → 关于 → 连点设备名称 7 次",
                    "开发者选项 → 打开「ADB 调试」", IP),
            new Brand("Philips / 其它 Android TV", true,
                    "通用路径；若厂商藏了开发者选项，试试设置里搜索「开发者」。",
                    DEV, "设置 → 开发者选项 → USB 调试 + 网络调试", IP),
            new Brand("三星 Tizen / LG webOS / Roku / VIDAA / SmartCast", false,
                    "不是 Android TV，没有 ADB，本工具不适用。请改用遥控器关闭跟踪：见 cleanyour.tv/not-android。",
                    "停止：这些系统没有 ADB 端口，扫描与安装都不会生效。")
    };

    private Brands() {
    }

    public static Brand[] all() {
        return ALL.clone();
    }

    /** 通用安全提示，每个品牌页底部都显示。 */
    public static String warning() {
        return "注意：网络 ADB 的 5555 是明文端口，同网段设备都能连。仓库默认要求用完即关；"
                + "你已选择「保持开启 + App 内一键关闭」，请在不使用时点第 3 步里的关闭按钮并校验端口。"
                + "任何情况下都不要为了开 ADB 进工厂/测试菜单。";
    }
}
