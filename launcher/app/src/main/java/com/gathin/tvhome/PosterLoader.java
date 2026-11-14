package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 轻量网络图片加载（海报）与模糊背景工具，不引入第三方库。 */
final class PosterLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 异步加载海报到 ImageView；先淡出，加载完淡入。用 tag 防错位（列表复用）。 */
    static void load(ImageView iv, String url) {
        if (iv == null || url == null) return;
        iv.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            iv.setAlpha(1f);
            return;
        }
        iv.setImageDrawable(null);
        iv.setAlpha(0f);
        POOL.execute(() -> {
            Bitmap bmp = download(url, 420);
            if (bmp != null) CACHE.put(url, bmp);
            MAIN.post(() -> {
                if (bmp != null && url.equals(iv.getTag())) {
                    iv.setImageBitmap(bmp);
                    iv.animate().alpha(1f).setDuration(180).start();
                }
            });
        });
    }

    /** 异步加载并模糊成影视背景；保持 ImageView 自身的 alpha。 */
    static void loadBlurred(ImageView iv, String url) {
        if (iv == null || url == null) return;
        iv.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            Bitmap blurred = blur(cached);
            if (blurred != null) iv.setImageBitmap(blurred);
            return;
        }
        POOL.execute(() -> {
            Bitmap bmp = download(url, 300);
            Bitmap blurred = blur(bmp);
            if (bmp != null && blurred != bmp) bmp.recycle();
            MAIN.post(() -> {
                if (blurred != null && url.equals(iv.getTag())) iv.setImageBitmap(blurred);
            });
        });
    }

    /** 同步下载并按目标宽度采样解码（背景用）。 */
    static Bitmap download(String url, int targetWidth) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            conn.setRequestProperty("Referer", "https://m.douban.com/");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9)");
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                bytes = bos.toByteArray();
            }
            if (bytes.length == 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / sample > targetWidth * 2) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 模糊：先缩到很小再放大，得到柔和的影视背景（比高斯模糊省太多）。 */
    static Bitmap blur(Bitmap src) {
        if (src == null) return null;
        int w = 28;
        int h = Math.max(1, src.getHeight() * w / Math.max(1, src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        Bitmap big = Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
        if (small != big) small.recycle();
        return big;
    }
}
