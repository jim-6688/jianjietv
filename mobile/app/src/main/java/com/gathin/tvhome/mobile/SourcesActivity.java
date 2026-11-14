package com.gathin.tvhome.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 功能 5：手机管理电视数据源。直接对接电视上 ConfigServer 的本地端口
 * （8899 起，避开影视 App 的端口）：GET / 看当前源与内置源，POST /apply 切源，
 * POST /upload 传 py。无需电视端额外改动。
 */
public class SourcesActivity extends ToolActivity {

    private static final int PORT_FROM = 8899;
    private static final int PORT_TO = 8919;

    private EditText nameEdit;
    private EditText urlEdit;
    private TextView pickHint;
    private Uri picked;
    private int port = PORT_FROM;

    @Override
    protected String title() {
        return "5 · TV 数据源";
    }

    @Override
    protected String initialStatus() {
        String host = Prefs.host(this);
        return host.isEmpty() ? "还没有电视，请先去「扫描发现电视」保存一台"
                : "当前电视：" + host + " · 配置服务 8899+";
    }

    @Override
    protected void build() {
        String host = Prefs.host(this);
        if (host.isEmpty()) {
            action(R.drawable.ic_scan, "去扫描发现电视", v -> startActivity(new Intent(this, ScanActivity.class)));
            heading("说明");
            line("这里能查看并切换电视的数据源、上传 py 配置源。");
            line("数据源由电视上的本地配置服务（8899 端口）提供，手机与电视须同一 Wi-Fi。");
            return;
        }
        View form = inflate(R.layout.sources_form);
        nameEdit = form.findViewById(R.id.srcName);
        urlEdit = form.findViewById(R.id.srcUrl);
        pickHint = form.findViewById(R.id.srcPickHint);
        Button apply = form.findViewById(R.id.srcApply);
        Button pick = form.findViewById(R.id.srcPick);
        apply.setOnClickListener(v -> applySource());
        pick.setOnClickListener(v -> pickPy());
        addView(form);
        heading("当前源 / 内置源");
        refresh();
    }

    private View inflate(int id) {
        return android.view.LayoutInflater.from(this).inflate(id, content, false);
    }

    /** 探测配置服务实际端口（8899 起，可能被占用顺延到后面）。 */
    private String baseUrl(String host) {
        return "http://" + host + ":" + port + "/";
    }

    private void refresh() {
        final String host = Prefs.host(this);
        background(() -> {
            int found = -1;
            for (int p = PORT_FROM; p <= PORT_TO; p++) {
                port = p;
                try {
                    String body = get(baseUrl(host), 2500);
                    if (body != null && body.contains("数据源")) {
                        found = p;
                        break;
                    }
                } catch (IOException ignored) {
                }
            }
            if (found < 0) {
                status("连不上电视配置服务（8899）。请确认电视已启动桌面并同一 Wi-Fi。");
                return;
            }
            try {
                String body = get(baseUrl(host), 3000);
                final String current = extract(body, "当前源：([^<\n]+)");
                runOnUiThread(() -> renderSources(current, body));
                status("已连接配置服务 :" + found + " · " + host);
            } catch (IOException e) {
                status("读取失败：" + e.getMessage());
            }
        });
    }

    private void renderSources(String current, String body) {
        clearContent();
        View form = inflate(R.layout.sources_form);
        nameEdit = form.findViewById(R.id.srcName);
        urlEdit = form.findViewById(R.id.srcUrl);
        pickHint = form.findViewById(R.id.srcPickHint);
        form.findViewById(R.id.srcApply).setOnClickListener(v -> applySource());
        form.findViewById(R.id.srcPick).setOnClickListener(v -> pickPy());
        addView(form);

        heading("当前源 / 内置源");
        line("当前源：" + (current == null || current.isEmpty() ? "未切换过" : current.trim()));
        List<String> names = extractAll(body, "<span class=\"name\">([^<]+)</span>");
        if (!names.isEmpty()) {
            heading("可用源");
            for (String n : names) line("· " + n);
        }
    }

    private void applySource() {
        final String host = Prefs.host(this);
        final String name = nameEdit.getText().toString().trim();
        final String url = urlEdit.getText().toString().trim();
        if (url.isEmpty()) {
            status("请先填写配置地址");
            return;
        }
        status("正在提交并让电视切换 …");
        background(() -> {
            try {
                StringBuilder data = new StringBuilder();
                data.append("name=").append(enc(name));
                data.append("&url=").append(enc(url));
                String resp = post(baseUrl(host) + "apply", "application/x-www-form-urlencoded", null, data.toString().getBytes(StandardCharsets.UTF_8));
                status("已提交。电视正在切到这个源（约十几秒）。返回结果：\n" + stripTags(firstLine(resp)));
            } catch (IOException e) {
                status("提交失败：" + e.getMessage());
            }
        });
    }

    private void pickPy() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(Intent.createChooser(intent, "选择 .py 文件"), 1);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK && data != null && data.getData() != null) {
            picked = data.getData();
            String name = picked.getLastPathSegment();
            if (name == null) name = "source.py";
            if (!name.toLowerCase().endsWith(".py")) name = name + ".py";
            pickHint.setText("已选：" + name);
            uploadPy(name);
        }
    }

    private void uploadPy(final String name) {
        final String host = Prefs.host(this);
        status("正在上传 " + name + " …");
        background(() -> {
            try {
                byte[] file = readUri(picked);
                if (file == null) {
                    status("读取文件失败");
                    return;
                }
                String boundary = "----tvhome" + System.currentTimeMillis();
                byte[] body = multipart(boundary, name, file);
                String resp = post(baseUrl(host) + "upload",
                        "multipart/form-data; boundary=" + boundary, null, body);
                status("已上传 " + name + "\n" + stripTags(firstLine(resp)));
            } catch (IOException e) {
                status("上传失败：" + e.getMessage());
            }
        });
    }

    // ---- HTTP 小工具 --------------------------------------------------------------------------

    private String get(String url, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestMethod("GET");
        try {
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String s = new String(readAll(in), StandardCharsets.UTF_8);
            in.close();
            return s;
        } finally {
            c.disconnect();
        }
    }

    private String post(String url, String contentType, String auth, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body);
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = new String(readAll(in), StandardCharsets.UTF_8);
        in.close();
        c.disconnect();
        return s;
    }

    private byte[] multipart(String boundary, String fileName, byte[] file) throws IOException {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: text/x-python\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(file);
        out.write(tail.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private byte[] readUri(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            return readAll(in);
        }
    }

    private String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String extract(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        return m.find() ? m.group(1) : "";
    }

    private List<String> extractAll(String html, String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(html);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").trim();
    }

    private String firstLine(String s) {
        if (s == null) return "";
        int at = s.indexOf('\n');
        return at < 0 ? s : s.substring(0, at);
    }
}
