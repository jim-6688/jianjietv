package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.TextureView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final int backgroundColor;
    private final List<File> files;
    /** 轮换间隔：设置面板的「立即生效 / 24 小时轮换」会在运行时改变它，故非 final。 */
    private long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    /** Video wallpaper layer (Aerial-style): a streamed, looped, muted clip behind Home. Null in picture mode. */
    private TextureView videoView;
    private ExoPlayer player;
    private List<String> videoUrls;
    private boolean videoMode;
    /** App context, so playOne() can read the favourite clip the owner pinned without a Context parameter. */
    private final Context appContext;
    /** URL of the clip currently playing; empty in still mode. Mirrors wallpaper_rotation's video_url. */
    private String currentVideoUrl = "";
    /** The favourite clip last pinned for playback; empty means the manifest picks clips at random. */
    private String pinnedUrl = "";
    private int lastVideoW, lastVideoH;
    private final Random videoRandom = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        appContext = context.getApplicationContext();
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        // Video wallpaper (Aerial-style): stream a looping, muted clip behind Home. Falls back to the
        // still-picture rotation only when the video switch is off or the source is unavailable.
        boolean videoPending = false;
        pinnedUrl = WallpaperFavorites.selectedVideo(context);
        if (config.wallpaperVideoEnabled) {
            List<String> list = videoSources(context, config);
            if (!list.isEmpty()) {
                setupVideoLayer(context, list);
            } else if (WallpaperVideo.isRemote(config.wallpaperVideoManifest)) {
                // Remote manifest: no URLs cached yet. Fetch off the UI thread; build the layer when they arrive.
                videoPending = true;
                WallpaperVideo.fetchRemote(config.wallpaperVideoManifest, urls -> {
                    if (videoView == null && !urls.isEmpty()) setupVideoLayer(context, urls);
                });
            }
        }
        if (!videoPending && videoView == null && config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Log.i("TvHome.Tone", "showFirst start, files=" + files.size());
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Decoded decoded = decode(first);
            if (decoded != null) {
                show(decoded.bitmap, decoded.tone);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                recordCurrent(first);
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    /**
     * Swap in a freshly downloaded picture collection (Apple stills, or the 360 fallback) without
     * rebuilding Home. Called on the UI thread once the background fetch ends. An unchanged
     * collection is a no-op, so this is safe to call on every resume.
     */
    void setFiles(List<File> replacement) {
        if (videoMode || videoView != null || closed || replacement == null || replacement.isEmpty()) return;
        String key = collectionKey(replacement);
        if (key.equals(collectionKey(files))) return;
        files.clear();
        files.addAll(replacement);
        // Playback state belongs to the old collection: start this one from its first picture.
        preferences.edit().putString("collection", key)
                .remove("image").remove("changed").apply();
        if (image.getParent() == null) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        loading = false;
        showFirst();
    }

    /**
     * 设置变更后实时重配壁纸：静态 / 动态运行时互切，不必重建首页。
     * 视频层在构造时按初始配置建立，这里负责运行中按新配置建立或拆除它。
     */
    void reconfigure(Context context, Config config) {
        if (closed) return;
        interval = config.wallpaperRotationMinutes * 60_000L;
        boolean wantVideo = config.wallpaperVideoEnabled;

        if (wantVideo) {
            String pinned = WallpaperFavorites.selectedVideo(context);
            List<String> list = videoSources(context, config);
            if (videoView == null) {
                // 静态 -> 动态：片源就绪才切，起不来就继续显示当前静态图。
                pinnedUrl = pinned;
                if (!list.isEmpty()) {
                    bringUpVideo(context, list);
                } else if (WallpaperVideo.isRemote(config.wallpaperVideoManifest)) {
                    WallpaperVideo.fetchRemote(config.wallpaperVideoManifest, urls -> {
                        if (!urls.isEmpty() && videoView == null && !closed) bringUpVideo(context, urls);
                    });
                }
                return;
            }
            // 已在视频模式：收藏列表里选中的片源变了就换片（或取消固定后恢复随机）。
            if (!pinned.equals(pinnedUrl) || !list.equals(videoUrls)) {
                pinnedUrl = pinned;
                if (!list.isEmpty()) {
                    videoUrls = list;
                    playOne();
                    if (active && player != null) player.play();
                } else if (WallpaperVideo.isRemote(config.wallpaperVideoManifest)) {
                    // 远程 manifest 还没缓存：拉到片源再换，别一直停在旧片上。
                    WallpaperVideo.fetchRemote(config.wallpaperVideoManifest, urls -> {
                        if (!urls.isEmpty() && videoView != null && !closed) {
                            videoUrls = urls;
                            playOne();
                            if (active && player != null) player.play();
                        }
                    });
                }
            }
            return;
        }

        boolean wasVideo = videoView != null;
        if (wasVideo) {
            // 动态 -> 静态：拆掉视频层，恢复静态图。
            tearDownVideo();
        }

        // 静态模式：同步最新的静态图片集合（收藏 / 来源变化）；集合没变就不重新解码。
        if (videoView == null && config.wallpaperEnabled && !config.wallpapers.isEmpty()) {
            String newKey = collectionKey(config.wallpapers);
            boolean collectionChanged = !newKey.equals(collectionKey(files));
            if (collectionChanged) {
                files.clear();
                files.addAll(config.wallpapers);
                preferences.edit().putString("collection", newKey)
                        .remove("image").remove("changed").apply();
            }
            // 从视频切回时必须重新解码：recordCurrent(null) 清过 path，星标要能重新认出这张静态图。
            if (collectionChanged || wasVideo) {
                displayed = "";
                loading = false;
                showFirst();
            }
            if (toneListener != null && tone >= 0f) toneListener.onTone(tone);
        }
    }

    /**
     * 片源：收藏列表里指定的那一条动态壁纸优先（固定播放），否则用 manifest 的全部片源随机轮换。
     * 这样点收藏列表里的一条视频就能真正把它播出来，而不是继续随机。
     */
    private List<String> videoSources(Context context, Config config) {
        String pinned = WallpaperFavorites.selectedVideo(context);
        if (!pinned.isEmpty()) return Collections.singletonList(pinned);
        return WallpaperVideo.urls(context, config.wallpaperVideoManifest);
    }

    /** 建立视频层并进入视频模式：视频层叠在静态图之上，首帧就绪后由回调撤掉静态图，全程无空窗。 */
    private void bringUpVideo(Context context, List<String> urls) {
        if (setupVideoLayer(context, urls) && active && player != null) player.play();
        if (toneListener != null) toneListener.onTone(0.35f); // Aerial clips are mostly dark; keep light text.
    }

    /** 拆掉视频层、释放播放器，把静态图放回视图树。 */
    private void tearDownVideo() {
        videoMode = false;
        videoUrls = null;
        currentVideoUrl = "";
        pinnedUrl = "";
        preferences.edit().remove(WallpaperFavorites.VIDEO_CURRENT_KEY).apply();
        lastVideoW = lastVideoH = 0;
        if (player != null) {
            player.release();
            player = null;
        }
        if (videoView != null) {
            root.removeView(videoView);
            videoView = null;
        }
        if (image.getParent() == null) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    void start() {
        active = true;
        if (videoMode && player != null) {
            player.play();
            // Aerial clips are mostly dark; tell the UI to keep light text on top.
            if (toneListener != null) toneListener.onTone(0.35f);
            return;
        }
        update();
    }

    void stop() {
        active = false;
        if (player != null) player.pause();
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (videoMode) {
            // A streaming clip owns its own decoder buffers; pause and let the OS reclaim if it must.
            if (player != null) player.pause();
            return;
        }
        if (current == null) return;
        image.setImageDrawable(null);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** Builds the ExoPlayer video layer and prepares a clip. Returns true once the layer is up. */
    private boolean setupVideoLayer(Context context, List<String> list) {
        if (list == null || list.isEmpty()) return false;
        videoMode = true;
        recordCurrent(null);   // no still path to copy; playOne() records the clip URL for the star
        videoUrls = list;
        // TextureView instead of media3-ui's PlayerView: keeps the dependency to the already-cached
        // media3-exoplayer and lets us paint a centerCrop transform by hand. It throws on any background
        // drawable ("TextureView doesn't support displaying a background drawable"), so the plain colour
        // stays on the root, behind it.
        videoView = new TextureView(context);
        // 关键：把视频层放在静态图之上（而非直接 remove 静态图）。TextureView 首帧解码出来前是透明的，
        // 底下的静态图正好透出来当占位；首帧真正画出来后再撤掉静态图，切动态壁纸就不会闪一下纯色。
        // 静态图不在视图树里时（冷启动就是视频模式），index 0 即可。
        int videoIndex = image.getParent() == root ? root.indexOfChild(image) + 1 : 0;
        root.addView(videoView, videoIndex, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Log.i("TvHome.Wallpaper", "video layer up: " + list.size() + " clips, screen " + width + "x" + height);
        player = new ExoPlayer.Builder(context).build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE); // loop the single clip seamlessly
        player.setVolume(0f);                         // muted background
        player.setPlayWhenReady(false);               // wait for start()
        player.setVideoTextureView(videoView);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize size) {
                lastVideoW = size.width;
                lastVideoH = size.height;
                Log.i("TvHome.Wallpaper", "video size " + size.width + "x" + size.height);
                applyCrop();
            }

            @Override
            public void onRenderedFirstFrame() {
                // 视频第一帧真的画出来了，底下的静态图不再需要；此时撤掉不会露出纯色空窗。
                if (videoView != null && image.getParent() == root) root.removeView(image);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                fallBackToStills(error.getMessage());
            }
        });
        // setVideoTextureView installs the player's own SurfaceTextureListener, which is how it learns the texture is
        // up and gets a surface at all: replacing it left the player stuck buffering with no picture (state=2,
        // surface=false, not one frame decoded). Keep that one and hang the crop off it.
        final TextureView.SurfaceTextureListener playerListener = videoView.getSurfaceTextureListener();
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (playerListener != null) playerListener.onSurfaceTextureAvailable(st, w, h);
                applyCrop();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                if (playerListener != null) playerListener.onSurfaceTextureSizeChanged(st, w, h);
                applyCrop();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return playerListener != null && playerListener.onSurfaceTextureDestroyed(st);
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (playerListener != null) playerListener.onSurfaceTextureUpdated(st);
            }
        });
        playOne();
        return true;
    }

    /** Picks a random clip from the manifest and prepares it (playing waits for start()). */
    private void playOne() {
        if (videoUrls == null || videoUrls.isEmpty() || player == null) return;
        String url = videoUrls.get(videoRandom.nextInt(videoUrls.size()));
        try {
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            // 记下正在播放的那一条，星标按钮据此收藏动态壁纸。
            recordCurrentVideo(url);
        } catch (Exception e) {
            Log.w("TvHome.Wallpaper", "video wallpaper failed to prepare " + url + ": " + e.getMessage());
        }
    }

    /**
     * The clip never arrives (a host that answers only on http, no route at all, a codec the box refuses): drop the
     * layer and put the picture rotation back, instead of leaving Home on a bare background colour for good.
     */
    private void fallBackToStills(String why) {
        Log.w("TvHome.Wallpaper", "video wallpaper gave up: " + why);
        videoMode = false;
        videoUrls = null;
        currentVideoUrl = "";
        preferences.edit().remove(WallpaperFavorites.VIDEO_CURRENT_KEY).apply();
        if (player != null) {
            player.release();
            player = null;
        }
        if (videoView != null) {
            root.removeView(videoView);
            videoView = null;
        }
        if (closed || files.isEmpty() || image.getParent() != null) return;
        root.addView(image, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showFirst();
    }

    /** centerCrop: scale the video to cover the screen, cropping the overflow (like Aerial on Apple TV). */
    private void applyCrop() {
        if (videoView == null || lastVideoW <= 0 || lastVideoH <= 0) return;
        float scale = Math.max((float) width / lastVideoW, (float) height / lastVideoH);
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((width - lastVideoW * scale) / 2f, (height - lastVideoH * scale) / 2f);
        videoView.setTransform(m);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        if (player != null) {
            player.release();
            player = null;
        }
        decoder.shutdownNow();
    }

    void update() {
        if (videoMode) return; // a streaming clip, not a rotating picture collection
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Decoded decoded = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (decoded != null) decoded.bitmap.recycle();
                    return;
                }
                if (decoded == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(decoded.bitmap, decoded.tone);
                displayed = next.getName();
                recordCurrent(next);
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** decode() 的产出：显示用位图 + 在软件位图上量好的平均亮度（0=全黑，1=全白）。 */
    private static final class Decoded {
        final Bitmap bitmap;
        final float tone;

        Decoded(Bitmap bitmap, float tone) {
            this.bitmap = bitmap;
            this.tone = tone;
        }
    }

    /**
     * WallpaperPlayer already records the picture's name in these preferences (ClockScene reads it).
     * Record the full path too, so the settings panel can copy exactly the picture on screen into
     * the favourites folder. Video mode passes null: there is no still to favourite.
     */
    private void recordCurrent(File file) {
        // A still picture is on screen: it becomes the favourite target, and any clip marker is stale.
        currentVideoUrl = "";
        preferences.edit().putString("path", file == null ? "" : file.getAbsolutePath())
                .remove(WallpaperFavorites.VIDEO_CURRENT_KEY).apply();
    }

    /** A streamed clip is on screen: the star saves this URL, and there is no still path to copy. */
    private void recordCurrentVideo(String url) {
        currentVideoUrl = url == null ? "" : url;
        preferences.edit().putString(WallpaperFavorites.VIDEO_CURRENT_KEY, currentVideoUrl)
                .putString("path", "").apply();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap, float luminance) {
        Bitmap old = current;
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        image.setImageBitmap(bitmap);
        tone = luminance;
        Log.i("TvHome.Tone", "luminance=" + tone + " dim=" + dimColor);
        if (toneListener != null) toneListener.onTone(tone);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    /** 壁纸明暗回调：0（全黑）到 1（全白），首页据此决定文字用深色还是浅色。 */
    interface ToneListener {
        void onTone(float luminance);
    }

    private ToneListener toneListener;
    private float tone = -1f;

    void setToneListener(ToneListener listener) {
        toneListener = listener;
        // 构造时 showFirst() 抢在 MainActivity 注册监听之前，这里补发一次。
        if (listener != null && tone >= 0f) listener.onTone(tone);
    }

    /**
     * 亮度必须在转成硬件位图之前量：Config.HARDWARE 不能 getPixels，也不能画进软件 Canvas
     * （会抛 "Software rendering doesn't support hardware bitmaps"）。缩到 16×16 只取 256 点。
     */
    private static float sampleTone(Bitmap software) {
        final int size = 16;
        Bitmap small = Bitmap.createScaledBitmap(software, size, size, true);
        int[] pixels = new int[size * size];
        small.getPixels(pixels, 0, size, 0, 0, size, size);
        float sum = 0f;
        for (int c : pixels) {
            sum += (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF)
                    + 0.114f * (c & 0xFF)) / 255f;
        }
        if (small != software) small.recycle();
        return sum / pixels.length;
    }

    private Decoded decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            // Apple's macOS stills run up to 5K/6K and some are unusually wide: a single screen-sized
            // downsample can still leave them over the 4K decode budget. Keep halving until the bitmap
            // fits instead of skipping the picture; CENTER_CROP scales it back up to cover the screen.
            while (!WallpaperSize.accepted(width / sample, height / sample)
                    && sample < 32
                    && width / (sample * 2) >= this.width / 2
                    && height / (sample * 2) >= this.height / 2) {
                sample *= 2;
            }
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // 暗化已经画进像素，这里量到的就是实际显示出来的明暗。
            float luminance = sampleTone(decoded);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return new Decoded(decoded, luminance);
            decoded.recycle();
            return new Decoded(gpu, luminance);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
