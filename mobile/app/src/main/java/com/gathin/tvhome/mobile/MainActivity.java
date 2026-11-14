package com.gathin.tvhome.mobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** 手机版主页：本轮打通 1/2/3，功能 4–7 在「更多功能」里列出规划与接口。 */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.deviceStatus);
        ((TextView) findViewById(R.id.headerTitle)).setText("吾爱TV助手");
        ((TextView) findViewById(R.id.headerSubtitle)).setText("手机端 · TV 桌面管理");

        setFeature(R.id.btnBrand, R.drawable.ic_brand, "按品牌引导开启 ADB",
                "按电视品牌找到开发者选项 / 无线调试");
        setFeature(R.id.btnScan, R.drawable.ic_scan, "扫描发现电视",
                "局域网自动发现 ADB 设备");
        setFeature(R.id.btnSetup, R.drawable.ic_download, "安装 TV 版并守护",
                "推送安装并设为默认桌面");
        setFeature(R.id.btnRemote, R.drawable.ic_remote, "TV 遥控器",
                "方向 / 确认 / 音量，按品牌自动选键码");
        setFeature(R.id.btnApps, R.drawable.ic_apps, "TV 软件管理",
                "卸载第三方 / 停用系统应用");
        setFeature(R.id.btnSources, R.drawable.ic_database, "TV 数据源",
                "切换源 / 上传 py 配置源");
        setFeature(R.id.btnSync, R.drawable.ic_sync, "配置同步到 TV",
                "列数 / dock / 隐藏 / 键码一键推送");
        setFeature(R.id.btnMore, R.drawable.ic_more, "功能汇总",
                "7 项功能进度与说明");

        open(R.id.btnBrand, BrandGuideActivity.class);
        open(R.id.btnScan, ScanActivity.class);
        open(R.id.btnSetup, SetupActivity.class);
        open(R.id.btnRemote, RemoteActivity.class);
        open(R.id.btnApps, AppManagerActivity.class);
        open(R.id.btnSources, SourcesActivity.class);
        open(R.id.btnSync, SyncActivity.class);
        open(R.id.btnMore, MoreActivity.class);
    }

    private void setFeature(int cardId, int icon, String title, String subtitle) {
        View card = findViewById(cardId);
        ((ImageView) card.findViewById(R.id.rowIcon)).setImageResource(icon);
        ((TextView) card.findViewById(R.id.rowTitle)).setText(title);
        ((TextView) card.findViewById(R.id.rowSubtitle)).setText(subtitle);
    }

    private void open(int id, Class<?> target) {
        findViewById(id).setOnClickListener(v -> startActivity(new Intent(this, target)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        String host = Prefs.host(this);
        if (host.isEmpty()) {
            status.setText("尚未连接电视 · 先做第 1 步开启 ADB，再做第 2 步扫描");
        } else {
            status.setText("当前电视：" + host + " · " + Prefs.model(this)
                    + (Prefs.guarded(this) ? " · 已安装并守护" : " · 未安装 TV 版"));
        }
    }
}
