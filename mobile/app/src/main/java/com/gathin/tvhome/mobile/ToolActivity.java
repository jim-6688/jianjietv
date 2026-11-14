package com.gathin.tvhome.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 四个工具页共用同一套：标题 + 状态条 + 可滚动内容区，内容用代码生成，少写重复布局。 */
public abstract class ToolActivity extends Activity {

    private TextView statusView;
    protected LinearLayout content;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_tool);
        ((TextView) findViewById(R.id.headerTitle)).setText(title());
        findViewById(R.id.navBack).setOnClickListener(v -> finish());
        statusView = findViewById(R.id.toolStatus);
        content = findViewById(R.id.toolContent);
        statusView.setText(initialStatus());
        build();
    }

    protected abstract String title();

    protected abstract void build();

    protected String initialStatus() {
        return "";
    }

    protected void status(String text) {
        runOnUiThread(() -> statusView.setText(text));
    }

    protected void addView(View view) {
        content.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** 清空内容区，子页面做「返回列表 / 切换品牌」这类原地导航时用。 */
    protected void clearContent() {
        content.removeAllViews();
    }

    /** 一个带图标的可点击操作行（卡片样式，自带涟漪）。 */
    protected View action(int iconRes, String text, View.OnClickListener listener) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_action, content, false);
        ((ImageView) row.findViewById(R.id.rowIcon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.rowText)).setText(text);
        row.setOnClickListener(listener);
        content.addView(row);
        return row;
    }

    /** 兼容旧调用：无指定图标时使用通用手机图标。 */
    protected View button(String text, View.OnClickListener listener) {
        return action(R.drawable.ic_smartphone, text, listener);
    }

    /** 追加一行说明/日志，可从后台线程直接调用。 */
    protected void line(String text) {
        runOnUiThread(() -> {
            TextView t = new TextView(this);
            t.setText(text);
            t.setTextSize(14);
            t.setTextColor(getColor(R.color.on_surface_variant));
            t.setLineSpacing(2, 1.05f);
            t.setPadding(2, 12, 2, 4);
            content.addView(t, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        });
    }

    protected void heading(String text) {
        runOnUiThread(() -> {
            TextView t = new TextView(this);
            t.setText(text);
            t.setTextSize(17);
            t.setPadding(2, 22, 2, 6);
            t.setTextColor(getColor(R.color.on_surface));
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            content.addView(t, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        });
    }

    protected void background(Runnable work) {
        new Thread(work, "tvhome-mobile").start();
    }
}
