package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
 *
 * 设置项按需求收敛为：桌面设置（应用数量）、壁纸设置（静态/动态二选一 + 立即生效/24 小时轮换 + 收藏列表）、
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
    /** 背景透明度的四档，写入 dock_opacity（30-100）：同时作用于程序坞、顶栏、天气卡与设置抽屉。 */
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] opacityChoices = new TextView[DOCK_OPACITIES.length];
    /** 当前生效的透明度（可能来自主题），点击同一档时不重复写预置。 */
    private int dockOpacity;
    private LinearLayout wallpaperInline;
    private TextView wallpaperFavoritesText;
    private LinearLayout wallpaperFavoritesGrid;
    /** 缩略图按显示顺序排好；重建后把焦点还给原来聚焦的那一格。 */
    private final List<View> favouriteCells = new ArrayList<>();
    /** 收藏缩略图每排两张（面板可用宽约 376dp，一张 16:9 的预览才看得清画面）。 */
    private static final int COLUMNS = 2;
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

        // 背景透明度：四档写 dock_opacity。面板自身立刻跟着变，首页在抽屉关闭时统一刷新。
        dockOpacity = config.dockOpacity;
        int[] opacityIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < opacityIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            opacityChoices[i] = view.findViewById(opacityIds[i]);
            opacityChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                UiAlpha.applyPanel(view, value);
                changed();
                refresh();
            });
        }

        wallpaperInterval = choices.getInt(Config.WALLPAPER_INTERVAL_KEY, 0);
        wallpaperInline = view.findViewById(R.id.wallpaperInline);
        wallpaperFavoritesText = view.findViewById(R.id.wallpaperFavoritesText);
        wallpaperFavoritesGrid = view.findViewById(R.id.wallpaperFavoritesGrid);
        // 影视源：在同一个右侧抽屉里换页，不另开窗口。
        view.findViewById(R.id.filmSourcesChoice).setOnClickListener(v -> host.openSources());
        refresh();
    }

    /** 抽屉滑入后再把焦点交给第一个可选项。 */
    void focusFirst(Config config) {
        if (appCountChoices[0] != null) appCountChoices[0].requestFocus();
    }

    /**
     * 壁纸设置直接铺在面板里（不再弹二级页面）：顶部「静态壁纸 / 动态壁纸」两张大卡片左右二选一
     * （选中态金色描边），下方只展开当前类型的内容，再下面两档「立即生效 / 24 小时轮换」控制轮播。
     * 每次调用都整块重建；重建前后按文字保留焦点，避免遥控器焦点丢失。
     */
    private void renderWallpaperInline() {
        CharSequence focusedText = focusedTextIn(wallpaperInline);
        wallpaperInline.removeAllViews();
        Context c = a;
        boolean videoOn = choices.getBoolean(Config.WALLPAPER_VIDEO_KEY, false);

        // 顶部：静态 / 动态 二选一（左右两张卡片，选中态明显）
        LinearLayout typeRow = new LinearLayout(c);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams typeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        typeLp.bottomMargin = 12;
        typeRow.setLayoutParams(typeLp);
        TextView staticCard = typeCard(c, R.string.wallpaper_static, !videoOn);
        TextView dynamicCard = typeCard(c, R.string.wallpaper_dynamic, videoOn);
        staticCard.setOnClickListener(v -> {
            if (videoOn) {
                // 切回静态：清掉收藏里固定播放的那条视频，之后再切动态会重新随机选片。
                choices.edit().remove(Config.WALLPAPER_VIDEO_KEY)
                        .remove(WallpaperFavorites.VIDEO_SELECTED_KEY).apply();
                changed();
                refresh();
                refocus(wallpaperInline, staticCard.getText());
            } else {
                staticCard.requestFocus();
            }
        });
        dynamicCard.setOnClickListener(v -> {
            if (!videoOn) {
                choices.edit().putBoolean(Config.WALLPAPER_VIDEO_KEY, true)
                        .remove(Config.WALLPAPER_CHOICE_KEY)
                        .remove(WallpaperFavorites.VIDEO_SELECTED_KEY).apply();
                changed();
                refresh();
                refocus(wallpaperInline, dynamicCard.getText());
            } else {
                dynamicCard.requestFocus();
            }
        });
        typeRow.addView(staticCard);
        typeRow.addView(dynamicCard);
        wallpaperInline.addView(typeRow);

        // 子内容：只展示当前选中的类型
        if (videoOn) {
            TextView note = new TextView(c);
            note.setText(R.string.wallpaper_dynamic_active);
            note.setTextColor(0xFFC9A96E);
            note.setTextSize(16);
            note.setPadding(12, 12, 12, 12);
            wallpaperInline.addView(note);
        } else {
            addStaticItems(c, wallpaperInline);
        }

        // 两档：立即生效 / 24 小时轮换
        LinearLayout modes = new LinearLayout(c);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams modesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modesLp.topMargin = 16;
        modes.setLayoutParams(modesLp);
        modes.addView(modeItem(c, R.string.wallpaper_immediate, 0));
        modes.addView(modeItem(c, R.string.wallpaper_24h, 1440));
        wallpaperInline.addView(modes);

        if (focusedText != null) refocus(wallpaperInline, focusedText);
    }

    /** 记录容器内当前获得焦点的可点击项的文字，供整块重建后按文字找回同一项。 */
    private static CharSequence focusedTextIn(ViewGroup group) {
        View focused = group.findFocus();
        if (focused instanceof TextView && focused.isClickable()) return ((TextView) focused).getText();
        return null;
    }

    /** 重建后按文字把焦点还给原来的项；找不到（例如切类型后该项已消失）就不强行抢焦点。 */
    private static void refocus(ViewGroup group, CharSequence text) {
        if (text == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && text.equals(((TextView) child).getText())) {
                child.requestFocus();
                return;
            }
            if (child instanceof ViewGroup) {
                ViewGroup row = (ViewGroup) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View item = row.getChildAt(j);
                    if (item instanceof TextView && text.equals(((TextView) item).getText())) {
                        item.requestFocus();
                        return;
                    }
                }
            }
        }
    }

    /** 二选一的大卡片：左右等分，选中态有金色描边，一眼能看出当前选的是静态还是动态。 */
    private TextView typeCard(Context c, int labelRes, boolean selected) {
        TextView t = pickerItem(c, a.getString(labelRes));
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setPadding(16, 28, 16, 28);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(4, 0, 4, 0);
        t.setLayoutParams(lp);
        t.setSelected(selected);
        return t;
    }

    /** 静态壁纸：本地壁纸目录的图片、当前壁纸（「主题默认」「无」选框均已去掉）。 */
    private void addStaticItems(Context c, LinearLayout col) {
        File dir = Config.dir(c);
        List<File> images = new ArrayList<>();
        if (dir != null) images.addAll(WallpaperRotation.images(new File(dir, "wallpapers")));
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        boolean singleIsActive = false;
        if (single != null && single.isFile()) {
            images.add(0, single);
            singleIsActive = true;
        }
        List<String> names = new ArrayList<>();
        for (File f : images) names.add(f.getName());
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean videoOn = choices.getBoolean(Config.WALLPAPER_VIDEO_KEY, false);
        // 旧版本可能残留 "none"（该选项已去掉），按「未指定」处理，避免所有图片都不高亮。
        boolean usingDefault = !videoOn && (current == null || "none".equals(current));
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            final boolean isSingle = singleIsActive && i == 0;
            TextView item = pickerItem(c, name);
            boolean selected = usingDefault ? isSingle : name.equals(current);
            item.setSelected(selected);
            item.setOnClickListener(v -> {
                choices.edit().remove(Config.WALLPAPER_VIDEO_KEY)
                        .remove(WallpaperFavorites.VIDEO_SELECTED_KEY)
                        .putString(Config.WALLPAPER_CHOICE_KEY, name).apply();
                Toast.makeText(a, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                changed();
                refresh();
            });
            col.addView(item);
        }
    }

    private TextView modeItem(Context c, int labelRes, int minutes) {
        TextView t = pickerItem(c, a.getString(labelRes));
        // 两档平分一行宽度（pickerItem 默认是 MATCH_PARENT，横排里会把第二档挤出屏幕）。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(4, 2, 4, 2);
        t.setLayoutParams(lp);
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
        Config current = Config.load(a);
        dockOpacity = current.dockOpacity;
        for (int i = 0; i < opacityChoices.length; i++)
            opacityChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        int dockCount = current.dockCount;
        for (int i = 0; i < appCountChoices.length; i++)
            appCountChoices[i].setSelected(dockCount == i + 3);
        wallpaperInterval = choices.getInt(Config.WALLPAPER_INTERVAL_KEY, 0);
        renderWallpaperInline();
        List<File> favourites = WallpaperFavorites.files(a);
        List<String> clips = WallpaperFavorites.videos(a);
        // 数量把静态图片和动态视频一起算，动态壁纸也能收藏后这里要立刻看得出来。
        wallpaperFavoritesText.setText(
                a.getString(R.string.wallpaper_favorites_state, favourites.size() + clips.size()));
        renderFavourites(favourites, clips);
    }

    /** 收藏直接平铺成缩略图：静态图用画面本身，动态片用星标加片名，一眼看出是哪一张。 */
    private void renderFavourites(List<File> stills, List<String> clips) {
        int focused = favouriteCells.indexOf(wallpaperFavoritesGrid.findFocus());
        favouriteCells.clear();
        wallpaperFavoritesGrid.removeAllViews();

        if (stills.isEmpty() && clips.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText(R.string.wallpaper_favorites_empty);
            empty.setTextColor(0xFFACB8C1);
            empty.setTextSize(13);
            wallpaperFavoritesGrid.addView(empty);
            return;
        }

        String chosenStill = WallpaperFavorites.selected(a);
        String chosenClip = WallpaperFavorites.selectedVideo(a);
        LinearLayout row = null;
        int inRow = 0;
        for (File file : stills) {
            if (inRow == 0) {
                row = gridRow();
                wallpaperFavoritesGrid.addView(row);
            }
            row.addView(stillCell(file, file.getName().equals(chosenStill)));
            inRow = (inRow + 1) % COLUMNS;
        }
        for (String url : clips) {
            if (inRow == 0) {
                row = gridRow();
                wallpaperFavoritesGrid.addView(row);
            }
            row.addView(clipCell(url, url.equals(chosenClip)));
            inRow = (inRow + 1) % COLUMNS;
        }
        // 最后一排没排满时补空位，否则剩下几张会被平分拉宽。
        if (row != null && inRow != 0) {
            for (int i = inRow; i < COLUMNS; i++) row.addView(new View(a), cellParams());
        }

        TextView hint = new TextView(a);
        hint.setText(R.string.wallpaper_favorites_grid_hint);
        hint.setTextColor(0xFF8FA0AD);
        hint.setTextSize(12);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(10);
        hint.setLayoutParams(hintLp);
        wallpaperFavoritesGrid.addView(hint);

        if (focused >= 0 && focused < favouriteCells.size())
            favouriteCells.get(focused).requestFocus();
    }

    private LinearLayout gridRow() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = wallpaperFavoritesGrid.getChildCount() == 0 ? 0 : dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** 16:9 的格子，两张平分一排。 */
    private LinearLayout.LayoutParams cellParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(100), 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    /** 一格缩略图：圆角卡片，正在用的那张换成金色描边。 */
    private FrameLayout cell(boolean chosen) {
        FrameLayout cell = new FrameLayout(a);
        cell.setLayoutParams(cellParams());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        bg.setColor(0xFF14161B);
        bg.setStroke(dp(chosen ? 2 : 1), chosen ? 0xFFC9A96E : 0x33FFFFFF);
        cell.setBackground(bg);
        cell.setClipToOutline(true);
        cell.setFocusable(true);
        cell.setClickable(true);
        favouriteCells.add(cell);
        return cell;
    }

    private FrameLayout stillCell(File file, boolean chosen) {
        FrameLayout cell = cell(chosen);
        ImageView picture = new ImageView(a);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bmp = decodeSampled(file, dp(200), dp(112));
        if (bmp != null) picture.setImageBitmap(bmp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        cell.addView(picture, lp);
        cell.setOnClickListener(v -> {
            WallpaperFavorites.select(a, file.getName());
            Toast.makeText(a, R.string.wallpaper_favorite_applied, Toast.LENGTH_SHORT).show();
            changed();
            refresh();
        });
        cell.setOnLongClickListener(v -> {
            WallpaperFavorites.remove(a, file);
            Toast.makeText(a, R.string.wallpaper_favorite_deleted, Toast.LENGTH_SHORT).show();
            changed();
            refresh();
            return true;
        });
        return cell;
    }

    /** 动态片没有本地画面，用星标加片名代替，仍然一眼能分出是哪一段。 */
    private FrameLayout clipCell(String url, boolean chosen) {
        FrameLayout cell = cell(chosen);
        ImageView star = new ImageView(a);
        star.setImageResource(R.drawable.ic_star_filled);
        cell.addView(star, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));

        TextView name = new TextView(a);
        name.setText(WallpaperFavorites.videoLabel(url));
        name.setTextColor(0xFFC9A96E);
        name.setTextSize(11);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(6), 0, dp(6), dp(5));
        cell.addView(name, lp);

        cell.setOnClickListener(v -> {
            WallpaperFavorites.selectVideo(a, url);
            Toast.makeText(a, R.string.wallpaper_favorite_applied, Toast.LENGTH_SHORT).show();
            changed();
            refresh();
        });
        cell.setOnLongClickListener(v -> {
            WallpaperFavorites.removeVideo(a, url);
            Toast.makeText(a, R.string.wallpaper_favorite_deleted, Toast.LENGTH_SHORT).show();
            changed();
            refresh();
            return true;
        });
        return cell;
    }

    private int dp(int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }

    /** 把壁纸文件按目标尺寸采样解码，避免大图直接进内存。 */
    private static Bitmap decodeSampled(File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sw = o.outWidth, sh = o.outHeight;
            int scale = 1;
            while (sw / scale / 2 >= w && sh / scale / 2 >= h) scale *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = scale;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }
}
