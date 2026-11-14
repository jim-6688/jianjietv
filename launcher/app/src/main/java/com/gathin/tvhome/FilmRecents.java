package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 影视「最近观看」：桌面侧维护的最近打开记录（按分类名记录，影视 App 自身历史需其暴露接口才能读取）。
 * 存在内部存储 film_recents.json，最多保留 12 条，同名打开会提到最前。
 */
final class FilmRecents {

    static final class Entry {
        final String name;
        final long ts;

        Entry(String name, long ts) {
            this.name = name;
            this.ts = ts;
        }
    }

    private static final int MAX = 12;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "film_recents.json");
    }

    static List<Entry> load(Context c) {
        List<Entry> list = new ArrayList<>();
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file(c).toPath()));
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Entry(o.getString("name"), o.optLong("ts", 0)));
            }
        } catch (Exception ignored) {
            // 没有记录或解析失败都当空列表
        }
        return list;
    }

    /** 记录一次打开：同名移到最前，超量裁剪。 */
    static void record(Context c, String name) {
        List<Entry> list = load(c);
        list.removeIf(e -> e.name.equals(name));
        list.add(0, new Entry(name, System.currentTimeMillis()));
        while (list.size() > MAX) list.remove(list.size() - 1);
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("ts", e.ts);
                arr.put(o);
            }
            java.nio.file.Files.write(file(c).toPath(), arr.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    static String relTime(long ts) {
        if (ts <= 0) return "";
        long diff = System.currentTimeMillis() - ts;
        long min = diff / 60000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hr = min / 60;
        if (hr < 24) return hr + " 小时前";
        long day = hr / 24;
        if (day < 30) return day + " 天前";
        return day / 30 + " 个月前";
    }
}
