package com.gathin.tvhome;

import java.util.Locale;

/**
 * 把公网 IP 回显接口的响应认成宽带运营商名。刻意不依赖任何 Android API，
 * 这样解析规则能在 JVM 单测里锁住，不必把整个 NetInfo（Handler / SharedPreferences）拉起来。
 */
final class IspNames {

    /** 每组第一个是显示用的标准名，后面是响应里可能出现的写法；按特异度排序。 */
    private static final String[][] NAMES = {
            {"中国电信", "电信", "CHINANET", "CTNET"},
            {"中国联通", "联通", "UNICOM", "CHINA169"},
            {"中国移动", "移动", "CMCC", "CHINAMOBILE"},
            {"中国铁通", "铁通", "CRTC"},
            {"中国广电", "广电", "CBN"},
            {"教育网", "CERNET"},
            {"长城宽带", "长城", "鹏博士"}
    };

    private IspNames() {
    }

    static String from(String body) {
        if (body == null || body.isEmpty()) return null;
        String up = body.toUpperCase(Locale.ROOT);
        for (String[] row : NAMES) {
            for (String keyword : row) {
                if (up.contains(keyword.toUpperCase(Locale.ROOT))) return row[0];
            }
        }
        return null;
    }
}
