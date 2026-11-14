package com.gathin.tvhome;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「影视分类」面板：选中一个分类后打开影视 App 并带上关键字搜索。
 * 桌面内容页（{@link FilmHub}）也复用 {@link #openFilm}：那里传进来的是豆瓣返回的片名。
 */
public final class FilmCategoriesActivity extends Activity {
    private static final String FILM_PACKAGE = "com.fongmi.android.tv";

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(UiScale.wrap(LauncherLocale.wrap(base)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_film_categories);
        // Tap the dimmed area outside the card to close the panel.
        findViewById(R.id.backdrop).setOnClickListener(v -> finish());
        int[] ids = {R.id.cat0, R.id.cat1, R.id.cat2, R.id.cat3, R.id.cat4,
                R.id.cat5, R.id.cat6, R.id.cat7, R.id.cat8, R.id.cat9};
        View.OnClickListener pick = v -> {
            Object tag = v.getTag();
            if (tag != null) openFilm(this, tag.toString());
            finish();
        };
        for (int id : ids) {
            TextView c = findViewById(id);
            if (c != null) c.setOnClickListener(pick);
        }
        // 「换源」：打开影视源按钮面板。
        View sourceEntry = findViewById(R.id.sourceEntry);
        if (sourceEntry != null) sourceEntry.setOnClickListener(v ->
                startActivity(new Intent(this, FilmSourcesActivity.class)));
        // 面板根节点自身可聚焦会抢走焦点，导致遥控方向键没反应；进面板就把焦点放到第一个分类上。
        TextView first = findViewById(R.id.cat0);
        if (first != null) first.requestFocus();
    }

    /**
     * 用关键字（内容页传片名，分类面板传分类名）打开影视 App：
     * 优先 ACTION_SEARCH 把关键字交给它搜索，App 不支持搜索或影视未安装时分别退化为「只启动 / 提示」。
     * 桌面不存片源、不播放，播放完全在影视 App 内完成。
     */
    static void openFilm(Context context, String query) {
        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(FILM_PACKAGE);
        if (launch == null) launch = pm.getLaunchIntentForPackage(FILM_PACKAGE);
        if (launch == null) {
            Toast.makeText(context, R.string.film_app_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (query != null && !query.isEmpty()) {
            try {
                Intent search = new Intent(Intent.ACTION_SEARCH);
                search.setPackage(FILM_PACKAGE);
                search.putExtra(SearchManager.QUERY, query);
                search.putExtra("keyword", query);
                search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (search.resolveActivity(pm) != null) {
                    context.startActivity(search);
                    return;
                }
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // 影视 App 声明了搜索却起不来：退化成只启动 App
            }
        }
        try {
            context.startActivity(launch);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // 影视 App 刚被卸载/停用，桌面不因点击海报而崩
        }
    }
}
