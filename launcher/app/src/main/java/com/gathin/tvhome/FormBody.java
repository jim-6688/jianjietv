package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 手机网页提交上来的请求体解析：urlencoded 表单，以及 multipart 里那一个文件。
 *
 * <p>刻意写成不依赖 Android，这样能在 {@code src/test} 的纯 JVM 单测里用浏览器真实发出来的样子跑一遍
 * （换源表单里的中文、带中文文件名的 .py）。
 */
final class FormBody {

    /** multipart 里解析出来的文件。 */
    static final class FilePart {
        final String name;
        final byte[] data;

        FilePart(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    private FormBody() {
    }

    /** {@code a=1&b=%E4%B8%AD} → {a:1, b:中}；坏字段跳过，不抛。 */
    static Map<String, String> urlEncoded(byte[] body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null || body.length == 0) return map;
        String raw = new String(body, StandardCharsets.UTF_8);
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                map.put(java.net.URLDecoder.decode(key, "UTF-8"), java.net.URLDecoder.decode(value, "UTF-8"));
            } catch (Exception ignored) {
                // 编码坏掉的字段直接跳过
            }
        }
        return map;
    }

    /** 从 multipart 请求体里取第一个带文件名的部分；没有就返回 null。 */
    static FilePart multipartFile(byte[] body, String contentType) {
        if (body == null || body.length == 0 || contentType == null) return null;
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (!lower.contains("multipart/form-data")) return null;
        int marker = lower.indexOf("boundary=");
        if (marker < 0) return null;
        String boundary = contentType.substring(marker + "boundary=".length()).trim();
        if (boundary.startsWith("\"")) boundary = boundary.substring(1);
        int quote = boundary.indexOf('"');
        if (quote >= 0) boundary = boundary.substring(0, quote);
        if (boundary.isEmpty()) return null;

        // 一个字节对一个字符：文件内容原样保留，不会被字符集改写。
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        for (String part : raw.split("--" + Pattern.quote(boundary))) {
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) continue;
            String head = part.substring(0, headerEnd);
            if (!head.contains("filename=")) continue;
            String fileName = between(head, "filename=\"", "\"");
            if (fileName == null || fileName.isEmpty()) continue;
            String data = part.substring(headerEnd + 4);
            if (data.endsWith("\r\n")) data = data.substring(0, data.length() - 2);
            return new FilePart(decodeName(fileName), data.getBytes(StandardCharsets.ISO_8859_1));
        }
        return null;
    }

    /** 浏览器发来的文件名是 UTF-8 字节，被当成 ISO-8859-1 读进来了，这里先转回去，再处理百分号编码。 */
    static String decodeName(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
        String name = new String(bytes, StandardCharsets.UTF_8);
        // 本来就不是 UTF-8 字节（已经是正常字符串）时，转换只会得到替换符，保持原样。
        if (name.indexOf('\uFFFD') >= 0) name = raw;
        if (name.contains("%")) {
            try {
                return java.net.URLDecoder.decode(name, "UTF-8");
            } catch (Exception ignored) {
                // 不是百分号编码，原样用
            }
        }
        return name;
    }

    private static String between(String text, String open, String close) {
        int from = text.indexOf(open);
        if (from < 0) return null;
        from += open.length();
        int to = text.indexOf(close, from);
        return to < 0 ? null : text.substring(from, to);
    }
}
