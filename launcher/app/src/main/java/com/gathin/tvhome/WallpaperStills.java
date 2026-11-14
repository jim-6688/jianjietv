package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple still-wallpaper source (option 1 from the wallpaper discussion): a manifest of remote Apple desktop
 * picture URLs is downloaded onto local storage at runtime and rotated by {@link WallpaperPlayer}, exactly like
 * {@link Wallpaper360} does for 360's pictures. The manifest either ships with the app (assets/apple_stills.json,
 * a curated set of Apple's macOS landscape wallpapers) or is a remote JSON the TV fetches at runtime, so the
 * same "online picture directory" idea copies over cleanly. 360 stays the fallback (Wallpaper360) when this
 * collection is empty or unreachable.
 *
 * <p>The collection lives in {@code <external-files>/applestills/} and is read with the same filter as the
 * theme's {@code wallpapers/} folder, so the existing rotation, dimming and caption code applies.</p>
 */
final class WallpaperStills {
    /** Built-in manifest name: the bundled Apple macOS still-wallpaper list. */
    static final String MANIFEST_APPLE = "apple";
    private static final String TAG = "TvHome.WallpaperStills";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Keep at most this many pictures on disk. */
    private static final int MAX_KEEP = 40;
    /** Same 10 MB cap Wallpaper360 and the PC script use, so a single picture never blows up decode memory. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private WallpaperStills() { }

    /** The local Apple still-wallpaper folder, or null when external storage is unavailable. */
    static File dir(Context ctx) {
        File base = Config.dir(ctx);
        return base == null ? null : new File(base, "applestills");
    }

    /** The local Apple still images ready to show, using the same filter as the theme's wallpapers folder. */
    static List<File> files(Context ctx) {
        File d = dir(ctx);
        return d == null ? new ArrayList<>() : WallpaperRotation.images(d);
    }

    /** URLs named by the manifest: the built-in Apple list, an assets file, or empty until a remote one is fetched. */
    static List<String> urls(Context ctx, String manifest) {
        if (manifest == null || manifest.isEmpty() || MANIFEST_APPLE.equals(manifest)) {
            return fromAssets(ctx, "apple_stills.json");
        }
        if (isRemote(manifest)) return Collections.emptyList();
        return fromAssets(ctx, manifest);
    }

    static boolean isRemote(String manifest) {
        return manifest != null && (manifest.startsWith("http://") || manifest.startsWith("https://"));
    }

    /** Downloads the manifest's pictures onto local storage when the local set is missing or short. Safe from the UI thread. */
    static void fetchIfNeeded(Context ctx, String manifest) {
        fetchIfNeeded(ctx, manifest, null);
    }

    /** As {@link #fetchIfNeeded(Context, String)} plus a UI-thread callback once the (possibly empty) fetch ends. */
    static void fetchIfNeeded(Context ctx, String manifest, Runnable onDone) {
        worker.execute(() -> {
            fetchSync(ctx, manifest);
            if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
        });
    }

    private static void fetchSync(Context ctx, String manifest) {
        File d = dir(ctx);
        if (d == null) return;
        List<String> list = isRemote(manifest) ? fromUrl(manifest) : urls(ctx, manifest);
        if (list.isEmpty()) return;
        d.mkdirs();
        int kept = 0;
        int index = 0;
        for (String url : list) {
            if (kept >= MAX_KEEP) break;
            File dest = new File(d, String.format("apple_%02d.jpg", index++));
            if (dest.exists() && dest.length() > 0) { kept++; continue; }
            // A picture over the 10 MB cap can never be shown (WallpaperRotation.images filters it
            // out too), so remember it rather than re-downloading the same bytes on every resume.
            if (new File(d, dest.getName() + ".skip").exists()) continue;
            if (download(url, dest)) kept++;
        }
        trim(d);
        Log.i(TAG, "Apple still wallpapers on disk: " + WallpaperRotation.images(d).size());
    }

    /** Drop the oldest beyond {@link #MAX_KEEP} so repeated refreshes never fill storage. */
    private static void trim(File dir) {
        List<File> all = WallpaperRotation.images(dir);
        if (all.size() <= MAX_KEEP) return;
        List<File> byAge = new ArrayList<>(all);
        byAge.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < byAge.size() - MAX_KEEP; i++) byAge.get(i).delete();
    }

    private static List<String> fromAssets(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            return parse(in);
        } catch (Exception e) {
            Log.w(TAG, "cannot read still-wallpaper manifest " + name + ": " + e.getMessage());
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
            Log.w(TAG, "cannot fetch still-wallpaper manifest " + manifest + ": " + e.getMessage());
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
        JSONArray images = root.optJSONArray("images");
        List<String> urls = new ArrayList<>();
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject item = images.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "").trim();
                if (url.isEmpty()) url = item.optString("src", "").trim();
                if (!url.isEmpty()) urls.add(url);
            }
        }
        return urls;
    }

    private static boolean download(String url, File dest) {
        HttpURLConnection conn = null;
        try {
            String https = url.replaceFirst("^http://", "https://");
            URL u = new URL(https);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            byte[] bytes = readAll(conn.getInputStream());
            if (bytes.length == 0) return false;
            if (bytes.length > MAX_BYTES) {
                // Too big for the rotation's own file-size filter: mark it skipped once and for all.
                // The .skip marker is not an image, so WallpaperRotation.images ignores it.
                try {
                    new File(dest.getParentFile(), dest.getName() + ".skip").createNewFile();
                } catch (Exception ignored) { }
                return false;
            }
            File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            Log.w(TAG, "Apple still download failed " + url + ": " + e.getMessage());
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
