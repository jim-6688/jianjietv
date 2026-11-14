package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 独立窗口版的数据源面板（设置抽屉里用的是 {@link FilmSourcesPanel}，两边共用同一套源）。
 * 把内置的饭总 / 肥猫 / 嗷呜等源做成按钮，点一下即把影视 App 切到该源（走影视 App 自带的本地 HTTP 接口）；
 * 顶部还有手机扫码配置：同一局域网里扫码就能换源、加自定义地址、上传 py。
 */
public final class FilmSourcesActivity extends Activity {

    /** 每行放几个源。 */
    private static final int PER_ROW = 4;

    private TextView current;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(UiScale.wrap(LauncherLocale.wrap(base)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_film_sources);
        // 点卡片外的暗色区域关闭面板。
        View root = findViewById(R.id.backdrop);
        root.setOnClickListener(v -> finish());
        current = findViewById(R.id.sourceCurrent);
        showCurrent();
        new SourceScanView(this, root, this::showCurrent);
        TextView first = buildChips(findViewById(R.id.sourceList));
        // 面板根节点自身可聚焦会抢走焦点，导致遥控方向键没反应；进面板就把焦点放到第一个源上。
        if (first != null) first.requestFocus();
    }

    private void showCurrent() {
        if (current == null) return;
        String name = FilmSourceSwitcher.currentName(this);
        current.setText(name == null ? "当前：未切换过（影视 App 使用自己的配置）" : "当前：" + name);
    }

    /** 按 PER_ROW 分行生成源按钮；末行不足时补不可见占位，保证每列等宽。返回第一个按钮。 */
    private TextView buildChips(LinearLayout list) {
        List<String[]> sources = FilmSources.all(this);
        TextView first = null;
        LinearLayout row = null;
        for (int i = 0; i < sources.size(); i++) {
            if (i % PER_ROW == 0) {
                row = new LinearLayout(this);
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
        int missing = (PER_ROW - sources.size() % PER_ROW) % PER_ROW;
        for (int i = 0; i < missing; i++) {
            View spacer = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            spacer.setVisibility(View.INVISIBLE);
            row.addView(spacer, lp);
        }
        return first;
    }

    private TextView chip(String name, String url) {
        TextView v = new TextView(this);
        v.setText(name);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        v.setTextColor(0xFFFFFFFF);
        v.setBackgroundResource(R.drawable.settings_choice_bg);
        v.setSingleLine(true);   // 名字长就省略，别在 56dp 高的按钮里换行
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setFocusable(true);
        v.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        v.setLayoutParams(lp);
        v.setOnClickListener(view -> pick(name, url));
        return v;
    }

    /** 选中即切换：面板立刻收起，切换结果用 Toast 反馈。 */
    private void pick(String name, String url) {
        Toast.makeText(getApplicationContext(), "正在切换到「" + name + "」…", Toast.LENGTH_SHORT).show();
        FilmSourceSwitcher.switchTo(this, name, url, (ok, message) ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
