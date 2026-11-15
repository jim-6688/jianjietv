package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 桌面设置的全部交互逻辑，和「放在哪个窗口里」无关：首页右侧抽屉（HomeDrawer）和兼容用的
 * LauncherSettingsActivity 都用它接同一个布局，避免两套代码走偏。
 *
 * 设置项按需求收敛为：桌面设置（应用数量）、壁纸设置（静态/动态双栏 + 立即生效/24 小时轮换 + 收藏列表）、
 * 影视源。其余（调整顺序、程序坞下方行数、动效等）不再暴露，由代码按规则处理。所有改动实时生效，不再重建首页。
 */
final class SettingsController {

    /** 谁承载这个面板：抽屉负责滑出/收起，Activity 负责 finish/setResult。 */
    interface Host {
        Activity activity();

        void close();

        void changed();

        /** No longer wired to any settings row (桌面设置里不再有「调整应用顺序」)，保留以满足宿主的 @Override。 */
        void reorder();

        void openSources();
    }

    private final Host host;
    private final Activity a;
    private final View view;
    private SharedPreferences choices;
    private final TextView[] appCountChoices = new TextView[6];
    private TextView wallpaperChoice;
    private TextView wallpaperFavoritesChoice;
    /** 桌面设置里选的轮换间隔（分钟），0 表示立即生效（固定不动）。 */
    private int wallpaperInterval;

    SettingsController(Host host, View view) {
        this.host = host;
        this.a = host.activity();
        this.view = view;
        bind();
    }

    private void bind() {
        // 点卡片外的暗色区域收起面板；遮罩本身不参与焦点，否则抢走第一个选项的焦点。
        View backdrop = view.findViewById(R.id.backdrop);
        backdrop.setFocusable(false);
        backdrop.setOnClickListener(v -> host.close());
        choices = a.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Config config = Config.load(a);
        // 兼容用的独立 Activity 也用这份布局，抽屉和它都套同一个顶栏透明度档位。
        UiAlpha.applyPanel(view, config.dockOpacity);

        // 应用数量：3..8 六档，写入 dock_count（桌面设置里唯一决定程序坞槽位数的开关）。
        int[] ids = {R.id.appCount3, R.id.appCount4, R.id.appCount5, R.id.appCount6, R.id.appCount7, R.id.appCount8};
        final int[] dockCount = {config.dockCount};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 3;
            appCountChoices[i] = view.findViewById(ids[i]);
            appCountChoices[i].setOnClickListener(v -> {
                if (dockCount[0] == value) return;
                dockCount[0] = value;
                choices.edit().putInt(Config.DOCK_COUNT_KEY, value).apply();
                changed();
                refresh();
            });
        }

        wallpaperInterval = choices.getInt(Config.WALLPAPER_INTERVAL_KEY, 0);
        wallpaperChoice = view.findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        wallpaperFavoritesChoice = view.findViewById(R.id.wallpaperFavoritesChoice);
        wallpaperFavoritesChoice.setOnClickListener(v -> showFavourites());
        // 影视源：在同一个右侧抽屉里换页，不另开窗口。
        view.findViewById(R.id.filmSourcesChoice).setOnClickListener(v -> host.openSources());
        refresh();
    }

    /** 抽屉滑入后再把焦点交给第一个可选项。 */
    void focusFirst(Config config) {
        if (appCountChoices[0] != null) appCountChoices[0].requestFocus();
    }

    /** The same favourites list the Home star button opens, kept in WallpaperFavorites. */
    private void showFavourites() {
        Runnable changedAndRefresh = () -> { changed(); refresh(); };
        WallpaperFavorites.showPicker(a, changedAndRefresh, changedAndRefresh);
    }

    /**
     * 壁纸选择：上方两栏并排——左「静态壁纸」（本地图片 + 主题默认），右「动态壁纸」（在线视频开关）；
     * 下方两档「立即生效 / 24 小时轮换」控制是否自动轮播。
     */
    private void showWallpaperPicker() {
        Context c = a;
        ScrollView scroll = new ScrollView(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        // 两栏：静态 | 动态
        LinearLayout cols = new LinearLayout(c);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout staticCol = new LinearLayout(c);
        staticCol.setOrientation(LinearLayout.VERTICAL);
        staticCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout dynamicCol = new LinearLayout(c);
        dynamicCol.setOrientation(LinearLayout.VERTICAL);
        dynamicCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        staticCol.addView(header(c, R.string.wallpaper_static));
        addStaticItems(c, staticCol);
        dynamicCol.addView(header(c, R.string.wallpaper_dynamic));
        addDynamicItems(c, dynamicCol);
        cols.addView(staticCol);
        cols.addView(dynamicCol);
        root.addView(cols);

        // 两档：立即生效 / 24 小时轮换
        root.addView(header(c, R.string.wallpaper_mode));
        LinearLayout modes = new LinearLayout(c);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        modes.addView(modeItem(c, R.string.wallpaper_immediate, 0));
        modes.addView(modeItem(c, R.string.wallpaper_24h, 1440));
        root.addView(modes);

        scroll.addView(root);
        new AlertDialog.Builder(c)
                .setTitle(R.string.wallpaper_label)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private TextView header(Context c, int stringRes) {
        TextView t = new TextView(c);
        t.setText(stringRes);
        t.setTextColor(0xFFACB8C1);
        t.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 12, 0, 6);
        t.setLayoutParams(lp);
        return t;
    }

    /** 静态壁纸：本地壁纸目录的图片、当前壁纸、无、以及「主题默认」（交还给主题/自动来源）。 */
    private void addStaticItems(Context c, LinearLayout col) {
        File dir = Config.dir(c);
        List<File> images = new ArrayList<>();
        if (dir != null) images.addAll(WallpaperRotation.images(new File(dir, "wallpapers")));
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        List<String> names = new ArrayList<>();
        for (File f : images) names.add(f.getName());
        names.add(a.getString(R.string.wallpaper_none));
        names.add(a.getString(R.string.wallpaper_theme_default));
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean videoOn = choices.getBoolean(Config.WALLPAPER_VIDEO_KEY, false);
        boolean themeDefault = !videoOn && current == null;
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            final boolean isNone = i == names.size() - 2;
            final boolean isDefault = i == names.size() - 1;
            TextView item = pickerItem(c, name);
            boolean selected = isDefault ? themeDefault
                    : isNone ? "none".equals(current)
                    : name.equals(current);
            item.setSelected(selected);
            item.setOnClickListener(v -> {
                choices.edit().remove(Config.WALLPAPER_VIDEO_KEY).apply();
                if (isDefault) choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                else if (isNone) choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                else choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, name).apply();
                Toast.makeText(a, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                changed();
                refresh();
            });
            col.addView(item);
        }
    }

    /** 动态壁纸：在线视频开关（与静态互斥）。 */
    private void addDynamicItems(Context c, LinearLayout col) {
        boolean videoOn = choices.getBoolean(Config.WALLPAPER_VIDEO_KEY, false);
        TextView on = pickerItem(c, a.getString(R.string.wallpaper_dynamic_on));
        on.setSelected(videoOn);
        on.setOnClickListener(v -> {
            choices.edit().putBoolean(Config.WALLPAPER_VIDEO_KEY, true)
                    .remove(Config.WALLPAPER_CHOICE_KEY).apply();
            Toast.makeText(a, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            changed();
            refresh();
        });
        TextView off = pickerItem(c, a.getString(R.string.wallpaper_dynamic_off));
        off.setSelected(!videoOn);
        off.setOnClickListener(v -> {
            choices.edit().remove(Config.WALLPAPER_VIDEO_KEY).apply();
            changed();
            refresh();
        });
        col.addView(on);
        col.addView(off);
    }

    private TextView modeItem(Context c, int labelRes, int minutes) {
        TextView t = pickerItem(c, a.getString(labelRes));
        t.setSelected(wallpaperInterval == minutes);
        t.setOnClickListener(v -> {
            if (wallpaperInterval == minutes) return;
            wallpaperInterval = minutes;
            if (minutes == 0) choices.edit().remove(Config.WALLPAPER_INTERVAL_KEY).apply();
            else choices.edit().putInt(Config.WALLPAPER_INTERVAL_KEY, minutes).apply();
            changed();
            refresh();
        });
        return t;
    }

    private TextView pickerItem(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(16);
        t.setPadding(12, 12, 12, 12);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackgroundResource(R.drawable.picker_item_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 2, 0, 2);
        t.setLayoutParams(lp);
        return t;
    }

    /** 设置改动计数 +1：首页靠它实时感知，不需要重建。 */
    private void changed() {
        host.changed();
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void refresh() {
        int dockCount = Config.load(a).dockCount;
        for (int i = 0; i < appCountChoices.length; i++)
            appCountChoices[i].setSelected(dockCount == i + 3);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean videoOn = choices.getBoolean(Config.WALLPAPER_VIDEO_KEY, false);
        String wallpaperLabel;
        if (videoOn) wallpaperLabel = a.getString(R.string.wallpaper_dynamic_on);
        else if ("none".equals(wallpaperCurrent)) wallpaperLabel = a.getString(R.string.wallpaper_none);
        else if (wallpaperCurrent != null) wallpaperLabel = wallpaperCurrent;
        else wallpaperLabel = a.getString(R.string.wallpaper_theme_default);
        wallpaperChoice.setText(a.getString(R.string.wallpaper_state, wallpaperLabel));
        List<File> favourites = WallpaperFavorites.files(a);
        wallpaperFavoritesChoice.setText(a.getString(R.string.wallpaper_favorites_state, favourites.size()));
    }
}
