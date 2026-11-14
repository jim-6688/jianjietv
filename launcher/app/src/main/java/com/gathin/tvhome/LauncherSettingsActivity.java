package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 兼容入口：正常情况首页用 HomeDrawer 把设置贴在右侧显示，不会启动这个 Activity。
 * 保留它是为了外部 Intent / 老快捷方式仍然能打开同一套设置界面。
 */
public final class LauncherSettingsActivity extends Activity implements SettingsController.Host {

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(UiScale.wrap(LauncherLocale.wrap(base)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        SettingsController controller = new SettingsController(this, getWindow().getDecorView());
        controller.focusFirst(Config.load(this));
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public void close() {
        finish();
    }

    @Override
    public void changed() {
        setResult(RESULT_OK);
    }

    @Override
    public void reorder() {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
    }

    @Override
    public void openSources() {
        startActivity(new Intent(this, FilmSourcesActivity.class));
    }
}
