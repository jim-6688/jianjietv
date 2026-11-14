package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Video wallpaper sources, modelled on Aerial / HaloTV: a manifest of remote video URLs is streamed
 * (looped, muted) behind the launcher instead of a still picture. The manifest either ships with the
 * app (assets/aerials.json, Apple's Aerial clips) or is a remote JSON the TV fetches at runtime, so the
 * same "online video directory" idea copies over cleanly.
 *
 * <p>Only the manifest is bundled; the videos themselves are always fetched over the network, exactly
 * like Aerial. A remote manifest is cached after a successful fetch so a later launch has something to
 * show before the network returns.</p>
 */
final class WallpaperVideo {
    /** Built-in manifest name: the bundled Apple Aerial list. */
    static final String MANIFEST_AERIAL = "aerial";
    private static final String TAG = "TvHome.WallpaperVideo";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Remote manifest URL -> last fetched URLs, so a remote manifest shows something on the next launch too. */
    private static final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    /** Called on the main thread with the fetched URLs (possibly empty). */
    interface ManifestCallback {
        void onUrls(List<String> urls);
    }

    /** URLs available right now: the built-in Aerial list, an assets file, or the last remote fetch (empty until fetched). */
    static List<String> urls(Context ctx, String manifest) {
        if (manifest == null || manifest.isEmpty() || MANIFEST_AERIAL.equals(manifest)) {
            return fromAssets(ctx, "aerials.json");
        }
        if (manifest.startsWith("http://") || manifest.startsWith("https://")) {
            List<String> cached = cache.get(manifest);
            return cached != null ? cached : Collections.emptyList();
        }
        // Anything else is treated as a manifest file in assets.
        return fromAssets(ctx, manifest);
    }

    /** Fetch a remote manifest (a JSON {"videos":[{"url":...}]}) off the UI thread, then call back on the UI thread. */
    static void fetchRemote(String manifest, ManifestCallback cb) {
        worker.execute(() -> {
            List<String> result = fromUrl(manifest);
            if (!result.isEmpty()) cache.put(manifest, result);
            final List<String> out = result;
            new Handler(Looper.getMainLooper()).post(() -> cb.onUrls(out));
        });
    }

    static boolean isRemote(String manifest) {
        return manifest != null && (manifest.startsWith("http://") || manifest.startsWith("https://"));
    }

    private static List<String> fromAssets(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            return parse(in);
        } catch (Exception e) {
            Log.w(TAG, "cannot read video manifest " + name + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<String> fromUrl(String manifest) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(manifest);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream()) {
                return parse(in);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot fetch video manifest " + manifest + ": " + e.getMessage());
            return Collections.emptyList();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<String> parse(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        JSONObject root = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        JSONArray videos = root.optJSONArray("videos");
        java.util.ArrayList<String> urls = new java.util.ArrayList<>();
        if (videos != null) {
            for (int i = 0; i < videos.length(); i++) {
                Object item = videos.opt(i);
                if (item instanceof String) {
                    String url = ((String) item).trim();
                    if (!url.isEmpty()) urls.add(playable(url));
                    continue;
                }
                JSONObject v = videos.optJSONObject(i);
                if (v == null) continue;
                String url = v.optString("url", "").trim();
                if (url.isEmpty()) url = v.optString("src", "").trim();
                if (!url.isEmpty()) urls.add(playable(url));
            }
        }
        return urls;
    }

    /**
     * Apple's Aerial clips are public files on sylvan.apple.com, but that host's edges answer on port 80 and drop the
     * TLS handshake on 443 from mainland China (checked: http 206, https no connection, while other https hosts are
     * fine). The same file is served either way, so the bundled manifest is played over http. Any other host, and any
     * https URL a reader puts in their own manifest, keeps the scheme it was given.
     */
    static String playable(String url) {
        String apple = "https://sylvan.apple.com/";
        return url.startsWith(apple) ? "http://" + url.substring("https://".length()) : url;
    }

    private WallpaperVideo() { }
}
