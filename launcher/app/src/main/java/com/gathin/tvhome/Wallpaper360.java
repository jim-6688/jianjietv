package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Brings 360 wallpapers straight to the launcher's background. The public 360 API needs no key, so the
 * launcher fetches a batch of pictures onto local storage at runtime and lets {@link WallpaperPlayer}
 * rotate them, instead of relying on a PC-side script to push images. See addons/photo-screensaver/
 * download_360_wallpapers.py for the same interface used offline.
 *
 * <p>The collection lives in {@code <external-files>/360wallpapers/} and is read with the same filter
 * as the theme's {@code wallpapers/} folder, so the existing rotation, dimming and caption code applies.</p>
 */
final class Wallpaper360 {
    /** On-TV switch stored in the launcher settings SharedPreferences. */
    static final String PREF_KEY = "wallpaper_360";
    private static final String TAG = "TvHome.Wallpaper360";
    /** 360 wallpaper public API (no key). http in the docs; we rewrite the image host to https. */
    private static final String API = "http://wallpaper.apc.360.cn/index.php";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    /** Keep at least this many pictures on disk; the rotation looks better with a fuller set. */
    private static final int MIN = 24;
    /** Refresh the collection when the newest picture is older than this (also caps bandwidth). */
    private static final long STALE_MS = 6L * 60 * 60 * 1000;
    /** Upper bound on pictures kept on disk; the oldest are dropped once a manual refresh goes past it. */
    private static final int MAX_KEPT = 60;
    /** Same 10 MB cap the PC script and sync.sh use, so a single picture never blows up decode memory. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Random random = new Random();

    private Wallpaper360() { }

    /** The local 360 collection folder, or null when external storage is unavailable. */
    static File dir(Context ctx) {
        File base = Config.dir(ctx);
        return base == null ? null : new File(base, "360wallpapers");
    }

    /** Whether the on-TV 360 wallpaper switch is on. */
    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY, false);
    }

    /** The local 360 images ready to show, using the same filter as the theme's wallpapers folder. */
    static List<File> files(Context ctx) {
        File d = dir(ctx);
        return d == null ? new ArrayList<>() : WallpaperRotation.images(d);
    }

    /**
     * Downloads more 360 wallpapers when the local set is missing, smaller than {@link #MIN}, or stale.
     * Safe to call from the UI thread: the network work runs on a background thread.
     */
    static void fetchIfNeeded(Context ctx) {
        if (!enabled(ctx)) return;
        worker.execute(() -> fetchSync(ctx));
    }

    /** 手动「刷新壁纸」：忽略 6 小时/24 张的节流，立刻再抓一批新图，并清掉过老的，保持总量可控。 */
    static void refreshNow(Context ctx) {
        if (!enabled(ctx)) return;
        worker.execute(() -> fetchFresh(ctx));
    }

    /** Warm the local 360 cache so it can act as a fallback wallpaper when Apple stills are unavailable. */
    static void warmCache(Context ctx) {
        worker.execute(() -> fetchSync(ctx));
    }

    private static void fetchFresh(Context ctx) {
        File d = dir(ctx);
        if (d == null) return;
        try {
            int cid = resolveCategory();
            int total = categoryTotal(cid);
            if (total <= 0) {
                Log.w(TAG, "360 manual refresh: category " + cid + " returned no pictures");
                return;
            }
            int count = Math.min(40, Math.max(MIN, total));
            int start = total > count ? random.nextInt(total - count + 1) : 0;
            List<String> urls = pageUrls(cid, start, count);
            if (urls.isEmpty()) return;
            d.mkdirs();
            long stamp = System.currentTimeMillis();
            int added = 0;
            for (String url : urls) {
                if (added >= 12) break;
                // 文件名带时间戳，和已有图不重名，保证这一批真的是新的。
                File dest = new File(d, String.format("360_%d_%d_%d.jpg", start, stamp % 100000, added));
                if (download(url, dest)) added++;
            }
            trim(d);
            Log.i(TAG, "360 manual refresh added " + added + ", on disk "
                    + WallpaperRotation.images(d).size());
        } catch (Exception e) {
            Log.w(TAG, "360 manual refresh failed: " + e.getMessage());
        }
    }

    /** 超过 {@link #MAX_KEPT} 张就删最旧的，避免手动刷新几次就把存储堆满。 */
    private static void trim(File dir) {
        List<File> all = WallpaperRotation.images(dir);
        if (all.size() <= MAX_KEPT) return;
        List<File> byAge = new ArrayList<>(all);
        byAge.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < byAge.size() - MAX_KEPT; i++) byAge.get(i).delete();
    }

    /** Blocking download: top the collection up to {@link #MIN} and refresh anything older than {@link #STALE_MS}. */
    static void fetchSync(Context ctx) {
        File d = dir(ctx);
        if (d == null) return;
        List<File> have = WallpaperRotation.images(d);
        long now = System.currentTimeMillis();
        long staleAfter = staleAfter(ctx);
        boolean stale = have.isEmpty();
        if (!stale) {
            long newest = 0;
            for (File f : have) newest = Math.max(newest, f.lastModified());
            stale = (now - newest) > staleAfter;
        }
        if (!stale && have.size() >= MIN) return;
        // 到了间隔就真的换一批：图库已经满了的话先让出最旧的几张，否则文件名撞上已有图，
        // 「每小时抓一次」会一张也进不来。
        if (stale && have.size() >= MIN + 10) {
            List<File> byAge = new ArrayList<>(have);
            byAge.sort((x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            for (int i = 0; i < Math.min(10, byAge.size() - MIN); i++) byAge.get(i).delete();
            have = WallpaperRotation.images(d);
        }
        try {
            int cid = resolveCategory();
            int total = categoryTotal(cid);
            if (total <= 0) {
                Log.w(TAG, "360 category " + cid + " returned no pictures");
                return;
            }
            int count = Math.min(50, Math.max(MIN, total));
            int start = total > count ? random.nextInt(total - count + 1) : 0;
            List<String> urls = pageUrls(cid, start, count);
            if (urls.isEmpty()) return;
            d.mkdirs();
            int kept = have.size();
            for (String url : urls) {
                if (kept >= MIN + 10) break;   // a little headroom so the rotation is not all new at once
                File dest = new File(d, String.format("360_%d_%d.jpg", start, kept));
                if (dest.exists() && dest.length() > 0) { kept++; continue; }
                if (download(url, dest)) kept++;
            }
            Log.i(TAG, "360 wallpapers on disk: " + WallpaperRotation.images(d).size());
        } catch (Exception e) {
            Log.w(TAG, "360 wallpaper fetch failed: " + e.getMessage());
        }
    }

    /**
     * How old the newest picture may get before another batch is fetched: the on-TV rotation interval when one is
     * chosen (1 hour → hourly, 1 day → daily), so「自动获取」follows the same rhythm as the rotation itself.
     * Following the theme keeps the old 6-hour throttle; a short theme interval is floored at an hour so the API
     * is never hammered.
     */
    private static long staleAfter(Context ctx) {
        int minutes;
        try {
            minutes = Config.load(ctx).wallpaperRotationMinutes;
        } catch (RuntimeException e) {
            minutes = 0;
        }
        if (minutes < 60) return STALE_MS;
        return Math.min(minutes * 60_000L, 24L * 60 * 60 * 1000);
    }

    /** "今日推荐" (today's picks); falls back to 27 when the name cannot be resolved. */
    private static int resolveCategory() {
        try {
            JSONObject root = getJson(API + "?c=WallPaper&a=getAllCategories");
            JSONObject data = root == null ? null : root.optJSONObject("data");
            if (data != null) {
                for (Iterator<String> it = data.keys(); it.hasNext(); ) {
                    String cid = it.next();
                    JSONObject info = data.optJSONObject(cid);
                    if (info != null && "今日推荐".equals(info.optString("name", ""))) {
                        return Integer.parseInt(cid);
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to the default id
        }
        return 27;
    }

    private static int categoryTotal(int cid) {
        try {
            JSONObject root = getJson(API + "?c=WallPaper&a=getAppsByCategory&cid=" + cid
                    + "&start=0&count=1");
            return root == null ? 0 : root.optInt("total", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<String> pageUrls(int cid, int start, int count) {
        List<String> urls = new ArrayList<>();
        try {
            JSONObject root = getJson(API + "?c=WallPaper&a=getAppsByCategory&cid=" + cid
                    + "&start=" + start + "&count=" + count);
            JSONArray data = root == null ? null : root.optJSONArray("data");
            if (data == null) return urls;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                // Prefer the mid resolution: full-res 4K is wasteful to decode on a 1080p TV, thumb is too soft.
                String url = item.optString("url_mid", "");
                if (url.isEmpty()) url = item.optString("url", "");
                if (url.isEmpty()) url = item.optString("url_thumb", "");
                if (!url.isEmpty()) urls.add(url.trim());
            }
        } catch (Exception ignored) {
            // return whatever we gathered
        }
        return urls;
    }

    private static JSONObject getJson(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", UA);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream()) {
                byte[] bytes = readAll(in);
                if (bytes.length == 0) return null;
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "360 request failed " + urlString + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean download(String url, File dest) {
        HttpURLConnection conn = null;
        try {
            String https = url.replaceFirst("^http://", "https://");
            URL u = new URL(https);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "http://www.360.cn/");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            byte[] bytes = readAll(conn.getInputStream());
            if (bytes.length == 0 || bytes.length > MAX_BYTES) return false;
            File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            Log.w(TAG, "360 download failed " + url + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
