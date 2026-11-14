package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * 分辨率自适应字号：4K 电视上调不通版式，字会显得又小又难认。
 *
 * <p>以 1080p 为 1.0 基准，按屏幕短边像素线性放大，最多 1.5 倍：
 * 1080p → 1.0，1440p → 1.33，4K → 1.5（封顶）。720p 一类的更小屏不缩小，保持 1.0。
 *
 * <p>只放大 sp（{@link Configuration#fontScale}），dp 版式、卡片高度、间距都不动，
 * 所以放大后仍然落在原来的格子里；每个 Activity 在 {@code attachBaseContext} 里套一层即可，
 * 布局 XML 里的 sp、代码里的 {@code setTextSize(SP, …)}、Dialog 里的字号都会跟着走。
 */
final class UiScale {

    /** 基准短边像素：1080p。 */
    private static final float BASE_SHORT_SIDE = 1080f;
    /** 放大上限：再大字就要从卡片、顶栏里挤出来了。 */
    private static final float MAX_FACTOR = 1.5f;

    private UiScale() {
    }

    /** 这张屏的字号倍数，1.0 ~ 1.5。 */
    static float factor(Context ctx) {
        if (ctx == null) return 1f;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        if (shortSide <= 0) return 1f;
        float scale = shortSide / BASE_SHORT_SIDE;
        if (scale < 1f) return 1f;
        return Math.min(MAX_FACTOR, scale);
    }

    /** 套上字号倍数的 Context；1080p 及以下原样返回，什么都不改。 */
    static Context wrap(Context base) {
        if (base == null) return null;
        float scale = factor(base);
        if (scale <= 1.001f) return base;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        // 电视自己也可能设了字号缩放，叠加而不是覆盖。
        c.fontScale = c.fontScale * scale;
        return base.createConfigurationContext(c);
    }
}
