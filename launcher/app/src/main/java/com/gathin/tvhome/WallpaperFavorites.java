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
 * Wallpapers the person liked. A favourite is <em>copied</em> into its own folder: the Apple and 360
 * collections are trimmed on every refresh, so keeping a path into those folders would break as soon
 * as the cache rolls over. The copy keeps the picture's name, which is how the rotation and the
 * settings panel recognise it.
 *
 * <p>Two on-TV choices live in {@link Config#SETTINGS_NAME} so the usual settings revision rebuilds
 * Home: one picked favourite held on screen ({@link #SELECTED_KEY}), or a rotation limited to the
 * favourites ({@link #ONLY_KEY}). They are mutually exclusive; picking one picture wins.</p>
 */
final class WallpaperFavorites {
    /** Rotate only through the favourites instead of the automatic wallpaper sources. */
    static final String ONLY_KEY = "wallpaper_favorites_only";
    /** File name of the one favourite to hold on screen. */
    static final String SELECTED_KEY = "wallpaper_favorite_selected";
    private static final String ROTATION_PREFS = "wallpaper_rotation";
    /** Same cap as WallpaperRotation.images: a bigger picture can never be decoded anyway. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final String TAG = "TvHome.Favorites";

    private WallpaperFavorites() { }

    static File dir(Context ctx) {
        File base = Config.dir(ctx);
        return base == null ? null : new File(base, "favorites");
    }

    /** Favourites ready to show, filtered exactly like any other wallpaper collection. */
    static List<File> files(Context ctx) {
        File d = dir(ctx);
        return d == null ? new ArrayList<>() : WallpaperRotation.images(d);
    }

    /** Absolute path WallpaperPlayer recorded for the still picture currently on screen; empty in video mode. */
    static String currentPath(Context ctx) {
        return ctx.getSharedPreferences(ROTATION_PREFS, Context.MODE_PRIVATE)
                .getString("path", "");
    }

    /** The picture currently on screen, or null when there is no usable still (video mode, first frame). */
    static File current(Context ctx) {
        String path = currentPath(ctx);
        if (path.isEmpty()) return null;
        File f = new File(path);
        return f.isFile() && f.length() > 0 && f.length() <= MAX_BYTES ? f : null;
    }

    /**
     * Save (or unsave) exactly the picture Home is showing right now. Shared by the Home star button
     * and Launcher settings so both behave the same; the caller only refreshes its own view afterwards.
     */
    static void toggle(Context ctx) {
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
     * The favourites list: pick one to hold it on screen, long-press one to remove it, or switch the
     * rotation to favourites only. {@code onWallpaperChange} runs when the wallpaper source itself
     * changes (Home must rebuild; the settings panel just refreshes), {@code onListChange} when an
     * entry is deleted (Home only needs to repaint its star).
     */
    static void showPicker(Context ctx, Runnable onWallpaperChange, Runnable onListChange) {
        List<File> favourites = files(ctx);
        if (favourites.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.wallpaper_favorites_title)
                    .setMessage(R.string.wallpaper_favorites_empty)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        String[] labels = new String[favourites.size()];
        for (int i = 0; i < favourites.size(); i++) labels[i] = favourites.get(i).getName();
        String selected = selected(ctx);
        int checked = -1;
        for (int i = 0; i < favourites.size(); i++) {
            if (favourites.get(i).getName().equals(selected)) checked = i;
        }
        final boolean only = only(ctx);
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.wallpaper_favorites_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    select(ctx, favourites.get(which).getName());
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
            remove(ctx, favourites.get(position));
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

    /** Delete a favourite and clear any on-TV choice that pointed at it. */
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

    static boolean only(Context ctx) {
        return prefs(ctx).getBoolean(ONLY_KEY, false);
    }

    /** Turning this on drops the single-picture pick; the two choices are mutually exclusive. */
    static void setOnly(Context ctx, boolean on) {
        SharedPreferences.Editor edit = prefs(ctx).edit();
        if (on) edit.putBoolean(ONLY_KEY, true).remove(SELECTED_KEY);
        else edit.remove(ONLY_KEY);
        edit.apply();
    }

    static String selected(Context ctx) {
        return prefs(ctx).getString(SELECTED_KEY, null);
    }

    /** Hold one picture on screen; clears the "favourites only" rotation. */
    static void select(Context ctx, String name) {
        prefs(ctx).edit().putString(SELECTED_KEY, name).remove(ONLY_KEY).apply();
    }

    static void clearSelection(Context ctx) {
        prefs(ctx).edit().remove(SELECTED_KEY).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
