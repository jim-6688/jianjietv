package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    private LinearLayout wallpaperInline;
    private View wallpaperFavoritesChoice;
    private TextView wallpaperFavoritesText;
    private ImageView wallpaperFavoritesThumb;
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
        wallpaperInline = view.findViewById(R.id.wallpaperInline);
        wallpaperFavoritesChoice = view.findViewById(R.id.wallpaperFavoritesChoice);
        wallpaperFavoritesText = view.findViewById(R.id.wallpaperFavoritesText);
        wallpaperFavoritesThumb = view.findViewById(R.id.wallpaperFavoritesThumb);
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
        wallpaperInline.addView(header(c, R.string.wallpaper_type));
        LinearLayout typeRow = new LinearLayout(c);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        typeRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView staticCard = typeCard(c, R.string.wallpaper_static, !videoOn);
        TextView dynamicCard = typeCard(c, R.string.wallpaper_dynamic, videoOn);
        staticCard.setOnClickListener(v -> {
            if (videoOn) {
                choices.edit().remove(Config.WALLPAPER_VIDEO_KEY).apply();
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
                        .remove(Config.WALLPAPER_CHOICE_KEY).apply();
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
            wallpaperInline.addView(header(c, R.string.wallpaper_choose_image));
            addStaticItems(c, wallpaperInline);
        }

        // 两档：立即生效 / 24 小时轮换
        wallpaperInline.addView(header(c, R.string.wallpaper_mode));
        LinearLayout modes = new LinearLayout(c);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

    /** 静态壁纸：本地壁纸目录的图片、当前壁纸、无（不再放「主题默认」选框）。 */
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
        names.add(a.getString(R.string.wallpaper_none));
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean videoOn = choices.getBoolean(Config.WALLPAPER_VIDEO_KEY, false);
        boolean usingDefault = !videoOn && current == null;
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            final boolean isNone = i == names.size() - 1;
            final boolean isSingle = singleIsActive && i == 0;
            TextView item = pickerItem(c, name);
            boolean selected = isNone ? "none".equals(current)
                    : usingDefault ? isSingle
                    : name.equals(current);
            item.setSelected(selected);
            item.setOnClickListener(v -> {
                choices.edit().remove(Config.WALLPAPER_VIDEO_KEY).apply();
                if (isNone) choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                else choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, name).apply();
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
        int dockCount = Config.load(a).dockCount;
        for (int i = 0; i < appCountChoices.length; i++)
            appCountChoices[i].setSelected(dockCount == i + 3);
        wallpaperInterval = choices.getInt(Config.WALLPAPER_INTERVAL_KEY, 0);
        renderWallpaperInline();
        List<File> favourites = WallpaperFavorites.files(a);
        wallpaperFavoritesText.setText(a.getString(R.string.wallpaper_favorites_state, favourites.size()));
        loadFavouriteThumb(favourites);
    }

    /** 胶囊预览：取收藏列表里的第一张作为缩略图；无收藏时隐藏胶囊。 */
    private void loadFavouriteThumb(List<File> favourites) {
        if (favourites == null || favourites.isEmpty()) {
            wallpaperFavoritesThumb.setVisibility(View.GONE);
            return;
        }
        Bitmap bmp = decodeSampled(favourites.get(0), 152, 76);
        if (bmp == null) {
            wallpaperFavoritesThumb.setVisibility(View.GONE);
            return;
        }
        wallpaperFavoritesThumb.setImageBitmap(bmp);
        wallpaperFavoritesThumb.setVisibility(View.VISIBLE);
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
