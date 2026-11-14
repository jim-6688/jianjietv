package com.gathin.tvhome.mobile;

import com.gathin.tvhome.mobile.adb.Adb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 功能 3：把 TV 桌面 APK 装到电视、设为默认 Home、启用按键助手（守护），
 * 并提供用户确认过的「一键关闭 ADB + 校验 5555」。
 *
 * <p>顺序照 launcher/build-and-install.sh 的 Home guard：先问 SDK、先装、先确认桌面能起来，
 * 才动默认 Home；本轮不禁用原厂桌面，所以不会出现「两个 Home 都没了」的风险。
 */
public class SetupActivity extends ToolActivity {

    private static final String PKG = "com.gathin.tvhome";
    private static final String HOME_COMPONENT = PKG + "/.MainActivity";
    private static final String KEY_SERVICE = PKG + "/.HomeKeyService";
    private static final String REMOTE = "/data/local/tmp/tvhome.apk";
    private static final int PORT = 5555;

    @Override
    protected String title() {
        return "3 · 安装 TV 版并守护";
    }

    @Override
    protected String initialStatus() {
        String host = Prefs.host(this);
        return host.isEmpty() ? "还没有电视，请先做第 2 步扫描" : "当前电视：" + host;
    }

    @Override
    protected void build() {
        action(R.drawable.ic_download, "安装 TV 版并加入守护", v -> background(this::install));
        action(R.drawable.ic_close, "一键关闭电视 ADB（用完请点）", v -> background(this::closeAdb));
        line("APK 来自手机 App 构建时从 launcher/app/build/outputs/apk/debug 带进来的最新 debug 包。");
        line("守护 = 设为默认 Home + 启用 HomeKeyService 按键助手；不禁用原厂桌面，随时可回退。");
    }

    private void install() {
        final String host = Prefs.host(this);
        if (host.isEmpty()) {
            status("请先做第 2 步扫描并选择电视");
            return;
        }
        File apk = bundledApk();
        if (apk == null) {
            status("没有随包带上 TV 版 APK：先构建 launcher 的 debug 包，再重新构建手机 App");
            return;
        }
        // 推送前校验（对齐 HaloTV「手机推送校验」：类型 / 包名 / 签名 / 内部号）。
        ApkVerifier.ApkInfo info = ApkVerifier.read(apk, this);
        if (!info.valid) {
            status("随包的 TV 版 APK 无法解析，可能不是合法 APK，已停止");
            return;
        }
        if (!PKG.equals(info.packageName)) {
            status("APK 包名是 " + info.packageName + "，不是 " + PKG + "，推错包了，已停止");
            return;
        }
        line("待推送：" + PKG + " · 内部号 " + info.versionCode
                + (info.versionName != null && !info.versionName.isEmpty() ? "（" + info.versionName + "）" : ""));
        if (info.certSha256 != null) {
            int n = info.certSha256.length();
            line("签名指纹(SHA-256)：…" + info.certSha256.substring(Math.max(0, n - 12)));
        }

        status("连接 " + host + " …");
        Adb adb = null;
        try {
            adb = Adb.connect(this, host, PORT, 5000);
            // 先问 SDK：低于 minSdk 26 就别推过去（build-and-install.sh 也是这个顺序）。
            String sdk = adb.shell("getprop ro.build.version.sdk").trim();
            int version = parseInt(sdk);
            if (version > 0 && version < 26) {
                status("这台电视 Android SDK " + version + "，低于桌面要求的 26，已停止安装");
                return;
            }
            // 和电视上已装的版本比一比，推送前就把升级 / 降版 / 全新讲清楚。
            long tvVer = ApkVerifier.parseVersionCode(adb.shell("dumpsys package " + PKG));
            if (tvVer > 0) {
                if (tvVer == info.versionCode) {
                    line("电视已装同内部号 " + tvVer + "，将覆盖重装");
                } else if (tvVer < info.versionCode) {
                    line("电视已装内部号 " + tvVer + " → 升级到 " + info.versionCode);
                } else {
                    line("注意：电视已装内部号 " + tvVer + " 高于待推送的 " + info.versionCode + "，这是降版");
                }
            } else {
                line("电视上尚未安装 " + PKG + "，将是全新安装");
            }

            line("推送 TV 版（" + (apk.length() / 1024) + " KB）…");
            adb.push(apk, REMOTE, message -> line(message));
            line("安装 …");
            String out = adb.shell("pm install -r " + REMOTE + "; rm -f " + REMOTE);
            line(out.isEmpty() ? "pm install 无输出" : out);
            if (!out.contains("Success")) {
                // 把常见的失败原因翻译成人话，避免只丢一句「没返回 Success」。
                if (out.contains("INCOMPATIBLE") || out.contains("signature")
                        || out.contains("SIGNATURE") || out.contains(" signatures ")) {
                    status("安装被拒：签名不一致——电视上已装的 " + PKG + " 用了不同的签名，"
                            + "请先在「TV 软件管理」卸载旧版再装");
                } else if (out.contains("DOWNGRADE")) {
                    status("安装被拒：不允许降版（电视已装更高内部号）；如需重装请先卸载");
                } else {
                    status("安装没有返回 Success，已停止；原桌面保持不变");
                }
                return;
            }
            // 电视端确认安装：回查内部号，确认装上去的就是这一版（对应 HaloTV 的「回电视确认」）。
            long after = ApkVerifier.parseVersionCode(adb.shell("dumpsys package " + PKG));
            if (after == info.versionCode) {
                line("电视端确认：已安装 " + PKG + "，内部号 " + after);
            } else {
                line("电视端回查内部号 " + after + " 与待推送的 " + info.versionCode
                        + " 不一致，请到电视上确认是否装上了正确版本");
            }

            // Home guard：先确认新桌面真的能起来，再改默认 Home。
            String started = adb.shell("am start -W -n " + HOME_COMPONENT);
            line("启动检查：" + firstLine(started));
            if (started.contains("Error") || started.contains("Error type")) {
                status("桌面启动失败，已停止设置默认 Home；原桌面保持不变");
                return;
            }
            adb.shell("cmd package set-home-activity --user 0 " + HOME_COMPONENT);
            String home = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                    + "-c android.intent.category.HOME");
            line("当前默认桌面：" + firstLine(home));
            // 守护：启用按键助手，并保留电视上原本已启用的其它无障碍服务。
            adb.shell("pm enable " + KEY_SERVICE);
            String current = adb.shell("settings get secure enabled_accessibility_services").trim();
            String merged = current.contains(PKG) ? current
                    : (current.isEmpty() || "null".equals(current)) ? KEY_SERVICE : current + ":" + KEY_SERVICE;
            adb.shell("settings put secure enabled_accessibility_services " + merged);
            adb.shell("settings put secure accessibility_enabled 1");
            Prefs.setGuarded(this, true);
            status(home.contains(PKG) ? "安装完成并已设为默认桌面、按键助手已启用"
                    : "安装与守护已完成，但默认桌面校验没指向新桌面；请在电视上确认一次");
        } catch (IOException e) {
            status("失败：" + e.getMessage());
        } finally {
            if (adb != null) adb.close();
        }
    }

    private void closeAdb() {
        final String host = Prefs.host(this);
        if (host.isEmpty()) {
            status("还没有保存电视地址");
            return;
        }
        status("正在下发关闭 ADB …");
        Adb adb = null;
        try {
            adb = Adb.connect(this, host, PORT, 4000);
            Adb.requestCloseAdb(adb);
        } catch (IOException e) {
            status("连接失败（可能已经关了）：" + e.getMessage());
        } finally {
            if (adb != null) adb.close();
        }
        try {
            Thread.sleep(900);
        } catch (InterruptedException ignored) {
        }
        boolean stillOpen = Adb.portOpen(host, PORT, 400);
        status(stillOpen
                ? "5555 仍开放：请到电视开发者选项手动关闭「网络 ADB 调试 / 无线调试」；无线调试还有独立端口，也要关"
                : "5555 已关闭（明文端口不再对外开放）");
    }

    /** assets/tvhome.apk 由 app/build.gradle 的 copyTvApk 在构建时注入，不入库。 */
    private File bundledApk() {
        try (InputStream in = getAssets().open("tvhome.apk")) {
            File target = new File(getCacheDir(), "tvhome.apk");
            try (FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return target.length() > 0 ? target : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isEmpty()) return "(空)";
        int at = text.indexOf('\n');
        return at < 0 ? text : text.substring(0, at);
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
