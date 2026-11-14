package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 电视上的一个局域网小服务：手机扫码打开的就是它提供的配置页。
 *
 * <p>只做三件事，全部只在局域网内可达：
 * <ul>
 *   <li>{@code GET /}：配置页——列内置源、填自定义地址、上传 py；</li>
 *   <li>{@code POST /apply}：切到选中的源（或刚填的自定义地址），走 {@link FilmSourceSwitcher}；</li>
 *   <li>{@code POST /upload}：收一个 .py 存到 {@code files/py/}，把它置顶为最高优先级的配置源，
 *       并自动让影视 App 切过去；</li>
 *   <li>{@code GET /py/<名字>}：把上传的 py 原样提供给影视 App。</li>
 * </ul>
 *
 * <p>服务随桌面进程常驻：上传的 py 就是影视 App 的配置地址，设置页关掉也不能让这个地址 404。
 * 页面文案跟电视系统语言无关，固定中文：扫码的一定是家里同一台电视的手机。
 */
final class ConfigServer {

    interface Listener {
        /** 手机端改了东西（换了源 / 传了 py），在主线程回调，电视端据此刷新「当前」。 */
        void onRemoteChange(String message);
    }

    /** 影视 App 自己占 9978-9998，这里从 8899 起，避开它。 */
    private static final int PORT_FROM = 8899;
    private static final int PORT_TO = 8919;
    private static final int MAX_BODY = 4 * 1024 * 1024;

    /** 同一时刻只跑一个：桌面进程常驻，设置页、影视墙进来都复用同一个实例。 */
    private static volatile ConfigServer live;

    private final Context app;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket socket;
    private Thread thread;
    private volatile boolean running;
    private int port;

    private ConfigServer(Context app) {
        this.app = app;
    }

    /** 起服务（已在跑就复用）；失败返回 null，调用方隐藏二维码。 */
    static synchronized ConfigServer start(Context ctx, Listener listener) {
        if (live != null && live.running) {
            live.listener = listener;
            return live;
        }
        ConfigServer server = new ConfigServer(ctx.getApplicationContext());
        server.listener = listener;
        server.bind();
        if (!server.running) return null;
        live = server;
        return server;
    }

    /** 只关自己那一个：换了页面/重建界面时，别把新起的那个顺手关掉。 */
    static synchronized void stop(ConfigServer server) {
        if (server == null || server != live) return;
        live.shutdown();
        live = null;
    }

    /** 当前本机地址；没连上局域网时返回 null。 */
    static String localHost() {
        String ip = NetInfo.localIp();
        return ip == null || ip.isEmpty() || "—".equals(ip) ? null : ip;
    }

    /** 常驻服务对外提供某个 py 的地址；服务没起来时不列出这条源，避免给出一个 404 的地址。 */
    static String pyUrl(String name) {
        String host = localHost();
        ConfigServer server = live;
        if (host == null || server == null || !server.running) return null;
        return FilmSources.pyUrl(host, server.port, name);
    }

    /** 设置页关掉时只注销自己的监听，服务继续常驻。 */
    static synchronized void clearListener(Listener owner) {
        if (live != null && live.listener == owner) live.listener = null;
    }

    int port() {
        return port;
    }

    /** 手机要打开的地址；没有局域网地址时返回 null。 */
    String url() {
        String host = localHost();
        return host == null || !running ? null : "http://" + host + ":" + port + "/";
    }

    // ---- 服务端 -----------------------------------------------------------------------------

    private void bind() {
        for (int p = PORT_FROM; p <= PORT_TO; p++) {
            ServerSocket candidate = null;
            try {
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(p));
                socket = candidate;
                port = p;
                break;
            } catch (IOException busy) {
                if (candidate != null) {
                    try {
                        candidate.close();
                    } catch (IOException ignored) {
                        // 关不上也无所谓，换下一个端口
                    }
                }
            }
        }
        if (socket == null) return;
        running = true;
        thread = new Thread(this::serve, "config-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void shutdown() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // 已经关了
        }
        socket = null;
        thread = null;
        listener = null;
    }

    private void serve() {
        while (running) {
            Socket client = null;
            try {
                client = socket.accept();
                handle(client);
            } catch (IOException e) {
                if (!running) return;   // 关服务时 accept 会抛，正常
            } catch (RuntimeException e) {
                // 单个请求出错不拖垮服务
            } finally {
                if (client != null) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                        // 已经断了
                    }
                }
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }
            byte[] body = readBody(in, headers);
            int query = target.indexOf('?');
            String path = query < 0 ? target : target.substring(0, query);

            if ("POST".equals(method) && path.equals("/apply")) {
                applyForm(out, FormBody.urlEncoded(body));
            } else if ("POST".equals(method) && path.equals("/upload")) {
                receiveUpload(out, headers, body);
            } else if ("GET".equals(method) && path.startsWith("/py/")) {
                servePy(out, path.substring(4));
            } else if ("GET".equals(method) && (path.equals("/") || path.isEmpty())) {
                send(out, 200, "text/html; charset=utf-8", page(null));
            } else {
                send(out, 404, "text/plain; charset=utf-8", "not found");
            }
        } catch (Exception e) {
            // 手机提前断开、超时之类：什么都不用做
        }
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String raw = headers.get("content-length");
        if (raw == null) return new byte[0];
        int length;
        try {
            length = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return new byte[0];
        }
        if (length <= 0) return new byte[0];
        if (length > MAX_BODY) length = MAX_BODY;
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) break;
            read += n;
        }
        if (read == length) return body;
        byte[] shorter = new byte[read];
        System.arraycopy(body, 0, shorter, 0, read);
        return shorter;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
        }
        if (c < 0 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    // ---- 三个动作 ---------------------------------------------------------------------------

    /** 手机点了「应用」：填了地址就加进自定义源并切过去，否则切到选中的那一条。 */
    private void applyForm(OutputStream out, Map<String, String> form) throws IOException {
        String url = trim(form.get("url"));
        String name = trim(form.get("name"));
        // 手机上多半不会敲 http://，补上去，别让一个地址因为少了协议头被判成无效。
        if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        if (!url.isEmpty()) {
            FilmSources.addCustom(app, name, url);
        } else {
            int pick = parseInt(form.get("pick"), 0);
            List<String[]> sources = FilmSources.all(app);
            if (pick < 0 || pick >= sources.size()) {
                send(out, 200, "text/html; charset=utf-8",
                        page("没有找到这个源，请回上一页重新选择。"));
                return;
            }
            name = sources.get(pick)[0];
            url = sources.get(pick)[1];
        }
        final String targetName = name.isEmpty() ? FilmSources.hostOf(url) : name;
        final String targetUrl = url;
        // 电视端切换要拉起影视 App、等它的小服务起来，十几秒，先给手机回执再后台做。
        main.post(() -> FilmSourceSwitcher.switchTo(app, targetName, targetUrl,
                (ok, message) -> notifyListener(message)));
        send(out, 200, "text/html; charset=utf-8",
                page("已提交，电视正在切到「" + targetName + "」，大约十几秒。"));
    }

    /** 手机上传了一个 .py：存到 files/py/、置顶为最高优先级，并自动让影视 App 切到它。 */
    private void receiveUpload(OutputStream out, Map<String, String> headers, byte[] body) throws IOException {
        FormBody.FilePart part = FormBody.multipartFile(body, headers.get("content-type"));
        if (part == null) {
            send(out, 200, "text/html; charset=utf-8", page("没有收到文件，请重新选择。"));
            return;
        }
        File saved = FilmSources.savePy(app, part.name, part.data);
        if (saved == null) {
            send(out, 200, "text/html; charset=utf-8", page("保存失败，电视上可能没有空间。"));
            return;
        }
        // 上传的 py 排在内置源前面，最新上传的永远第一。
        FilmSources.setPriorityPy(app, saved.getName());
        String label = FilmSources.pyLabel(saved.getName());
        String host = localHost();
        String url = host == null ? "" : FilmSources.pyUrl(host, port, saved.getName());
        notifyListener("手机上传了 py：" + saved.getName() + "，已置顶为最高优先级");
        if (!url.isEmpty()) {
            final String targetName = label;
            final String targetUrl = url;
            // 和 /apply 一样：先给手机回执，再在电视端拉起影视 App 换源（约十几秒）。
            main.post(() -> FilmSourceSwitcher.switchTo(app, targetName, targetUrl,
                    (ok, message) -> notifyListener(message)));
        }
        send(out, 200, "text/html; charset=utf-8", page(
                "已收到 " + saved.getName() + "\n已置顶为最高优先级"
                        + (url.isEmpty() ? "，但当前没有局域网地址，无法自动切换"
                        : "\n地址：" + url + "\n电视正在自动切换到这个 py 源（约十几秒）")));
    }

    /** 影视 App 来取 py：files/py/ 里的文件按名字原样返回。 */
    private void servePy(OutputStream out, String encodedName) throws IOException {
        String name;
        try {
            name = URLDecoder.decode(encodedName, "UTF-8");
        } catch (Exception e) {
            name = encodedName;
        }
        File dir = FilmSources.pyDir(app);
        File file = new File(dir, name);
        // 只认 py 目录下的直接子文件，防止 ../../ 之类跳出去
        if (!file.isFile() || !file.getParentFile().equals(dir)) {
            send(out, 404, "text/plain; charset=utf-8", "not found");
            return;
        }
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        send(out, 200, "text/plain; charset=utf-8", bytes);
    }

    private void notifyListener(String message) {
        Listener target = listener;
        if (target != null) main.post(() -> target.onRemoteChange(message));
    }

    // ---- 页面 -------------------------------------------------------------------------------

    private String page(String banner) {
        String host = localHost();
        String current = FilmSourceSwitcher.currentName(app);
        List<String[]> sources = FilmSources.all(app);
        List<File> pys = FilmSources.pyFiles(app);

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            String[] source = sources.get(i);
            rows.append("<label class=\"row\"><input type=\"radio\" name=\"pick\" value=\"").append(i)
                    .append(i == 0 ? "\" checked>" : "\">")
                    .append("<span class=\"name\">").append(esc(source[0])).append("</span>")
                    .append("<span class=\"url\">").append(esc(source[1])).append("</span></label>");
        }
        StringBuilder pyList = new StringBuilder();
        if (pys.isEmpty()) {
            pyList.append("<p class=\"hint\">还没有上传过 py 文件。</p>");
        } else {
            for (File py : pys) {
                String url = host == null ? "" : FilmSources.pyUrl(host, port, py.getName());
                pyList.append("<div class=\"py\"><span class=\"name\">").append(esc(py.getName()))
                        .append("</span><span class=\"url\">").append(esc(url)).append("</span></div>");
            }
        }
        String bannerHtml = banner == null ? ""
                : "<div class=\"banner\">" + esc(banner).replace("\n", "<br>") + "</div>";

        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>数据源配置</title><style>"
                + "*{box-sizing:border-box}body{margin:0;padding:20px 16px 40px;background:#0b1016;color:#e8eef4;"
                + "font:16px/1.5 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif}"
                + "h1{font-size:21px;margin:0 0 4px}h2{font-size:16px;margin:26px 0 10px;color:#c8d4de}"
                + ".sub{color:#8b9aa8;font-size:13px;margin:0 0 8px}"
                + ".banner{background:#17301f;border:1px solid #2c6b45;color:#b8f0cd;padding:12px 14px;"
                + "border-radius:12px;margin:14px 0;font-size:14px;white-space:normal}"
                + ".row{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:11px 12px;margin:8px 0;"
                + "background:#141b23;border:1px solid #202b36;border-radius:12px}"
                + ".row input{width:18px;height:18px;margin:0}"
                + ".name{font-weight:600}.url{flex:1 0 100%;color:#7f8d9b;font-size:12px;word-break:break-all}"
                + "input[type=text]{width:100%;padding:12px;margin:8px 0;border-radius:12px;border:1px solid #25313d;"
                + "background:#111820;color:#e8eef4;font-size:16px}"
                + "input[type=file]{width:100%;padding:12px;border-radius:12px;border:1px dashed #2c3a48;"
                + "background:#111820;color:#e8eef4;font-size:14px}"
                + "button{width:100%;padding:14px;margin-top:12px;border:0;border-radius:14px;font-size:17px;"
                + "font-weight:600;color:#06111c;background:linear-gradient(180deg,#ffd79a,#e8b563)}"
                + ".py{display:flex;flex-wrap:wrap;gap:6px;padding:10px 12px;margin:8px 0;background:#121a22;"
                + "border:1px solid #1f2a35;border-radius:12px}"
                + ".hint{color:#7f8d9b;font-size:13px}"
                + "</style></head><body>"
                + "<h1>数据源配置</h1>"
                + "<p class=\"sub\">吾爱TV桌面 · 当前源：" + esc(current == null ? "未切换过" : current) + "</p>"
                + bannerHtml
                + "<form method=\"post\" action=\"/apply\">"
                + "<h2>内置与已添加的源</h2>" + rows
                + "<h2>添加自定义源</h2>"
                + "<input type=\"text\" name=\"name\" placeholder=\"名称（可不填，默认用域名）\">"
                + "<input type=\"text\" name=\"url\" placeholder=\"配置地址，http:// 或 https:// 开头\" "
                + "inputmode=\"url\" autocapitalize=\"off\" autocomplete=\"off\">"
                + "<button type=\"submit\">应用，并让电视切到这个源</button>"
                + "</form>"
                + "<form method=\"post\" action=\"/upload\" enctype=\"multipart/form-data\">"
                + "<h2>上传 py 文件</h2>"
                + "<input type=\"file\" name=\"file\" accept=\".py\" onchange=\""
                + "var b=this.form.querySelector('button');b.textContent=this.files.length?'上传 '+this.files[0].name:'上传 py 文件'\">"
                + "<button type=\"submit\">上传 py 文件</button>"
                + "<p class=\"hint\">上传后自动置顶为最高优先级，电视会立刻切换到这个 py 源；"
                + "文件由桌面常驻服务提供，设置页关掉也仍然可用。</p>"
                + "</form>"
                + "<h2>已上传的 py</h2>" + pyList
                + "</body></html>";
    }

    // ---- 小工具 -----------------------------------------------------------------------------

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String esc(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void send(OutputStream out, int code, String contentType, String body) throws IOException {
        send(out, code, contentType, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String contentType, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Not Found") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
