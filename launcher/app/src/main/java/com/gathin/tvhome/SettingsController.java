package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 桌面设置的全部交互逻辑，和「放在哪个窗口里」无关：首页右侧抽屉（HomeDrawer）和兼容用的
 * LauncherSettingsActivity 都用它接同一个布局，避免两套代码走偏。
 */
final class SettingsController {

    /** 谁承载这个面板：抽屉负责滑出/收起，Activity 负责 finish/setResult。 */
    interface Host {
        Activity activity();

        void close();

        void changed();

        void reorder();

        void openSources();
    }

    private final Host host;
    private final Activity a;
    private final View view;
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    /** 「自动」加 3/4/5/6：RTL 布局倒序时整行一起倒。 */
    private final TextView[] allColumnChoices = new TextView[5];
    private TextView columnsAutoChoice;
    private TextView showIpChoice;
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    /** 壁纸轮换间隔，单位分钟；0 表示跟随主题（也就是沿用主题 config.json 里的 wallpaper_rotation_minutes）。 */
    private static final int[] WALLPAPER_INTERVALS = {60, 1440};
    private final TextView[] intervalChoices = new TextView[WALLPAPER_INTERVALS.length + 1];
    private TextView wallpaperChoice;
    private TextView wallpaper360Choice;
    private TextView wallpaperFavoriteChoice;
    private TextView wallpaperFavoritesChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;
    /** 桌面设置里选的轮换间隔（分钟），0 表示跟随主题。 */
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
        View reorderChoice = view.findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            host.reorder();
            host.close();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        // 顶栏网络信息：默认只显示宽带运营商，打开后才显示内网 / 外网 IP 两行。
        showIpChoice = view.findViewById(R.id.showIpChoice);
        showIpChoice.setOnClickListener(v -> {
            boolean on = !choices.getBoolean(NetInfo.SHOW_IP_KEY, false);
            choices.edit().putBoolean(NetInfo.SHOW_IP_KEY, on).apply();
            changed();
            refresh();
        });
        // 「自动」清掉手动列数，让首页按应用数自己选 3..6 列。
        columnsAutoChoice = view.findViewById(R.id.columnsAuto);
        columnsAutoChoice.setOnClickListener(v -> {
            if (!choices.contains("columns")) return;
            choices.edit().remove("columns").apply();
            columns = AutoColumns.pick(AutoColumns.countApps(a));
            changed();
            refresh();
        });
        allColumnChoices[0] = columnsAutoChoice;
        // 3 columns is the big-card default; 4/5/6 shrink the cards back towards the old strips.
        int[] ids = {R.id.columns3, R.id.columns4, R.id.columns5, R.id.columns6};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 3;
            columnChoices[i] = view.findViewById(ids[i]);
            allColumnChoices[i + 1] = columnChoices[i];
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value && choices.contains("columns")) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = view.findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = view.findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = view.findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        if (a.getResources().getBoolean(R.bool.rtl_menu_bar))
            view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (a.getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(allColumnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
        }
        // 轮换间隔：1 小时 / 1 天 / 跟随主题。改了立刻生效（count 变了，首页重建时按新间隔算下一张）。
        wallpaperInterval = choices.getInt(Config.WALLPAPER_INTERVAL_KEY, 0);
        int[] intervalIds = {R.id.wallpaperInterval1h, R.id.wallpaperInterval1d, R.id.wallpaperIntervalTheme};
        for (int i = 0; i < intervalIds.length; i++) {
            final int value = i < WALLPAPER_INTERVALS.length ? WALLPAPER_INTERVALS[i] : 0;
            intervalChoices[i] = view.findViewById(intervalIds[i]);
            intervalChoices[i].setOnClickListener(v -> {
                if (wallpaperInterval == value) return;
                wallpaperInterval = value;
                if (value == 0) choices.edit().remove(Config.WALLPAPER_INTERVAL_KEY).apply();
                else choices.edit().putInt(Config.WALLPAPER_INTERVAL_KEY, value).apply();
                // 360 壁纸的「自动获取」跟着这个间隔走：选了 1 小时就马上按新节奏补一批。
                if (value > 0) {
                    Wallpaper360.fetchIfNeeded(a);
                    // 一张图都没得轮换时，设了间隔也看不出变化：说清楚图从哪来。
                    if (!rotatable()) {
                        Toast.makeText(a, R.string.wallpaper_interval_needs_pictures, Toast.LENGTH_LONG).show();
                    }
                }
                changed();
                refresh();
            });
        }
        // 这一排在 rtl_app_grid 里也要倒过来；放在绑定之后，因为倒序要用到刚拿到的视图。
        if (a.getResources().getBoolean(R.bool.rtl_app_grid)) reverseRow(intervalChoices);
        wallpaperChoice = view.findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        wallpaper360Choice = view.findViewById(R.id.wallpaper360Choice);
        wallpaper360Choice.setOnClickListener(v -> {
            boolean on = !Wallpaper360.enabled(a);
            choices.edit().putBoolean(Wallpaper360.PREF_KEY, on).apply();
            if (on) Wallpaper360.fetchIfNeeded(a);
            changed();
            refresh();
        });
        // 影视源：在同一个右侧抽屉里换页，不另开窗口。
        view.findViewById(R.id.filmSourcesChoice).setOnClickListener(v -> host.openSources());
        // 手动刷新壁纸：立刻换一张，360 开着的话再抓一批新图。
        view.findViewById(R.id.wallpaperRefreshChoice).setOnClickListener(v -> refreshWallpaper());
        wallpaperFavoriteChoice = view.findViewById(R.id.wallpaperFavoriteChoice);
        wallpaperFavoriteChoice.setOnClickListener(v -> toggleFavourite());
        wallpaperFavoritesChoice = view.findViewById(R.id.wallpaperFavoritesChoice);
        wallpaperFavoritesChoice.setOnClickListener(v -> showFavourites());
        animationsChoice = view.findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = view.findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(a)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        openOnStartChoice = view.findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = view.findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.WALLPAPER_INTERVAL_KEY).remove(NetInfo.SHOW_IP_KEY)
                    .remove(WallpaperFavorites.ONLY_KEY).remove(WallpaperFavorites.SELECTED_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY).commit();
            Config current = Config.load(a);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            wallpaperInterval = 0;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        view.findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(a, HomeSettingsActivity.class)));
        view.findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(a, Settings.ACTION_SETTINGS)));
        refresh();
    }

    /** 抽屉滑入后再把焦点交给第一个可选项。 */
    void focusFirst(Config config) {
        View first = config.launcherSettingsButton ? view.findViewById(R.id.reorderChoice)
                : !choices.contains("columns") ? columnsAutoChoice
                : columnChoices[Math.max(0, Math.min(columns - 3, 3))];
        if (first != null) first.requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    /** Save (or unsave) exactly the picture Home is showing right now. */
    private void toggleFavourite() {
        WallpaperFavorites.toggle(a);
        changed();
        refresh();
    }

    /** The same favourites list the Home star button opens, kept in WallpaperFavorites. */
    private void showFavourites() {
        Runnable changedAndRefresh = () -> { changed(); refresh(); };
        WallpaperFavorites.showPicker(a, changedAndRefresh, changedAndRefresh);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(a);
        List<File> images = new ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = a.getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size() : indexOf(images, current);
        new AlertDialog.Builder(a)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(a, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showOpenOnStartPicker() {
        PackageManager pm = a.getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(a.getPackageName())) continue;
            boolean seen = false;
            for (String[] app : apps) seen |= app[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = a.getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(a);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return a.getString(R.string.off);
        try {
            PackageManager pm = a.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /** 设置改动计数 +1：首页重建时靠它感知，不管是抽屉还是独立窗口里改的。 */
    private void changed() {
        host.changed();
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    /** 有没有东西可以轮换：360 壁纸开着，或主题的 wallpapers/ 目录里至少两张图。 */
    private boolean rotatable() {
        if (Wallpaper360.enabled(a)) return true;
        File dir = Config.dir(a);
        return dir != null && WallpaperRotation.images(new File(dir, "wallpapers")).size() >= 2;
    }

    /** 手动刷新壁纸：清掉轮播记录里的「当前图」，360 开着的话再抓一批新图，然后收起面板。 */    private void refreshWallpaper() {
        a.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                .edit().remove("image").apply();
        if (Wallpaper360.enabled(a)) Wallpaper360.refreshNow(a);
        Toast.makeText(a, R.string.wallpaper_refreshing, Toast.LENGTH_SHORT).show();
        changed();
        host.close();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        boolean autoColumns = !choices.contains("columns");
        columnsAutoChoice.setSelected(autoColumns);
        for (int i = 0; i < columnChoices.length; i++)
            columnChoices[i].setSelected(!autoColumns && columns == i + 3);
        showIpChoice.setText(a.getString(R.string.show_ip_state,
                a.getString(choices.getBoolean(NetInfo.SHOW_IP_KEY, false) ? R.string.on : R.string.off)));
        for (int i = 0; i < rowChoices.length; i++) rowChoices[i].setSelected(rowsBelowDock == i);
        for (int i = 0; i < dockChoices.length; i++) dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        for (int i = 0; i < clockChoices.length; i++) clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        for (int i = 0; i < intervalChoices.length; i++) {
            int value = i < WALLPAPER_INTERVALS.length ? WALLPAPER_INTERVALS[i] : 0;
            if (intervalChoices[i] != null) intervalChoices[i].setSelected(wallpaperInterval == value);
        }
        animationsChoice.setText(a.getString(R.string.animations_state,
                a.getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(a.getString(R.string.advanced_effects_state,
                a.getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(a.getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? a.getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : a.getString(R.string.wallpaper_theme_default)));
        wallpaper360Choice.setText(a.getString(R.string.wallpaper_360_state,
                a.getString(Wallpaper360.enabled(a) ? R.string.on : R.string.off)));
        List<File> favourites = WallpaperFavorites.files(a);
        wallpaperFavoriteChoice.setText(a.getString(
                WallpaperFavorites.contains(favourites, WallpaperFavorites.current(a))
                        ? R.string.wallpaper_favorite_remove : R.string.wallpaper_favorite_add));
        wallpaperFavoritesChoice.setText(a.getString(R.string.wallpaper_favorites_state, favourites.size()));
        wallpaperFavoritesChoice.setSelected(WallpaperFavorites.only(a)
                || WallpaperFavorites.selected(a) != null);
        openOnStartChoice.setText(a.getString(R.string.open_on_start_state, openOnStartLabel()));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.WALLPAPER_INTERVAL_KEY)
                || choices.contains(NetInfo.SHOW_IP_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(OpenOnStart.KEY)
                || choices.contains(WallpaperFavorites.ONLY_KEY) || choices.contains(WallpaperFavorites.SELECTED_KEY);
    }

    private void open(Intent intent) {
        try {
            a.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(a, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
