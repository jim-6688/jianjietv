package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从豆瓣公开接口取真实海报（与影视 App 的豆瓣站点同源）。按分类合集缓存，避免重复请求。
 * 电影用 cover.url，剧集用 pic.large/normal，两者都兼容。
 */
final class DoubanWall {

    static final class Item {
        final String title;
        final String poster;
        final String meta;

        Item(String title, String poster, String meta) {
            this.title = title;
            this.poster = poster;
            this.meta = meta;
        }
    }

    interface Callback {
        void onItems(List<Item> items);
    }

    /** 分类索引（与 FilmHub.CATEGORIES 对齐）-> 豆瓣合集名。没有对应合集的分类用经典片单兜底。 */
    private static final String[] COLLECTIONS = {
            "movie_showing",    // 电影：正在上映
            "tv_hot",           // 电视剧：热门
            "tv_variety_show",  // 综艺
            "tv_animation",     // 动漫
            "tv_documentary",   // 纪录片
            "movie_top250",     // 解说：经典片单
            "tv_hot",           // 短剧
            "tv_animation",     // 少儿
            "movie_top250",     // 体育
            "movie_top250",     // 音乐
    };

    private static final Map<String, List<Item>> CACHE = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void fetch(Context c, int category, Callback cb) {
        int idx = Math.max(0, Math.min(category, COLLECTIONS.length - 1));
        final String collection = COLLECTIONS[idx];
        synchronized (CACHE) {
            List<Item> cached = CACHE.get(collection);
            if (cached != null && !cached.isEmpty()) {
                cb.onItems(cached);
                return;
            }
        }
        new Thread(() -> {
            List<Item> items = load(collection);
            if (!items.isEmpty()) {
                synchronized (CACHE) {
                    CACHE.put(collection, items);
                }
            }
            MAIN.post(() -> cb.onItems(items));
        }).start();
    }

    private static List<Item> load(String collection) {
        List<Item> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            String url = "https://m.douban.com/rexxar/api/v2/subject_collection/" + collection
                    + "/items?start=0&count=20&for_mobile=1";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            conn.setRequestProperty("Referer", "https://m.douban.com/");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36");
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            }
            JSONObject d = new JSONObject(sb.toString());
            JSONArray arr = d.optJSONArray("subject_collection_items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String title = o.optString("title", "");
                if (title.isEmpty()) continue;
                String poster = null;
                JSONObject cover = o.optJSONObject("cover");
                if (cover != null) poster = cover.optString("url", null);
                if (poster == null || poster.isEmpty()) {
                    JSONObject pic = o.optJSONObject("pic");
                    if (pic != null) {
                        poster = pic.optString("large", null);
                        if (poster == null || poster.isEmpty()) poster = pic.optString("normal", null);
                    }
                }
                if (poster == null || poster.isEmpty()) continue;
                String meta = o.optString("card_subtitle", "");
                if (meta.isEmpty()) {
                    JSONObject rating = o.optJSONObject("rating");
                    if (rating != null) {
                        double v = rating.optDouble("value", 0);
                        if (v > 0) meta = "评分 " + v;
                    }
                }
                if (meta.isEmpty()) {
                    String info = o.optString("info", "");
                    if (info.length() > 24) info = info.substring(0, 24);
                    meta = info;
                }
                out.add(new Item(title, poster, meta));
            }
        } catch (Exception ignored) {
            // 网络/解析失败都返回空列表，影视墙会保留占位卡
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }
}
