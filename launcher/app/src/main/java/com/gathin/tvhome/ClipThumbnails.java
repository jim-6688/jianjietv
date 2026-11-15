package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 动态壁纸是网络视频流，收藏它时手上只有一个 URL，没有可以当预览的本地图片。
 * 这里给设置面板的收藏格子补一张真实画面：在后台从流里抽一帧，按 URL 缓存到磁盘，之后再打开就直接读。
 *
 * 抽帧会真的走网络（MediaMetadataRetriever 拉一段再解关键帧），所以只在被问到的那一条上做，
 * 串行排队，失败记在内存里不反复重试；缓存命中时完全不碰网络。
 */
final class ClipThumbnails {
    private static final String TAG = "TvHome.ClipThumb";
    /** 抽帧位置：跳过片头常见的黑场，3 秒处通常已经有画面。 */
    private static final long FRAME_US = 3_000_000L;
    /** 存盘尺寸：收藏格约 180×100dp，2 倍屏下这张够用，再大只是占空间。 */
    private static final int THUMB_W = 480;
    private static final int THUMB_H = 270;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** 本次进程抽到的帧；设置面板重建时不必再读盘。 */
    private static final Map<String, Bitmap> MEMORY = new LinkedHashMap<>();
    /** 正在抽的 URL，防止同一个格子触发多次。 */
    private static final Set<String> RUNNING = new HashSet<>();
    /** 抽失败的 URL → 失败时刻：一小时内不再重试（refresh 很频繁，否则每次点设置都会重走一遍网络）。 */
    private static final Map<String, Long> FAILED = new LinkedHashMap<>();
    private static final long RETRY_AFTER_MS = 60L * 60_000L;

    private ClipThumbnails() { }

    /** 缓存目录；拿不到数据目录（极早期启动）时返回 null。 */
    private static File cacheDir(Context ctx) {
        File base = Config.dir(ctx);
        return base == null ? null : new File(base, "clip_thumbs");
    }

    /**
     * 已经能显示的帧：内存 → 磁盘 → null。返回 null 时后台开始抽，抽到会通过 {@link #fetch} 的回调送回。
     */
    static Bitmap cached(Context ctx, String url) {
        Bitmap hit = MEMORY.get(url);
        if (hit != null) return hit;
        File file = cacheFile(ctx, url);
        if (file == null || !file.isFile() || file.length() == 0) return null;
        Bitmap bmp = BitmapFactory.decodeFile(file.getPath());
        if (bmp == null) {
            file.delete();
            return null;
        }
        remember(url, bmp);
        return bmp;
    }

    /**
     * 抽一帧（或直接从缓存拿）。回调只会在主线程、且只发一次：失败时给 null。
     * 缓存里已有帧时立刻回调，不切线程。
     */
    static void fetch(Context ctx, String url, Consumer<Bitmap> done) {
        if (url == null || url.isEmpty()) {
            done.accept(null);
            return;
        }
        Bitmap hit = cached(ctx, url);
        if (hit != null) {
            done.accept(hit);
            return;
        }
        Long failedAt = FAILED.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return;
        if (!RUNNING.add(url)) return;   // 正在抽
        Context app = ctx.getApplicationContext();
        WORKER.execute(() -> {
            Bitmap frame = readFrame(url);
            File file = cacheFile(app, url);
            if (frame != null && file != null) save(frame, file);
            Bitmap result = frame;
            MAIN.post(() -> {
                RUNNING.remove(url);
                if (result == null) {
                    FAILED.put(url, System.currentTimeMillis());
                    Log.w(TAG, "no frame for " + url);
                } else {
                    FAILED.remove(url);
                    remember(url, result);
                }
                done.accept(result);
            });
        });
    }

    /** 从网络流里解一帧；失败返回 null（流不可达、编码不支持等）。 */
    private static Bitmap readFrame(String url) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(url, new HashMap<String, String>());
            Bitmap frame = retriever.getFrameAtTime(FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            return frame == null ? null : scale(frame, THUMB_W, THUMB_H);
        } catch (RuntimeException | OutOfMemoryError failed) {
            Log.w(TAG, "readFrame failed: " + failed.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 抽出来的帧通常就是 1920×1080；缩到预览尺寸再存，省内存也省盘。 */
    private static Bitmap scale(Bitmap source, int maxW, int maxH) {
        int w = source.getWidth(), h = source.getHeight();
        if (w <= maxW && h <= maxH) return source;
        float ratio = Math.min((float) maxW / w, (float) maxH / h);
        Bitmap scaled = Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(w * ratio)), Math.max(1, Math.round(h * ratio)), true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    private static void save(Bitmap frame, File file) {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(file)) {
            frame.compress(Bitmap.CompressFormat.JPEG, 86, out);
        } catch (Exception failed) {
            Log.w(TAG, "cache write failed: " + failed.getMessage());
            file.delete();
        }
    }

    /** 内存缓存只留最近若干张：收藏一般就几条，留 24 张足够铺满一屏。 */
    private static void remember(String url, Bitmap bmp) {
        MEMORY.put(url, bmp);
        while (MEMORY.size() > 24) {
            java.util.Iterator<Map.Entry<String, Bitmap>> it = MEMORY.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private static File cacheFile(Context ctx, String url) {
        File dir = cacheDir(ctx);
        return dir == null ? null : new File(dir, hash(url) + ".jpg");
    }

    /** URL → 稳定文件名：同一条片源下次打开还是这张缓存。 */
    private static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception unavailable) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
