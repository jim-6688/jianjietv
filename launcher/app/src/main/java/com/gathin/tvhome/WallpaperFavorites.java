package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Wallpapers the person liked, in two shapes:
 *
 * <p><b>Stills</b> are <em>copied</em> into their own folder: the Apple and 360 collections are
 * trimmed on every refresh, so keeping a path into those folders would break as soon as the cache
 * rolls over. The copy keeps the picture's name, which is how the rotation and the settings panel
 * recognise it.</p>
 *
 * <p><b>Videos</b> are streamed URLs, not files on the device, so they are stored as a newline-separated
 * list in {@link Config#SETTINGS_NAME} instead. The star button saves whichever is on screen: a still
 * picture or the exact clip currently playing. Picking a favourite holds it on screen; stills and
 * videos are mutually exclusive (Config gives the video switch the last word).</p>
 */
final class WallpaperFavorites {
    /** Rotate only through the favourite stills instead of the automatic wallpaper sources. */
    static final String ONLY_KEY = "wallpaper_favorites_only";
    /** File name of the one favourite still to hold on screen. */
    static final String SELECTED_KEY = "wallpaper_favorite_selected";
    /** Newline-separated list of favourite video (dynamic wallpaper) URLs. */
    static final String VIDEO_KEY = "wallpaper_video_favorites";
    /** One favourite video URL to hold on screen; empty means the manifest picks clips at random. */
    static final String VIDEO_SELECTED_KEY = "wallpaper_video_selected";
    /** WallpaperPlayer records the clip currently playing here, so the star knows what to save. */
    static final String VIDEO_CURRENT_KEY = "video_url";
    private static final String ROTATION_PREFS = "wallpaper_rotation";
    /** Same cap as WallpaperRotation.images: a bigger picture can never be decoded anyway. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final String TAG = "TvHome.Favorites";

    private WallpaperFavorites() { }

    static File dir(Context ctx) {
        File base = Config.dir(ctx);
        return base == null ? null : new File(base, "favorites");
    }

    /** Favourite stills ready to show, filtered exactly like any other wallpaper collection. */
    static List<File> files(Context ctx) {
        File d = dir(ctx);
        return d == null ? new ArrayList<>() : WallpaperRotation.images(d);
    }

    /** Favourite video URLs, in the order they were saved. */
    static List<String> videos(Context ctx) {
        String raw = prefs(ctx).getString(VIDEO_KEY, "");
        List<String> result = new ArrayList<>();
        if (raw.isEmpty()) return result;
        for (String part : raw.split("\n")) {
            String url = part.trim();
            if (!url.isEmpty() && !result.contains(url)) result.add(url);
        }
        return result;
    }

    /** Both kinds, for the settings panel's count. */
    static int count(Context ctx) {
        return files(ctx).size() + videos(ctx).size();
    }

    /** A short readable name for a streamed clip, which has no local file name of its own. */
    static String videoLabel(String url) {
        String name = url == null ? "" : url;
        int query = name.indexOf('?');
        if (query >= 0) name = name.substring(0, query);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (name.isEmpty()) name = url == null ? "" : url;
        return name.length() > 30 ? name.substring(0, 30) + "…" : name;
    }

    /** Absolute path WallpaperPlayer recorded for the still picture currently on screen; empty in video mode. */
    static String currentPath(Context ctx) {
        return ctx.getSharedPreferences(ROTATION_PREFS, Context.MODE_PRIVATE)
                .getString("path", "");
    }

    /** The clip currently playing, or empty when a still picture (or nothing) is on screen. */
    static String currentVideoUrl(Context ctx) {
        return ctx.getSharedPreferences(ROTATION_PREFS, Context.MODE_PRIVATE)
                .getString(VIDEO_CURRENT_KEY, "");
    }

    /** The still picture currently on screen, or null when there is no usable still (video mode, first frame). */
    static File current(Context ctx) {
        String path = currentPath(ctx);
        if (path.isEmpty()) return null;
        File f = new File(path);
        return f.isFile() && f.length() > 0 && f.length() <= MAX_BYTES ? f : null;
    }

    /**
     * Save (or unsave) exactly the wallpaper Home is showing right now: the current clip in video
     * mode, otherwise the current still picture. Shared by the Home star button and Launcher settings.
     */
    static void toggle(Context ctx) {
        String clip = currentVideoUrl(ctx);
        if (!clip.isEmpty()) {
            toggleVideo(ctx, clip);
            return;
        }
        File picture = current(ctx);
        if (picture == null) {
            Toast.makeText(ctx, R.string.wallpaper_favorite_none, Toast.LENGTH_SHORT).show();
            return;
        }
        List<File> favourites = files(ctx);
        if (contains(favourites, picture)) {
            remove(ctx, find(favourites, picture.getName()));
            Toast.makeText(ctx, R.string.wallpaper_favorite_removed, Toast.LENGTH_SHORT).show();
        } else if (add(ctx, picture)) {
            Toast.makeText(ctx, R.string.wallpaper_favorite_added, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(ctx, R.string.wallpaper_favorite_none, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The favourites list, with stills first and streamed clips after them. Pick one to hold it on
     * screen, long-press one to remove it, or switch the still rotation to favourites only.
     * {@code onWallpaperChange} runs when the wallpaper source itself changes, {@code onListChange}
     * when an entry is deleted (Home then only needs to repaint its star).
     */
    static void showPicker(Context ctx, Runnable onWallpaperChange, Runnable onListChange) {
        final List<File> stills = files(ctx);
        final List<String> clips = videos(ctx);
        final int stillCount = stills.size();
        if (stillCount == 0 && clips.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.wallpaper_favorites_title)
                    .setMessage(R.string.wallpaper_favorites_empty)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        String[] labels = new String[stillCount + clips.size()];
        for (int i = 0; i < stillCount; i++) labels[i] = stills.get(i).getName();
        for (int i = 0; i < clips.size(); i++) {
            labels[stillCount + i] = ctx.getString(
                    R.string.wallpaper_favorite_video_item, videoLabel(clips.get(i)));
        }
        String selectedStill = selected(ctx);
        String selectedClip = selectedVideo(ctx);
        int checked = -1;
        for (int i = 0; i < stillCount; i++) {
            if (stills.get(i).getName().equals(selectedStill)) checked = i;
        }
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i).equals(selectedClip)) checked = stillCount + i;
        }
        final boolean only = only(ctx);
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.wallpaper_favorites_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which < stillCount) select(ctx, stills.get(which).getName());
                    else selectVideo(ctx, clips.get(which - stillCount));
                    Toast.makeText(ctx, R.string.wallpaper_favorite_applied, Toast.LENGTH_SHORT).show();
                    if (onWallpaperChange != null) onWallpaperChange.run();
                })
                .setNeutralButton(only ? R.string.wallpaper_favorites_only_on
                        : R.string.wallpaper_favorites_only_off, (d, which) -> {
                    setOnly(ctx, !only);
                    if (onWallpaperChange != null) onWallpaperChange.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        // Long-press removes one favourite without a second dialog.
        dialog.getListView().setOnItemLongClickListener((parent, item, position, id) -> {
            if (position < stillCount) remove(ctx, stills.get(position));
            else removeVideo(ctx, clips.get(position - stillCount));
            Toast.makeText(ctx, R.string.wallpaper_favorite_deleted, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if (onListChange != null) onListChange.run();
            return true;
        });
    }

    static boolean contains(List<File> favourites, File picture) {
        if (picture == null) return false;
        for (File f : favourites) if (f.getName().equals(picture.getName())) return true;
        return false;
    }

    static File find(List<File> favourites, String name) {
        if (name == null) return null;
        for (File f : favourites) if (f.getName().equals(name)) return f;
        return null;
    }

    /** Copy the picture into the favourites folder. Returns false when it cannot be shown as a wallpaper. */
    static boolean add(Context ctx, File picture) {
        if (picture == null || !picture.isFile() || picture.length() == 0
                || picture.length() > MAX_BYTES) return false;
        File d = dir(ctx);
        if (d == null) return false;
        if (!d.isDirectory() && !d.mkdirs()) return false;
        File dest = new File(d, picture.getName());
        if (dest.exists() && dest.length() == picture.length()) return true;   // already saved
        File tmp = new File(d, picture.getName() + ".part");
        try (InputStream in = new FileInputStream(picture);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            Log.w(TAG, "cannot save favourite: " + e.getMessage());
            tmp.delete();
            return false;
        }
        return tmp.renameTo(dest);
    }

    /** Delete a favourite still and clear any on-TV choice that pointed at it. */
    static void remove(Context ctx, File favourite) {
        SharedPreferences prefs = prefs(ctx);
        if (favourite != null) {
            favourite.delete();
            if (favourite.getName().equals(prefs.getString(SELECTED_KEY, ""))) {
                prefs.edit().remove(SELECTED_KEY).apply();
            }
        }
        if (files(ctx).isEmpty()) prefs.edit().remove(ONLY_KEY).apply();
    }

    // ---- streamed clips ----------------------------------------------------------------------

    static boolean containsVideo(Context ctx, String url) {
        return url != null && !url.isEmpty() && videos(ctx).contains(url);
    }

    static boolean addVideo(Context ctx, String url) {
        if (url == null || url.isEmpty()) return false;
        List<String> list = videos(ctx);
        if (list.contains(url)) return true;
        list.add(url);
        saveVideos(ctx, list);
        return true;
    }

    /** Remove a favourite clip; dropping the one held on screen returns video wallpaper to random clips. */
    static void removeVideo(Context ctx, String url) {
        List<String> list = videos(ctx);
        if (!list.remove(url)) return;
        saveVideos(ctx, list);
        if (url.equals(prefs(ctx).getString(VIDEO_SELECTED_KEY, ""))) {
            prefs(ctx).edit().remove(VIDEO_SELECTED_KEY).apply();
        }
    }

    private static void toggleVideo(Context ctx, String url) {
        if (containsVideo(ctx, url)) {
            removeVideo(ctx, url);
            Toast.makeText(ctx, R.string.wallpaper_favorite_removed, Toast.LENGTH_SHORT).show();
        } else if (addVideo(ctx, url)) {
            Toast.makeText(ctx, R.string.wallpaper_favorite_added, Toast.LENGTH_SHORT).show();
        }
    }

    private static void saveVideos(Context ctx, List<String> urls) {
        StringBuilder joined = new StringBuilder();
        for (String url : urls) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(url);
        }
        prefs(ctx).edit().putString(VIDEO_KEY, joined.toString()).apply();
    }

    static String selectedVideo(Context ctx) {
        return prefs(ctx).getString(VIDEO_SELECTED_KEY, "");
    }

    /**
     * Hold one clip on screen: the video wallpaper switch goes on, and the still choices are cleared
     * so Config.load resolves to video mode rather than a picked picture.
     */
    static void selectVideo(Context ctx, String url) {
        if (url == null || url.isEmpty()) return;
        if (!containsVideo(ctx, url)) addVideo(ctx, url);
        prefs(ctx).edit().putString(VIDEO_SELECTED_KEY, url)
                .putBoolean(Config.WALLPAPER_VIDEO_KEY, true)
                .remove(SELECTED_KEY).remove(ONLY_KEY)
                .remove(Config.WALLPAPER_CHOICE_KEY)
                .apply();
    }

    // ---- on-TV choices (stills) ---------------------------------------------------------------

    static boolean only(Context ctx) {
        return prefs(ctx).getBoolean(ONLY_KEY, false);
    }

    /** Turning this on drops the single-picture pick (and any held clip); the choices are mutually exclusive. */
    static void setOnly(Context ctx, boolean on) {
        SharedPreferences.Editor edit = prefs(ctx).edit();
        if (on) edit.putBoolean(ONLY_KEY, true).remove(SELECTED_KEY);
        else edit.remove(ONLY_KEY);
        edit.remove(VIDEO_SELECTED_KEY).remove(Config.WALLPAPER_VIDEO_KEY);
        edit.apply();
    }

    static String selected(Context ctx) {
        return prefs(ctx).getString(SELECTED_KEY, null);
    }

    /** Hold one picture on screen; clears the "favourites only" rotation and any held clip. */
    static void select(Context ctx, String name) {
        prefs(ctx).edit().putString(SELECTED_KEY, name).remove(ONLY_KEY)
                .remove(VIDEO_SELECTED_KEY).remove(Config.WALLPAPER_VIDEO_KEY)
                .apply();
    }

    static void clearSelection(Context ctx) {
        prefs(ctx).edit().remove(SELECTED_KEY).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
