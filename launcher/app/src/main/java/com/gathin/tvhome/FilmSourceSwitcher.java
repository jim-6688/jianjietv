package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 让影视 App 切换点播配置源，不需要重新编译影视 App。
 *
 * <p>影视 App（com.fongmi.android.tv）加载配置时会自带一个本地 HTTP 服务（NanoHTTPD，端口从 9978
 * 起顺延），其中的 /action 接口在收到
 * {@code do=sync&mode=1&type=history&config=<json>&targets=[]} 时，如果配置地址与当前不同就会调用
 * {@code VodConfig.load()}，效果等同于在影视 App 里换源；targets 为空数组时 History.sync() 是空操作，
 * 不会动观看记录。
 *
 * <p>因此桌面端只要探测到该端口并发出这一个请求，就能把影视 App 切到内置源。
 */
final class FilmSourceSwitcher {

    /** 影视 App 包名。 */
    private static final String FILM_PACKAGE = "com.fongmi.android.tv";
    /** 影视 App 本地服务端口范围（Server.start() 从 9978 顺延到 9998）。 */
    private static final int PORT_FROM = 9978;
    private static final int PORT_TO = 9998;
    /** 记住上次选择的源，供面板显示"当前"。 */
    private static final String PREFS = "film_sources";
    private static final String KEY_NAME = "current_name";
    private static final String KEY_URL = "current_url";
    /** 等服务就绪的总时长：这台电视冷启动影视 App 要十几秒，留足余量。 */
    private static final long WAIT_TIMEOUT_MS = 30000L;

    private FilmSourceSwitcher() {
    }

    interface Callback {
        void onDone(boolean ok, String message);
    }

    static String currentName(Context ctx) {
        return prefs(ctx).getString(KEY_NAME, null);
    }

    /**
     * 切到指定源：先拉起影视 App（服务随其配置加载启动），再探测端口并下发切换请求。
     * 回调在主线程执行。
     */
    static void switchTo(Context ctx, String name, String url, Callback cb) {
        Context app = ctx.getApplicationContext();
        prefs(app).edit().putString(KEY_NAME, name).putString(KEY_URL, url).apply();
        Handler main = new Handler(Looper.getMainLooper());
        boolean launched = launchFilm(app);
        new Thread(() -> {
            String error = null;
            int port = waitForServer(launched ? WAIT_TIMEOUT_MS : 2500L);
            if (port <= 0) {
                error = "影视 App 本地服务未就绪";
            } else {
                // 服务刚起来时偶发 5xx（实测见过 502），重试几次更稳。
                for (int attempt = 0; attempt < 3; attempt++) {
                    error = sendSwitch(port, name, url);
                    if (error == null) break;
                    try {
                        Thread.sleep(900L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            String msg = error == null ? "已切换到「" + name + "」" : "切换失败：" + error;
            boolean ok = error == null;
            main.post(() -> cb.onDone(ok, msg));
        }, "film-source-switch").start();
    }

    /** 拉起影视 App（优先 leanback 入口）。返回是否成功拉起。 */
    private static boolean launchFilm(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(FILM_PACKAGE);
        if (launch == null) launch = pm.getLaunchIntentForPackage(FILM_PACKAGE);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(launch);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 轮询端口直到服务应答，返回可用端口；超时返回 -1。 */
    private static int waitForServer(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (int port = PORT_FROM; port <= PORT_TO; port++) {
                if (ping(port)) return port;
            }
            try {
                Thread.sleep(400L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    /** 探测某个端口上是否是影视 App 的本地服务。 */
    private static boolean ping(int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/device").openConnection();
            conn.setConnectTimeout(150);
            conn.setReadTimeout(200);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            drain(conn);
            return code == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 下发切换请求；返回 null 表示成功，否则返回错误描述。 */
    private static String sendSwitch(int port, String name, String url) {
        HttpURLConnection conn = null;
        try {
            JSONObject json = new JSONObject();
            json.put("type", 0);
            json.put("name", name);
            json.put("url", url);
            String query = "do=sync&mode=1&type=history&force=false"
                    + "&config=" + URLEncoder.encode(json.toString(), "UTF-8")
                    + "&targets=" + URLEncoder.encode("[]", "UTF-8");
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/action?" + query).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String body = read(conn);
            drain(conn);
            if (code != HttpURLConnection.HTTP_OK) return "HTTP " + code;
            // 影视 App 成功时回 OK；失败时回错误文本（如源地址不可达）。
            if (body != null && body.trim().equalsIgnoreCase("OK")) return null;
            String text = body == null ? "" : body.trim();
            return text.isEmpty() ? "影视 App 未确认" : text;
        } catch (Exception e) {
            String m = e.getMessage();
            return m == null ? e.getClass().getSimpleName() : m;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(HttpURLConnection conn) {
        try (InputStream is = conn.getInputStream()) {
            byte[] buf = new byte[512];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void drain(HttpURLConnection conn) {
        try (InputStream es = conn.getErrorStream()) {
            if (es != null) while (es.read() != -1) { /* 丢弃 */ }
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
