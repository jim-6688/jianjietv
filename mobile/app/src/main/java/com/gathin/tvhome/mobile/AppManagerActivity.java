package com.gathin.tvhome.mobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.gathin.tvhome.mobile.adb.Adb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 功能 4：管理电视上的应用——列出第三方（可卸载）与系统应用（可停用/启用）。
 * 纯 adb shell 的 pm 命令，不依赖电视端任何额外代码；带保护名单，避免误停关键系统组件。
 */
public class AppManagerActivity extends ToolActivity {

    /** 这些包绝不允许从手机端卸载/停用，停了会直接让电视无法使用。 */
    private static final Set<String> PROTECTED = new HashSet<>();

    static {
        PROTECTED.add("com.gathin.tvhome");               // 我们自己的 TV 桌面
        PROTECTED.add("android");
        PROTECTED.add("com.android.systemui");
        PROTECTED.add("com.android.launcher3");
        PROTECTED.add("com.android.tv");
        PROTECTED.add("com.android.settings");
        PROTECTED.add("com.google.android.gsf");
    }

    private static final int PORT = 5555;
    private Adb adb;

    @Override
    protected String title() {
        return "4 · TV 软件管理";
    }

    @Override
    protected String initialStatus() {
        String host = Prefs.host(this);
        return host.isEmpty() ? "还没有电视，请先去「扫描发现电视」保存一台"
                : "当前电视：" + host + " · 列出应用后可卸载/停用";
    }

    @Override
    protected void build() {
        String host = Prefs.host(this);
        if (host.isEmpty()) {
            action(R.drawable.ic_scan, "去扫描发现电视", v -> startActivity(new Intent(this, ScanActivity.class)));
            heading("说明");
            line("这里能列出电视上安装的应用，卸载第三方应用、停用/启用系统应用。");
            line("操作需要电视已开启网络 ADB 并在同一 Wi-Fi。");
            return;
        }
        action(R.drawable.ic_sync, "刷新应用列表", v -> load());
        load();
    }

    private Adb connect(String host) throws IOException {
        if (adb != null) {
            try {
                adb.shell("echo ok");
                return adb;
            } catch (IOException e) {
                try {
                    adb.close();
                } catch (Exception ignored) {
                }
                adb = null;
            }
        }
        adb = Adb.connect(this, host, PORT, 6000);
        return adb;
    }

    private void load() {
        final String host = Prefs.host(this);
        status("正在读取电视应用列表 …");
        background(() -> {
            try {
                Adb a = connect(host);
                List<AppEntry> third = parse(a.shell("pm list packages -3 -f"), false);
                List<AppEntry> system = parse(a.shell("pm list packages -s -f"), true);
                Collections.sort(third, (x, y) -> x.label.compareToIgnoreCase(y.label));
                Collections.sort(system, (x, y) -> x.label.compareToIgnoreCase(y.label));
                runOnUiThread(() -> render(third, system));
                status("第三方 " + third.size() + " · 系统 " + system.size() + " · " + host);
            } catch (IOException e) {
                adb = null;
                status("读取失败：" + e.getMessage() + "\n请确认电视在线且已允许调试");
            }
        });
    }

    private List<AppEntry> parse(String out, boolean system) {
        List<AppEntry> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (!line.startsWith("package:")) continue;
            // package:/data/app/com.x-1/base.apk=com.x
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String pkg = line.substring(eq + 1).trim();
            if (pkg.isEmpty()) continue;
            String apk = line.substring(9, eq).trim();
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.apk = apk;
            e.system = system;
            e.label = pkg.contains(".") ? pkg.substring(pkg.lastIndexOf('.') + 1) : pkg;
            list.add(e);
        }
        return list;
    }

    private void render(List<AppEntry> third, List<AppEntry> system) {
        clearContent();
        action(R.drawable.ic_sync, "刷新应用列表", v -> load());

        heading("第三方应用（可卸载）");
        if (third.isEmpty()) line("没有第三方应用。");
        for (AppEntry e : third) row(e);

        heading("系统应用（可停用/启用）");
        if (system.isEmpty()) line("没有读取到系统应用（可能被限制）。");
        for (AppEntry e : system) row(e);
    }

    private void row(final AppEntry e) {
        View v = LayoutInflater.from(this).inflate(R.layout.app_row, content, false);
        String letter = e.label.isEmpty() ? "#" : e.label.substring(0, 1).toUpperCase();
        ((TextView) v.findViewById(R.id.appLetter)).setText(letter);
        ((TextView) v.findViewById(R.id.appTitle)).setText(e.label);
        ((TextView) v.findViewById(R.id.appSub)).setText(e.pkg
                + (e.system ? " · 系统" : " · 第三方"));
        Button primary = v.findViewById(R.id.btnPrimary);
        Button secondary = v.findViewById(R.id.btnSecondary);
        boolean protected_ = PROTECTED.contains(e.pkg) || e.pkg.startsWith("com.android.")
                || e.pkg.startsWith("com.google.android.");

        if (e.system) {
            primary.setText("启用");
            primary.setTag("enable");
            secondary.setText("停用");
            secondary.setBackgroundResource(R.drawable.bg_card_danger);
            secondary.setTextColor(getColor(R.color.danger));
            secondary.setTag("disable");
        } else {
            primary.setText("卸载");
            primary.setBackgroundResource(R.drawable.bg_card_danger);
            primary.setTextColor(getColor(R.color.danger));
            primary.setTag("uninstall");
            secondary.setVisibility(View.GONE);
        }
        if (protected_) {
            primary.setEnabled(false);
            secondary.setEnabled(false);
            ((TextView) v.findViewById(R.id.appSub)).append(" · 受保护");
        }
        primary.setOnClickListener(x -> confirm(e, (String) primary.getTag()));
        secondary.setOnClickListener(x -> confirm(e, (String) secondary.getTag()));
        addView(v);
    }

    private void confirm(final AppEntry e, final String op) {
        String verb = op.equals("uninstall") ? "卸载" : op.equals("disable") ? "停用" : "启用";
        String msg = verb + " " + e.label + "？\n" + e.pkg
                + (op.equals("disable") ? "\n停用后该应用不会出现在桌面，但随时可重新启用。" : "");
        new AlertDialog.Builder(this)
                .setTitle(verb + "应用")
                .setMessage(msg)
                .setPositiveButton("确定", (d, w) -> run(op, e))
                .setNegativeButton("取消", null)
                .show();
    }

    private void run(final String op, final AppEntry e) {
        final String host = Prefs.host(this);
        status("正在" + op + " " + e.label + " …");
        background(() -> {
            try {
                Adb a = connect(host);
                String cmd;
                if ("uninstall".equals(op)) cmd = "pm uninstall " + e.pkg;
                else if ("disable".equals(op)) cmd = "pm disable-user --user 0 " + e.pkg;
                else cmd = "pm enable " + e.pkg;
                String result = a.shell(cmd);
                runOnUiThread(() -> load());
                status((result.toLowerCase().contains("error") || result.toLowerCase().contains("failure")
                        ? "操作未成功：" + result : "已" + op + "：" + e.label) + " · " + host);
            } catch (IOException ex) {
                adb = null;
                status("操作失败：" + ex.getMessage());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adb != null) {
            try {
                adb.close();
            } catch (Exception ignored) {
            }
            adb = null;
        }
    }

    private static final class AppEntry {
        String pkg;
        String apk;
        String label;
        boolean system;
    }
}
