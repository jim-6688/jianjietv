package com.gathin.tvhome;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * 首页右侧设置抽屉：把设置 / 影视源面板作为 View 直接叠在 MainActivity 的 root 上，
 * 不启动任何 Activity、不新开窗口。两层页面用返回键逐层收起。
 */
final class HomeDrawer implements SettingsController.Host, FilmSourcesPanel.Host {

    interface Callback {
        /** 抽屉还开着时的一处改动：立刻应用到首页，不必等收起抽屉。 */
        void onSettingsChanged();

        void onDrawerClosed(boolean changed, boolean reorder);
    }

    private static final class Page {
        final View overlay;
        final Runnable focus;

        Page(View overlay, Runnable focus) {
            this.overlay = overlay;
            this.focus = focus;
        }
    }

    private final MainActivity host;
    private final FrameLayout root;
    private final Callback callback;
    private final ArrayList<Page> pages = new ArrayList<>();
    private boolean changed;
    private boolean reorder;
    private View savedFocus;

    HomeDrawer(MainActivity host, FrameLayout root, Callback callback) {
        this.host = host;
        this.root = root;
        this.callback = callback;
    }

    @Override
    public Activity activity() {
        return host;
    }

    @Override
    public void changed() {
        changed = true;
        // 每改一项就立刻落到首页：壁纸、程序坞数量、透明度在抽屉仍开着时就变了，
        // 用户不必先返回退出设置才看到效果。
        callback.onSettingsChanged();
    }

    @Override
    public void reorder() {
        reorder = true;
        changed = true;
        // 重排要收起抽屉并进入重排模式，不能在这里就应用。
    }

    @Override
    public void openSources() {
        showSources();
    }

    /** SettingsController / FilmSourcesPanel 都用它收起最上面一层。 */
    @Override
    public void close() {
        pop();
    }

    boolean isShowing() {
        return !pages.isEmpty();
    }

    /** 首页设置按钮：从右侧滑入设置页。 */
    void showSettings() {
        if (!pages.isEmpty()) return;
        changed = false;
        reorder = false;
        savedFocus = host.getCurrentFocus();
        View overlay = LayoutInflater.from(host).inflate(R.layout.activity_launcher_settings, root, false);
        SettingsController controller = new SettingsController(this, overlay);
        Config config = Config.load(host);
        push(overlay, () -> controller.focusFirst(config));
    }

    /** 设置页里点「影视源」：同一个抽屉换页。 */
    private void showSources() {
        if (pages.isEmpty()) return;
        View overlay = LayoutInflater.from(host).inflate(R.layout.activity_film_sources, root, false);
        FilmSourcesPanel controller = new FilmSourcesPanel(this, overlay);
        push(overlay, controller::focusFirst);
    }

    /** 从首页影视墙的「换源」直接打开来源页（不依赖已有设置页）。 */
    void showSourcesOnly() {
        if (!pages.isEmpty()) return;
        changed = false;
        reorder = false;
        savedFocus = host.getCurrentFocus();
        View overlay = LayoutInflater.from(host).inflate(R.layout.activity_film_sources, root, false);
        FilmSourcesPanel controller = new FilmSourcesPanel(this, overlay);
        push(overlay, controller::focusFirst);
    }

    private void push(View overlay, Runnable focus) {
        if (!pages.isEmpty()) pages.get(pages.size() - 1).overlay.setVisibility(View.GONE);
        final View panel = overlay.findViewById(R.id.panel);
        // 面板背景跟顶栏同档位，并从顶栏下沿开始：顶栏右侧按钮不再被抽屉盖住。
        // 设置页和影视源页都从这里 push 进来。
        UiAlpha.applyPanel(overlay, Config.load(host).dockOpacity, host.topBarBottom());
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages.add(new Page(overlay, focus));
        View backdrop = overlay.findViewById(R.id.backdrop);
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(200).start();
        panel.post(() -> {
            float width = panel.getWidth() > 0 ? panel.getWidth() : 460 * host.getResources().getDisplayMetrics().density;
            panel.setTranslationX(width);
            panel.animate().translationX(0f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
            // 换页时旧页面被置 GONE 会先清一次焦点，系统在下一帧才把焦点落到遮罩上；
            // 再 post 一次把焦点抢回第一个可选项，遥控方向键才不会失灵。
            panel.post(focus);
        });
        if (pages.size() == 1) setHomeFocusable(false);
    }

    private void pop() {
        if (pages.isEmpty()) return;
        Page page = pages.remove(pages.size() - 1);
        root.removeView(page.overlay);
        if (!pages.isEmpty()) {
            Page previous = pages.get(pages.size() - 1);
            previous.overlay.setVisibility(View.VISIBLE);
            previous.overlay.post(previous.focus);
            return;
        }
        setHomeFocusable(true);
        if (savedFocus != null && savedFocus.isAttachedToWindow()) savedFocus.requestFocus();
        else {
            View button = host.findViewById(R.id.btnSettings);
            if (button != null) button.requestFocus();
        }
        boolean wasChanged = changed;
        boolean wasReorder = reorder;
        changed = false;
        reorder = false;
        callback.onDrawerClosed(wasChanged, wasReorder);
    }

    /** 抽屉打开时挡住首页内容的焦点，方向键不会穿到后面的图标网格。 */
    private void setHomeFocusable(boolean enabled) {
        ViewGroup home = root.findViewById(R.id.homeContent);
        if (home != null) home.setDescendantFocusability(enabled
                ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    /** 返回键逐层收起；其它按键照常分发。 */
    boolean onKey(KeyEvent event) {
        if (pages.isEmpty()) return false;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_ESCAPE) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) pop();
        return true;
    }
}
