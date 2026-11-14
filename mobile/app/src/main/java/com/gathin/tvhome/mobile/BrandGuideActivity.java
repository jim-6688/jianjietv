package com.gathin.tvhome.mobile;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** 功能 1：按电视品牌给出开 ADB 的图文步骤；非 Android TV 明确拦住，不引导工厂菜单。 */
public class BrandGuideActivity extends ToolActivity {

    @Override
    protected String title() {
        return "1 · 按品牌开启 ADB";
    }

    @Override
    protected String initialStatus() {
        return "选择电视品牌，照图文步骤在电视上打开「网络 ADB 调试」(5555)，然后回到第 2 步扫描";
    }

    @Override
    protected void build() {
        showList();
    }

    /** 一级：品牌列表。 */
    private void showList() {
        clearContent();
        for (Brands.Brand brand : Brands.all()) {
            View row = LayoutInflater.from(this).inflate(R.layout.brand_row, content, false);
            ((ImageView) row.findViewById(R.id.rowIcon))
                    .setImageResource(brand.androidTv ? R.drawable.ic_tv : R.drawable.ic_close);
            ((TextView) row.findViewById(R.id.rowTitle)).setText(brand.name);
            ((TextView) row.findViewById(R.id.rowSubtitle)).setText(
                    brand.androidTv ? "支持 · 照步骤开启网络 ADB" : "非 Android TV · 本工具不适用");
            final Brands.Brand b = brand;
            row.setOnClickListener(v -> showBrand(b));
            content.addView(row);
        }
        heading("通用安全提示");
        line(Brands.warning());
    }

    /** 二级：该品牌的图文步骤。 */
    private void showBrand(Brands.Brand brand) {
        clearContent();
        action(R.drawable.ic_back, "返回品牌列表", v -> showList());

        if (!brand.androidTv) {
            addInfo("此系统没有 ADB：" + brand.note);
            return;
        }

        // 流程示意图：电视 → 设置 → Wi-Fi/网络 → 完成
        ImageView illus = new ImageView(this);
        illus.setImageResource(R.drawable.illus_open_adb);
        illus.setScaleType(ImageView.ScaleType.FIT_CENTER);
        illus.setPadding(0, 4, 0, 12);
        addView(illus);

        heading(brand.name);
        for (int i = 0; i < brand.steps.length; i++) {
            addStep(i + 1, brand.steps[i]);
        }
        if (brand.note != null && !brand.note.isEmpty()) {
            addInfo("说明：" + brand.note);
        }
        line("———");
        line(Brands.warning());
    }

    /** 一条带编号 + 图标 + 文字的步骤卡。 */
    private void addStep(int n, String text) {
        View card = LayoutInflater.from(this).inflate(R.layout.step_card, content, false);
        ((TextView) card.findViewById(R.id.stepBadge)).setText(String.valueOf(n));
        ((ImageView) card.findViewById(R.id.stepIcon)).setImageResource(stepIcon(text));
        ((TextView) card.findViewById(R.id.stepText)).setText(text);
        content.addView(card);
    }

    /** 一段提示卡（编号位用「!」+ 盾牌图标）。 */
    private void addInfo(String text) {
        View card = LayoutInflater.from(this).inflate(R.layout.step_card, content, false);
        ((TextView) card.findViewById(R.id.stepBadge)).setText("!");
        ((ImageView) card.findViewById(R.id.stepIcon)).setImageResource(R.drawable.ic_shield);
        ((TextView) card.findViewById(R.id.stepText)).setText(text);
        content.addView(card);
    }

    /** 按步骤文字挑一个最贴切的图标，让每一步都是「图文」。 */
    private static int stepIcon(String text) {
        if (text.contains("版本号") || text.contains("关于") || text.contains("开发者")) {
            return R.drawable.ic_book;
        }
        if (text.contains("网络") || text.contains("IP") || text.contains("Wi-Fi")
                || text.contains("无线")) {
            return R.drawable.ic_wifi;
        }
        if (text.contains("USB") || text.contains("调试")) {
            return R.drawable.ic_smartphone;
        }
        return R.drawable.ic_check;
    }
}
