package com.gathin.tvhome.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.gathin.tvhome.mobile.adb.Adb;

import java.io.IOException;

/**
 * 功能 6：TV 遥控器。复用已保存的电视地址，通过 ADB shell 的 input keyevent 注入按键。
 * 方向键 / 确认 / 返回 / 主页 / 菜单 / 音量 / 最近任务 / 电源 映射 Android KeyEvent 码。
 * 另有「按品牌自动启用对应遥控码」：先识别电视品牌，应用快捷键（Netflix/YouTube 等）优先发品牌专属键码，
 * 没有专属码的回退到 am start 启动对应 App。
 */
public class RemoteActivity extends ToolActivity {

    private static final int PORT = 5555;

    // Android KeyEvent 码，见 android.view.KeyEvent。
    private static final int DPAD_UP = 19;
    private static final int DPAD_DOWN = 20;
    private static final int DPAD_LEFT = 21;
    private static final int DPAD_RIGHT = 22;
    private static final int DPAD_CENTER = 23;
    private static final int KEY_BACK = 4;
    private static final int KEY_HOME = 3;
    private static final int KEY_MENU = 82;
    private static final int VOL_UP = 24;
    private static final int VOL_DOWN = 25;
    private static final int APP_SWITCH = 187;
    private static final int POWER = 26;

    private Adb adb;
    private String brand = "通用";

    @Override
    protected String title() {
        return "6 · TV 遥控器";
    }

    @Override
    protected String initialStatus() {
        String host = Prefs.host(this);
        return host.isEmpty() ? "还没有电视，请先去「扫描发现电视」保存一台"
                : "当前电视：" + host + " · 点按键即连接发送";
    }

    @Override
    protected void build() {
        String host = Prefs.host(this);
        if (host.isEmpty()) {
            action(R.drawable.ic_scan, "去扫描发现电视", v -> startActivity(new Intent(this, ScanActivity.class)));
            heading("怎么用");
            line("1）先在「按品牌引导开启 ADB」让电视打开网络调试；");
            line("2）再到「扫描发现电视」自动发现并保存电视地址；");
            line("3）回来这里即可用手机当遥控器。手机与电视须同一 Wi-Fi。");
            return;
        }

        // 自动识别品牌（来自已保存型号），并允许手动切换。
        brand = RemoteCodes.detectBrand(Prefs.model(this));

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, RemoteCodes.BRANDS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int sel = RemoteCodes.BRANDS.indexOf(brand);
        spinner.setSelection(sel < 0 ? 0 : sel);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                brand = RemoteCodes.BRANDS.get(position);
                status("已切换品牌遥控码：" + brand);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        addView(card("电视品牌（自动识别，可改）", spinner));

        action(R.drawable.ic_sync, "重新连接电视", v -> reconnect());
        LinearLayout pad = (LinearLayout) LayoutInflater.from(this).inflate(R.layout.remote_pad, content, false);
        bind(pad, R.id.keyUp, DPAD_UP, "上");
        bind(pad, R.id.keyDown, DPAD_DOWN, "下");
        bind(pad, R.id.keyLeft, DPAD_LEFT, "左");
        bind(pad, R.id.keyRight, DPAD_RIGHT, "右");
        bind(pad, R.id.keyOk, DPAD_CENTER, "确认");
        bind(pad, R.id.keyBack, KEY_BACK, "返回");
        bind(pad, R.id.keyHome, KEY_HOME, "主页");
        bind(pad, R.id.keyMenu, KEY_MENU, "菜单");
        bind(pad, R.id.keyVolUp, VOL_UP, "音量+");
        bind(pad, R.id.keyVolDown, VOL_DOWN, "音量-");
        bind(pad, R.id.keyRecent, APP_SWITCH, "最近任务");
        bind(pad, R.id.keyPower, POWER, "电源");
        addView(pad);

        heading("应用快捷键（按品牌自动选键码）");
        addView(appShortcutRow());
    }

    /** 一个带标题的卡片容器，用于嵌 Spinner。 */
    private View card(String title, View inner) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundResource(R.drawable.bg_card_status);
        wrap.setPadding(14, 14, 14, 14);
        ViewGroup.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTextColor(getColor(R.color.on_surface_variant));
        wrap.addView(t);
        wrap.addView(inner);
        return wrap;
    }

    private LinearLayout appShortcutRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        for (final String app : RemoteCodes.APPS) {
            Button b = (Button) LayoutInflater.from(this).inflate(R.layout.chip, row, false);
            b.setText(app);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(4, 4, 4, 4);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> sendApp(app));
            row.addView(b);
        }
        return row;
    }

    private void sendApp(String app) {
        final String host = Prefs.host(this);
        final int code = RemoteCodes.appKeycode(brand, app);
        final String pkg = RemoteCodes.APP_PACKAGE.get(app);
        background(() -> {
            try {
                Adb a = ensureConnected(host);
                if (code > 0) {
                    a.shell("input keyevent " + code);
                    status("已发送（" + brand + " 专属键码 " + code + "）：" + app);
                } else if (pkg != null) {
                    a.shell("am start -a android.intent.action.MAIN"
                            + " -c android.intent.category.LEANBACK_LAUNCHER -p " + pkg);
                    status("已启动：" + app + (pkg == null ? "" : "（" + pkg + "）"));
                } else {
                    status("没有「" + app + "」的键码或包名");
                }
            } catch (IOException first) {
                try {
                    if (adb != null) {
                        adb.close();
                        adb = null;
                    }
                    Adb a = Adb.connect(this, host, PORT, 5000);
                    adb = a;
                    if (code > 0) a.shell("input keyevent " + code);
                    else if (pkg != null) a.shell("am start -a android.intent.action.MAIN"
                            + " -c android.intent.category.LEANBACK_LAUNCHER -p " + pkg);
                    status("已发送：" + app + " → " + host);
                } catch (IOException e) {
                    adb = null;
                    status("发送失败：" + e.getMessage() + "\n请确认电视在线、同一 Wi-Fi，且已允许调试");
                }
            }
        });
    }

    private void bind(View pad, int id, int code, String label) {
        pad.findViewById(id).setOnClickListener(v -> sendKey(code, label));
    }

    private void sendKey(int code, String label) {
        final String host = Prefs.host(this);
        if (host.isEmpty()) {
            status("还没有保存电视，请先去扫描");
            return;
        }
        background(() -> {
            try {
                Adb a = ensureConnected(host);
                a.shell("input keyevent " + code);
                status("已发送：" + label + " → " + host);
            } catch (IOException first) {
                try {
                    if (adb != null) {
                        adb.close();
                        adb = null;
                    }
                    Adb a = Adb.connect(this, host, PORT, 5000);
                    adb = a;
                    a.shell("input keyevent " + code);
                    status("已发送：" + label + " → " + host);
                } catch (IOException e) {
                    adb = null;
                    status("发送失败：" + e.getMessage() + "\n请确认电视在线、同一 Wi-Fi，且已允许调试");
                }
            }
        });
    }

    private Adb ensureConnected(String host) throws IOException {
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
        adb = Adb.connect(this, host, PORT, 5000);
        return adb;
    }

    private void reconnect() {
        final String host = Prefs.host(this);
        if (host.isEmpty()) {
            status("还没有保存电视");
            return;
        }
        status("正在连接 " + host + " …");
        background(() -> {
            try {
                if (adb != null) adb.close();
                adb = Adb.connect(this, host, PORT, 5000);
                status("已连接到 " + host);
            } catch (IOException e) {
                adb = null;
                status("连接失败：" + e.getMessage());
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
}
