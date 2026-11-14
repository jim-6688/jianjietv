package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 数据源页顶部的「手机扫码配置」块：二维码 + 局域网地址 + 已上传的 py。
 *
 * <p>局域网小服务由 {@link MainActivity} 随桌面进程常驻：上传的 py 是影视 App 的配置地址，
 * 页面关掉也不能停。这里只负责复用它、显示二维码，并在页面可见期间注册/注销自己的监听。
 * 没有局域网地址时整块保持隐藏，电视端照常点选源。
 */
final class SourceScanView implements ConfigServer.Listener {

    private final Activity activity;
    private final View scanBox;
    private final ImageView qr;
    private final TextView scanUrl;
    private final LinearLayout pyList;
    private final Runnable onChanged;
    private ConfigServer server;
    private int port;

    SourceScanView(Activity activity, View root, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
        this.scanBox = root.findViewById(R.id.scanBox);
        this.qr = root.findViewById(R.id.scanQr);
        this.scanUrl = root.findViewById(R.id.scanUrl);
        this.pyList = root.findViewById(R.id.pyList);
        bind(root);
    }

    private void bind(View root) {
        if (scanBox == null || qr == null || scanUrl == null) return;
        // 没有局域网地址就没有可扫的地址，连端口都不用占。
        if (ConfigServer.localHost() == null) return;
        server = ConfigServer.start(activity, this);
        String url = server == null ? null : server.url();
        if (url == null) return;   // 没连局域网：保持隐藏
        port = server.port();
        scanBox.setVisibility(View.VISIBLE);
        scanUrl.setText(url);
        int size = Math.max(2, Math.round(116 * activity.getResources().getDisplayMetrics().density));
        Bitmap bitmap = QrCode.bitmap(url, size);
        if (bitmap == null) qr.setVisibility(View.GONE);
        else qr.setImageBitmap(bitmap);
        renderPy();
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                // 页面回来时重新注册监听（服务本身常驻，start 只是复用同一个实例）。
                ConfigServer.start(activity, SourceScanView.this);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // 只注销监听，不关服务：配置页关掉后 py 地址仍然要能访问。
                ConfigServer.clearListener(SourceScanView.this);
            }
        });
    }

    /** 手机上改了东西：提示一句，并把 py 列表和「当前源」刷新一遍。 */
    @Override
    public void onRemoteChange(String message) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Toast.makeText(activity.getApplicationContext(), message, Toast.LENGTH_LONG).show();
        renderPy();
        if (onChanged != null) onChanged.run();
    }

    private void renderPy() {
        if (pyList == null) return;
        pyList.removeAllViews();
        List<File> files = FilmSources.pyFiles(activity);
        if (files.isEmpty()) {
            pyList.setVisibility(View.GONE);
            return;
        }
        pyList.setVisibility(View.VISIBLE);
        addLine(activity.getString(R.string.film_sources_py_title), 0xFFFFFFFF, 14);
        String host = ConfigServer.localHost();
        for (File file : files) {
            String url = host == null ? "" : FilmSources.pyUrl(host, port, file.getName());
            addLine(activity.getString(R.string.film_sources_py_item, file.getName(), url), 0xFFACB8C1, 12);
        }
    }

    private void addLine(String text, int color, int sizeSp) {
        TextView line = new TextView(activity);
        line.setText(text);
        line.setTextColor(color);
        line.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp);
        line.setPadding(0, dp(3), 0, dp(3));
        pyList.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
