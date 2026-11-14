package com.gathin.tvhome;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 右侧抽屉面板的背景透明度跟顶栏保持一致：MainActivity.applyCardOpacity() 把 dock_opacity
 * 套到 topBar 和天气卡，这里把同一个档位套到设置页 / 影视源页的 panel。
 * 抽屉和兼容用的独立 Activity 都走这个方法，不会一处实心、一处玻璃。
 */
final class UiAlpha {

    private UiAlpha() {
    }

    static void applyPanel(View overlay, int dockOpacity) {
        applyPanel(overlay, dockOpacity, 0);
    }

    /**
     * @param topInset 顶栏实色条底边到屏幕上沿的距离。>0 时把面板下移到顶栏下方并缩短，
     *                 不再盖住顶栏右侧的按钮；兼容用的独立 Activity 没有顶栏，传 0 保持全高。
     */
    static void applyPanel(View overlay, int dockOpacity, int topInset) {
        if (overlay == null) return;
        View panel = overlay.findViewById(R.id.panel);
        if (panel == null) return;
        if (topInset > 0 && panel.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            int screenH = overlay.getResources().getDisplayMetrics().heightPixels;
            lp.topMargin = topInset;
            lp.height = Math.max(1, screenH - topInset);
            panel.setLayoutParams(lp);
        }
        Drawable bg = panel.getBackground();
        if (bg == null) return;
        Drawable copy = bg.mutate();   // 资源 drawable 共享常量状态，先 mutate 再改
        copy.setAlpha(Math.round(dockOpacity * 255f / 100f));
        panel.setBackground(copy);
    }
}
