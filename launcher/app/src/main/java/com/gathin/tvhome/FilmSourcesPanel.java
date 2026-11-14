package com.gathin.tvhome;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 数据源面板的 View 版：和独立 Activity 共用同一套源（内置 + 手机扫码加的）与切换逻辑，
 * 供右侧抽屉换页使用。
 */
final class FilmSourcesPanel {

    interface Host {
        Activity activity();

        void close();
    }

    private final Host host;
    private final Activity a;
    private final View view;
    private TextView current;
    private TextView first;

    FilmSourcesPanel(Host host, View view) {
        this.host = host;
        this.a = host.activity();
        this.view = view;
        bind();
    }

    private void bind() {
        View backdrop = view.findViewById(R.id.backdrop);
        backdrop.setFocusable(false);   // 遮罩不能抢走第一个源按钮的焦点
        backdrop.setOnClickListener(v -> host.close());
        UiAlpha.applyPanel(view, Config.load(a).dockOpacity);
        current = view.findViewById(R.id.sourceCurrent);
        showCurrent();
        // 手机扫码配置：起一个局域网小服务，把地址画成二维码贴在顶部。
        new SourceScanView(a, view, this::showCurrent);
        buildChips(view.findViewById(R.id.sourceList));
    }

    void focusFirst() {
        if (first != null) first.requestFocus();
    }

    private void showCurrent() {
        if (current == null) return;
        String name = FilmSourceSwitcher.currentName(a);
        current.setText(name == null ? "当前：未切换过（影视 App 使用自己的配置）" : "当前：" + name);
    }

    private void buildChips(LinearLayout list) {
        List<String[]> sources = FilmSources.all(a);
        int perRow = 3;   // 右侧抽屉比全屏面板窄，一行放 3 个更好按
        LinearLayout row = null;
        for (int i = 0; i < sources.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(4);
                list.addView(row, rp);
            }
            TextView chip = chip(sources.get(i)[0], sources.get(i)[1]);
            if (first == null) first = chip;
            row.addView(chip);
        }
        int missing = (perRow - sources.size() % perRow) % perRow;
        for (int i = 0; i < missing; i++) {
            View spacer = new View(a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            spacer.setVisibility(View.INVISIBLE);
            row.addView(spacer, lp);
        }
    }

    private TextView chip(String name, String url) {
        TextView v = new TextView(a);
        v.setText(name);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        v.setTextColor(0xFFFFFFFF);
        v.setBackgroundResource(R.drawable.settings_choice_bg);
        v.setSingleLine(true);   // 名字长就省略，别在 56dp 高的按钮里换行
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setFocusable(true);
        v.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        v.setLayoutParams(lp);
        v.setOnClickListener(view -> pick(name, url));
        return v;
    }

    private void pick(String name, String url) {
        Toast.makeText(a.getApplicationContext(), "正在切换到「" + name + "」…", Toast.LENGTH_SHORT).show();
        FilmSourceSwitcher.switchTo(a, name, url, (ok, message) ->
                Toast.makeText(a.getApplicationContext(), message, Toast.LENGTH_LONG).show());
        host.close();
    }

    private int dp(int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }
}
