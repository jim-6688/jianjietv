package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶栏「影视」按钮打开的内容页：整屏叠加层。顶部一排分类标签（影视菜单）；
 * 下面是「最近观看」与「影视墙」——当前分类 20 张真实海报（豆瓣接口），翻过第 20 张进入下一分类。
 * 信息全部来自 {@link DoubanWall}；点海报用「片名」交给影视 App 搜索并播放，桌面不存片源、不播放。
 * 背景用当前聚焦海报的模糊图；在最顶部分类条上按「上」回到桌面。返回键关闭。
 */
final class FilmHub {

    interface Host {
        Activity activity();

        /** 点「换源」时打开右侧来源抽屉（由 MainActivity 转交 homeDrawer）。 */
        void openSources();

        /** 关闭后回调（例如刷新首页）。 */
        void onHubClosed();
    }

    /** 分类定义：名字 / emoji / 主色（RGB）。 */
    private static final String[][] CATEGORIES = {
            {"电影", "🎬", "#E74C3C"},
            {"电视剧", "📺", "#3498DB"},
            {"综艺", "🎤", "#E67E22"},
            {"动漫", "🌀", "#9B59B6"},
            {"纪录片", "🎥", "#1ABC9C"},
            {"解说", "🗣️", "#F1C40F"},
            {"短剧", "🎭", "#E84393"},
            {"少儿", "🧒", "#2ECC71"},
            {"体育", "⚽", "#16A085"},
            {"音乐", "🎵", "#8E44AD"},
    };
    private static final String SOURCE_TAB = "换源";
    private static final String SOURCE_COLOR = "#7F8C8D";

    private static final int CARDS_PER_CATEGORY = 20;
    private static final int WALL_COLUMNS = 5;

    private final Host host;
    private final ViewGroup root;
    private View overlay;
    private boolean showing;
    private View savedFocus;
    private int currentCategory;
    private RecyclerView tabsView;
    private RecyclerView wallView;
    private TextView wallTitle;
    private ImageView hubBg;
    private PosterAdapter wallAdapter;
    private TabAdapter tabAdapter;

    FilmHub(Host host, ViewGroup root) {
        this.host = host;
        this.root = root;
    }

    boolean isShowing() {
        return showing;
    }

    void show() {
        if (showing) return;
        Activity a = host.activity();
        savedFocus = a.getCurrentFocus();
        overlay = LayoutInflater.from(a).inflate(R.layout.activity_film_hub, root, false);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showing = true;

        tabsView = overlay.findViewById(R.id.tabs);
        RecyclerView recents = overlay.findViewById(R.id.recents);
        wallView = overlay.findViewById(R.id.wall);
        wallTitle = overlay.findViewById(R.id.wallTitle);
        hubBg = overlay.findViewById(R.id.hubBg);
        View recentBlock = overlay.findViewById(R.id.recentBlock);

        List<FilmRecents.Entry> recent = FilmRecents.load(a);
        if (recent.isEmpty()) recentBlock.setVisibility(View.GONE);
        else {
            recentBlock.setVisibility(View.VISIBLE);
            List<PosterItem> recentItems = new ArrayList<>();
            for (FilmRecents.Entry e : recent) recentItems.add(buildRecentItem(e));
            PosterAdapter recentAdapter = new PosterAdapter(recentItems, false);
            recents.setLayoutManager(new LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false));
            recents.setAdapter(recentAdapter);
            recents.setHasFixedSize(true);
        }

        tabAdapter = new TabAdapter();
        tabsView.setLayoutManager(new LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false));
        tabsView.setAdapter(tabAdapter);
        tabsView.setHasFixedSize(true);

        currentCategory = 0;
        if (wallTitle != null) wallTitle.setText("影视墙 · " + CATEGORIES[0][0]);
        wallAdapter = new PosterAdapter(placeholders(), true);
        wallView.setLayoutManager(new GridLayoutManager(a, WALL_COLUMNS));
        wallView.setAdapter(wallAdapter);
        wallView.setHasFixedSize(true);
        fetchCategory(0);

        tabsView.post(() -> {
            if (tabsView.getChildCount() > 0) tabsView.getChildAt(0).requestFocus();
        });

        // 顶部在分类条上「上划」（触摸滑动或按上）回到桌面。
        View topBand = overlay.findViewById(R.id.topBand);
        if (topBand != null) topBand.setOnTouchListener(new SwipeUpCloser());

        View backdrop = overlay.findViewById(R.id.backdrop);
        View sheet = overlay.findViewById(R.id.sheet);
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(200).start();
        sheet.setAlpha(0f);
        sheet.setTranslationY(-dp(120));
        sheet.post(() -> sheet.animate().alpha(1f).translationY(0f)
                .setDuration(280).start());

        setHomeFocusable(false);
    }

    void close() {
        if (!showing) return;
        root.removeView(overlay);
        overlay = null;
        tabsView = null;
        wallView = null;
        wallTitle = null;
        hubBg = null;
        wallAdapter = null;
        tabAdapter = null;
        showing = false;
        setHomeFocusable(true);
        if (savedFocus != null && savedFocus.isAttachedToWindow()) savedFocus.requestFocus();
        host.onHubClosed();
    }

    /** 返回/ESC 关闭；在顶部分类条上按「上」回桌面；其余按键交给焦点视图自行导航。 */
    boolean onKey(KeyEvent event) {
        if (!showing) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) close();
            return true;
        }
        // 焦点已经在最顶部的分类条上，再按「上」就回桌面。
        if (code == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN
                && focusInTabs()) {
            close();
            return true;
        }
        return false;
    }

    private boolean focusInTabs() {
        View f = host.activity().getCurrentFocus();
        if (f == null || tabsView == null) return false;
        View v = f;
        while (v != null) {
            if (v == tabsView) return true;
            android.view.ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    private void setHomeFocusable(boolean enabled) {
        ViewGroup home = (ViewGroup) root.findViewById(R.id.homeContent);
        if (home != null) home.setDescendantFocusability(enabled
                ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    private int dp(int value) {
        return Math.round(value * host.activity().getResources().getDisplayMetrics().density);
    }

    // ---- 数据：先占位，再拉真实海报 ----

    private List<PosterItem> placeholders() {
        String[] cat = CATEGORIES[currentCategory];
        List<PosterItem> items = new ArrayList<>();
        for (int i = 1; i <= CARDS_PER_CATEGORY; i++) {
            items.add(new PosterItem(cat[0] + " " + String.format("%02d", i), cat[0], cat[1], cat[2], 0, null, ""));
        }
        return items;
    }

    /** 拉当前分类的真实数据；回来时若分类已切走就丢弃。 */
    private void fetchCategory(int cat) {
        DoubanWall.fetch(host.activity(), cat, items -> {
            if (!showing || cat != currentCategory || wallAdapter == null) return;
            if (items == null || items.isEmpty()) return;   // 失败就保留占位卡
            String[] c = CATEGORIES[cat];
            List<PosterItem> real = new ArrayList<>();
            for (DoubanWall.Item it : items) {
                real.add(new PosterItem(it.title, c[0], c[1], c[2], 0, it.poster, it.meta));
            }
            wallAdapter.setItems(real);
            if (!real.isEmpty()) setBackground(real.get(0).poster);
        });
    }

    private PosterItem buildRecentItem(FilmRecents.Entry e) {
        // 现在记的是片名；早期记录里可能还残留分类名，按分类上色，其余按电影色显示片名。
        for (String[] c : CATEGORIES)
            if (c[0].equals(e.name)) return new PosterItem(c[0], c[0], c[1], c[2], e.ts, null, "");
        return new PosterItem(e.name, e.name, CATEGORIES[0][1], CATEGORIES[0][2], e.ts, null, "");
    }

    private void setBackground(String poster) {
        if (hubBg != null && poster != null) PosterLoader.loadBlurred(hubBg, poster);
    }

    private void selectCategory(int index) {
        int n = CATEGORIES.length;
        currentCategory = ((index % n) + n) % n;
        if (wallTitle != null) wallTitle.setText("影视墙 · " + CATEGORIES[currentCategory][0]);
        if (wallAdapter != null) wallAdapter.setItems(placeholders());
        if (tabAdapter != null) tabAdapter.setSelected(currentCategory);
        fetchCategory(currentCategory);
        if (wallView != null) {
            wallView.scrollToPosition(0);
            wallView.post(() -> {
                RecyclerView.ViewHolder vh = wallView.findViewHolderForAdapterPosition(0);
                if (vh != null) vh.itemView.requestFocus();
            });
        }
    }

    private void advanceCategory() {
        selectCategory(currentCategory + 1);
    }

    private static final class PosterItem {
        final String name;
        final String category;
        final String emoji;
        final String color;
        final long ts;
        final String poster;
        final String meta;

        PosterItem(String name, String category, String emoji, String color, long ts, String poster, String meta) {
            this.name = name;
            this.category = category;
            this.emoji = emoji;
            this.color = color;
            this.ts = ts;
            this.poster = poster;
            this.meta = meta;
        }

        boolean recent() {
            return ts > 0;
        }
    }

    // ---- 分类标签（影视菜单） ----

    private final class TabAdapter extends RecyclerView.Adapter<TabAdapter.VH> {
        private final List<String> names = new ArrayList<>();
        private int selectedPos = 0;

        TabAdapter() {
            for (String[] c : CATEGORIES) names.add(c[0]);
            names.add(SOURCE_TAB);
        }

        void setSelected(int pos) {
            if (selectedPos == pos) return;
            int old = selectedPos;
            selectedPos = pos;
            if (old >= 0 && old < names.size()) notifyItemChanged(old);
            if (pos >= 0 && pos < names.size()) notifyItemChanged(pos);
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            View v = LayoutInflater.from(host.activity()).inflate(R.layout.item_film_tab, p, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            String name = names.get(pos);
            boolean isSource = name.equals(SOURCE_TAB);
            h.tab.setText(name);
            int color = isSource ? parse(SOURCE_COLOR) : parse(CATEGORIES[pos][2]);
            h.tab.setTextColor(pos == selectedPos ? 0xFFFFD166 : 0xFFFFFFFF);
            h.tab.setOnFocusChangeListener((v, f) -> {
                v.setScaleX(f ? 1.12f : 1f);
                v.setScaleY(f ? 1.12f : 1f);
                v.setElevation(f ? dp(10) : 0);
                if (f) v.setBackgroundColor(lighten(color, 0.22f));
                else v.setBackgroundResource(R.drawable.pill_bg);
            });
            h.tab.setOnClickListener(v -> {
                if (isSource) pickSource();
                else selectCategory(pos);
            });
        }

        @Override
        public int getItemCount() {
            return names.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView tab;

            VH(View v) {
                super(v);
                tab = v.findViewById(R.id.tab);
            }
        }
    }

    // ---- 海报卡（影视墙网格 / 最近观看横排） ----

    private final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.VH> {
        private final List<PosterItem> items;
        private final boolean grid;

        PosterAdapter(List<PosterItem> items, boolean grid) {
            this.items = new ArrayList<>(items);
            this.grid = grid;
        }

        void setItems(List<PosterItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            View v = LayoutInflater.from(host.activity()).inflate(R.layout.item_film_poster, p, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            PosterItem it = items.get(pos);
            int color = parse(it.color);
            h.accent.setBackgroundColor(color);
            h.emoji.setText(it.emoji);
            h.name.setText(it.name);
            if (it.meta != null && !it.meta.isEmpty()) {
                h.meta.setVisibility(View.VISIBLE);
                h.meta.setText(it.meta);
            } else if (it.recent()) {
                h.meta.setVisibility(View.VISIBLE);
                h.meta.setText(FilmRecents.relTime(it.ts));
            } else {
                h.meta.setVisibility(View.GONE);
            }
            if (it.poster != null && !it.poster.isEmpty()) {
                PosterLoader.load(h.posterImage, it.poster);
            } else {
                h.posterImage.setTag(null);
                h.posterImage.setImageDrawable(null);
                h.posterImage.setAlpha(0f);
            }
            if (grid) {
                ViewGroup.LayoutParams lp = h.poster.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = dp(170);
                h.poster.setLayoutParams(lp);
            } else {
                ViewGroup.LayoutParams lp = h.poster.getLayoutParams();
                lp.width = dp(300);
                lp.height = dp(170);
                h.poster.setLayoutParams(lp);
            }
            h.poster.setTag(it.name);
            h.poster.setOnFocusChangeListener((v, f) -> {
                if (f) {
                    v.setBackground(buildGradient(color));
                    v.setForeground(host.activity().getDrawable(R.drawable.poster_focus));
                    v.setScaleX(1.12f);
                    v.setScaleY(1.12f);
                    v.setElevation(dp(14));
                    if (grid && it.poster != null) setBackground(it.poster);
                } else {
                    v.setBackgroundResource(R.drawable.poster_card);
                    v.setForeground(null);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setElevation(0);
                }
            });
            // 点具体片子就把「片名」交给影视 App 搜索（不是它所属的分类名）。
            h.poster.setOnClickListener(v -> pick(it.name));
            if (grid) {
                final int lastRowStart = ((items.size() - 1) / WALL_COLUMNS) * WALL_COLUMNS;
                h.poster.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    boolean down = keyCode == KeyEvent.KEYCODE_DPAD_DOWN && pos >= lastRowStart;
                    boolean right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos == items.size() - 1;
                    if (down || right) {
                        advanceCategory();
                        return true;
                    }
                    return false;
                });
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final View poster;
            final View accent;
            final TextView emoji;
            final TextView name;
            final TextView meta;
            final ImageView posterImage;

            VH(View v) {
                super(v);
                poster = v.findViewById(R.id.poster);
                accent = v.findViewById(R.id.accent);
                emoji = v.findViewById(R.id.emoji);
                name = v.findViewById(R.id.name);
                meta = v.findViewById(R.id.meta);
                posterImage = v.findViewById(R.id.posterImage);
            }
        }
    }

    /** 点海报：记下这部片，再用片名让影视 App 搜索并播放；桌面本身不播放。 */
    private void pick(String title) {
        Activity a = host.activity();
        FilmRecents.record(a, title);
        FilmCategoriesActivity.openFilm(a, title);
        close();
    }

    private void pickSource() {
        close();
        host.openSources();
    }

    /** 顶栏上向上滑动回桌面（触摸遥控/鼠标）。 */
    private final class SwipeUpCloser implements View.OnTouchListener {
        private float downY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    return false;
                case MotionEvent.ACTION_UP:
                    if (downY - e.getY() > dp(60)) {
                        close();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }
    }

    private static int parse(String hex) {
        return (int) Long.parseLong(hex.replace("#", ""), 16) | 0xFF000000;
    }

    private static int lighten(int color, float amount) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r = Math.min(255, (int) (r + (255 - r) * amount));
        g = Math.min(255, (int) (g + (255 - g) * amount));
        b = Math.min(255, (int) (b + (255 - b) * amount));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private GradientDrawable buildGradient(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int dark = 0xFF000000 | ((r * 3 / 5) << 16) | ((g * 3 / 5) << 8) | (b * 3 / 5);
        int light = 0xFF000000 | (Math.min(255, r + 40) << 16) | (Math.min(255, g + 40) << 8) | Math.min(255, b + 40);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{light, color, dark});
        d.setCornerRadius(14 * host.activity().getResources().getDisplayMetrics().density);
        return d;
    }
}
