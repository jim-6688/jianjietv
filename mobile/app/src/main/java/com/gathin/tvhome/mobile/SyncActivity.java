package com.gathin.tvhome.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.gathin.tvhome.mobile.adb.Adb;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 功能 7：把手机上编辑的桌面配置同步到电视。生成 config.json，经 adb push 到
 * /sdcard/Android/data/com.gathin.tvhome/files/，再 force-stop 桌面让新配置生效。
 * 配置字段与电视端 Config.java 读的一致（columns / dock_apps / hidden_apps / app_keys）。
 */
public class SyncActivity extends ToolActivity {

    private static final int PORT = 5555;
    private static final String REMOTE_DIR = "/sdcard/Android/data/com.gathin.tvhome/files";
    private static final String REMOTE_FILE = REMOTE_DIR + "/config.json";
    private static final String TV_PKG = "com.gathin.tvhome";

    private Adb adb;

    @Override
    protected String title() {
        return "7 · 配置同步到 TV";
    }

    @Override
    protected String initialStatus() {
        String host = Prefs.host(this);
        return host.isEmpty() ? "还没有电视，请先去「扫描发现电视」保存一台"
                : "当前电视：" + host + " · 配置将推到 " + TV_PKG;
    }

    @Override
    protected void build() {
        String host = Prefs.host(this);
        if (host.isEmpty()) {
            action(R.drawable.ic_scan, "去扫描发现电视", v -> startActivity(new Intent(this, ScanActivity.class)));
            heading("说明");
            line("这里编辑桌面配置（列数 / dock / 隐藏应用 / 遥控键码），推到电视并重启桌面生效。");
            line("与电视端 Config.java 的 config.json 覆盖层格式一致。");
            return;
        }
        View form = inflate(R.layout.sync_form);
        Button push = form.findViewById(R.id.syncPush);
        push.setOnClickListener(v -> push());
        addView(form);
        heading("提示");
        line("· 只填要改的项，空着的不写进配置，不影响电视上其它设置。");
        line("· 推送后会重启电视桌面，几秒即可恢复。");
    }

    private View inflate(int id) {
        return android.view.LayoutInflater.from(this).inflate(id, content, false);
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

    private void push() {
        final String host = Prefs.host(this);
        EditText colE = content.findViewById(R.id.syncColumns);
        EditText dockE = content.findViewById(R.id.syncDock);
        EditText hiddenE = content.findViewById(R.id.syncHidden);
        EditText keysE = content.findViewById(R.id.syncKeys);

        String colText = colE.getText().toString().trim();
        List<String> dock = lines(dockE.getText().toString());
        List<String> hidden = lines(hiddenE.getText().toString());
        List<String> keys = lines(keysE.getText().toString());

        JSONObject json = new JSONObject();
        try {
            if (!colText.isEmpty()) json.put("columns", Integer.parseInt(colText));
            if (!dock.isEmpty()) json.put("dock_apps", new JSONArray(dock));
            if (!hidden.isEmpty()) json.put("hidden_apps", new JSONArray(hidden));
            if (!keys.isEmpty()) json.put("app_keys", new JSONArray(keys));
        } catch (NumberFormatException e) {
            status("列数必须是 1–8 之间的数字");
            return;
        } catch (Exception e) {
            status("生成配置失败：" + e.getMessage());
            return;
        }

        File local = new File(getCacheDir(), "config.json");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(local)) {
            out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            status("写入临时文件失败：" + e.getMessage());
            return;
        }

        status("正在推送 config.json 到 " + host + " …");
        background(() -> {
            try {
                Adb a = connect(host);
                a.shell("mkdir -p " + REMOTE_DIR);
                a.push(local, REMOTE_FILE, msg -> status(msg));
                a.shell("am force-stop " + TV_PKG);
                status("已推送并重启电视桌面，几秒后生效。\n配置：" + json.toString());
            } catch (IOException e) {
                adb = null;
                status("推送失败：" + e.getMessage() + "\n请确认电视在线、同一 Wi-Fi，且已允许调试");
            }
        });
    }

    private List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("\n")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
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
