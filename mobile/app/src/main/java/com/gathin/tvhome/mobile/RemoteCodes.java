package com.gathin.tvhome.mobile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 功能 6 的「按品牌自动启用对应遥控码」：根据已保存电视的型号识别出品牌，
 * 再用这张表挑专属应用键码；表里没有的品牌/应用就回退到 am start 启动对应 App。
 *
 * <p>键码来源：Android TV 通用键码（Netflix=286 多数固件支持），以及本仓库
 * launcher/app/src/main/res/values/config.xml 注释里记录的真实机型码
 * （小米 YouTube=190、Sony BRAVIA 多键同码 196、KTC Netflix=191/YouTube=190/Prime=195 等）。
 * 这张表是可扩展的：把 docs/devices/ 里更多机型的 scancode 补进来即可。
 */
final class RemoteCodes {

    /** 识别品牌：从电视型号字符串里挑关键字。 */
    static String detectBrand(String model) {
        String m = model == null ? "" : model.toLowerCase();
        if (m.contains("s81") || m.contains("coocaa") || m.contains("skyworth") || m.contains("9ta01")) return "创维/酷开";
        if (m.contains("sony") || m.contains("bravia")) return "Sony";
        if (m.contains("philips")) return "Philips";
        if (m.contains("xiaomi") || m.contains("mitv") || m.contains("mi tv")) return "小米";
        if (m.contains("tcl")) return "TCL";
        if (m.contains("samsung")) return "Samsung";
        if (m.contains("amazon") || m.contains("fire tv")) return "Amazon";
        return "通用";
    }

    static final List<String> BRANDS = Arrays.asList(
            "通用", "创维/酷开", "Sony", "Philips", "小米", "TCL", "Samsung", "Amazon");

    /** 应用名 → 包名，用于没有专属键码时 am start 兜底启动。 */
    static final Map<String, String> APP_PACKAGE = new HashMap<>();

    static {
        APP_PACKAGE.put("Netflix", "com.netflix.ninja");
        APP_PACKAGE.put("YouTube", "com.google.android.youtube.tv");
        APP_PACKAGE.put("Prime Video", "com.amazon.amazonvideo.livingroom");
        APP_PACKAGE.put("迪士尼+", "com.disney.disneyplus");
    }

    static final List<String> APPS = Arrays.asList("Netflix", "YouTube", "Prime Video", "迪士尼+");

    /**
     * 品牌 → 应用 → 专属键码。没有的键码用 -1 表示，调用方回退到 am start。
     * 通用/创维/Sony/Philips/TCL 共用一份：Netflix=286（Android TV 通用键码）。
     */
    static final Map<String, Map<String, Integer>> BRAND_APP_KEY = new HashMap<>();

    static {
        Map<String, Integer> base = new HashMap<>();
        base.put("Netflix", 286);                 // KEYCODE_TV_NETFLIX，多数 Android TV 支持
        BRAND_APP_KEY.put("通用", base);
        BRAND_APP_KEY.put("创维/酷开", base);
        BRAND_APP_KEY.put("Sony", base);
        BRAND_APP_KEY.put("Philips", base);
        BRAND_APP_KEY.put("TCL", base);
        BRAND_APP_KEY.put("Samsung", base);
        BRAND_APP_KEY.put("Amazon", base);

        // 小米：YouTube 走 190（KEYCODE_BUTTON_3），见 config.xml 注释
        Map<String, Integer> xiaomi = new HashMap<>(base);
        xiaomi.put("YouTube", 190);
        BRAND_APP_KEY.put("小米", xiaomi);
    }

    /** 返回该品牌下某应用的专属键码，没有则 -1。 */
    static int appKeycode(String brand, String app) {
        Map<String, Integer> table = BRAND_APP_KEY.get(brand);
        if (table == null) table = BRAND_APP_KEY.get("通用");
        Integer code = table == null ? null : table.get(app);
        return code == null ? -1 : code;
    }
}
