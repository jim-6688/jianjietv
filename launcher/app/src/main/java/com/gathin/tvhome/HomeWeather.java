package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 领先TV 首页天气：中国天气网实况 + 3 天预报，免 Key（只需把城市编码写入本应用
 * files/weather.txt，例如 "101210101"）。与 photo-screensaver 的 Weather 同源逻辑，
 * 但独立成类，供桌面首页大标签调用。只在首页显示期间按需取数，由调用方在后台线程调用，
 * 不会在主线程联网。取不到时返回 null，调用方隐藏标签即可。
 */
final class HomeWeather {
    private static final String TAG = "HomeWeather";
    private static final long TIMEOUT_MS = 8_000;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private HomeWeather() { }

    /** 实况的结构化结果，供首页大卡片分块填充（城市、温度、图标、天气文字）。 */
    static final class Snapshot {
        final String city;
        final int temp;
        final String glyph;
        final String condition;

        Snapshot(String city, int temp, String glyph, String condition) {
            this.city = city;
            this.temp = temp;
            this.glyph = glyph;
            this.condition = condition;
        }
    }

    /** 读取本应用 files/weather.txt 里的城市编码；无则返回 null。 */
    static String readCity(Context context) {
        File f = new File(context.getExternalFilesDir(null), "weather.txt");
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (line == null) return null;
            String[] parts = line.trim().split("\\s+");
            return parts.length > 0 && parts[0].matches("\\d{6,12}") ? parts[0] : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 实况的结构化结果（城市 / 温度 / 图标 / 天气文字）。取不到返回 null。 */
    static Snapshot snapshot(Context context) {
        String id = readCity(context);
        if (id == null) return null;
        try {
            JSONObject sk = skObject(get("https://d1.weather.com.cn/sk_2d/" + id + ".html"));
            Object raw = sk.opt("temp");
            if (raw == null) throw new JSONException("no temp");
            int temp = (int) Math.round(Double.parseDouble(String.valueOf(raw).trim()));
            String city = sk.optString("cityname", sk.optString("city", ""));
            String condition = sk.optString("weather", "");
            return new Snapshot(city, temp, glyphCn(condition), condition);
        } catch (Exception e) {
            Log.w(TAG, "snapshot() failed for city " + id + ": " + e);
            return null;
        }
    }

    /** 3 天预报里的一天：标签（今天/明天/后天）、图标、最高温、最低温。 */
    static final class Day {
        final String label;
        final String glyph;
        final String hi;
        final String lo;

        Day(String label, String glyph, String hi, String lo) {
            this.label = label;
            this.glyph = glyph;
            this.hi = hi;
            this.lo = lo;
        }

        /** "今天  ☀  12°/−3°"，首页 3 天预报每行一个。 */
        String line() {
            return label + "  " + glyph + "  " + hi + "°/" + lo + "°";
        }
    }

    /** 实况："北京 12°  ☀"。取不到返回 null。 */
    static String now(Context context) {
        Snapshot s = snapshot(context);
        if (s == null) return null;
        return (s.city.isEmpty() ? "" : s.city + " ") + s.temp + "°  " + s.glyph;
    }

    /**
     * 3 天预报的结构化结果（今天/明天/后天），首页按行显示。取不到返回 null。
     *
     * <p>数据来自免 Key 的 itboy 天气接口（同一份 cityCode，返回 15 天的高低温与天气现象）。
     * 中国天气网原来的 {@code weatherforecast/<id>.html} 已经改成返回网页而不是数据，故不再使用。</p>
     */
    static List<Day> days(Context context) {
        String id = readCity(context);
        if (id == null) return null;
        try {
            JSONObject root = new JSONObject(get("http://t.weather.itboy.net/api/weather/city/" + id));
            JSONArray arr = root.getJSONObject("data").getJSONArray("forecast");
            String[] when = {"今天", "明天", "后天"};
            List<Day> out = new ArrayList<>();
            for (int i = 0; i < Math.min(when.length, arr.length()); i++) {
                JSONObject d = arr.getJSONObject(i);
                String type = d.optString("type", "");
                out.add(new Day(when[i], glyphCn(type),
                        num(d.optString("high", "")), num(d.optString("low", ""))));
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            Log.w(TAG, "days() failed for city " + id + ": " + e);
            return null;
        }
    }

    /** "高温 14℃" → "14"，"低温 -3℃" → "−3"，取不到数字返回 "--"。 */
    private static String num(String s) {
        if (s == null) return "--";
        String digits = s.replaceAll("[^0-9-]", "");
        if (digits.isEmpty() || digits.equals("-")) return "--";
        return digits.startsWith("-") ? "−" + digits.substring(1) : digits;
    }

    /** 3 天预报："今天 ☀ 12°/-3°  明天 ⛅ 8°/-1°  后天 🌧 6°/0°"。取不到返回 null。 */
    static String forecast(Context context) {
        List<Day> days = days(context);
        if (days == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Day d : days) {
            if (sb.length() > 0) sb.append("   ");
            sb.append(d.label).append(' ').append(d.glyph).append(' ')
                    .append(d.hi).append('°').append('/').append(d.lo).append('°');
        }
        return sb.toString();
    }

    private static String norm(String s) {
        if (s == null || s.isEmpty()) return "--";
        if (s.startsWith("-")) return "−" + s.substring(1);
        return s;
    }

    private static JSONObject skObject(String body) throws JSONException {
        String json = extractJson(body);
        if (json.isEmpty()) throw new JSONException("no JSON");
        JSONObject o = new JSONObject(json);
        if (o.has("sk")) return o.getJSONObject("sk");
        if (o.has("data")) {
            JSONArray arr = o.getJSONArray("data");
            if (arr.length() > 0) {
                JSONObject first = arr.getJSONObject(0);
                return first.has("sk") ? first.getJSONObject("sk") : first;
            }
        }
        return o;
    }

    private static String extractJson(String body) {
        int a = body.indexOf('{'), b = body.lastIndexOf('}');
        if (a >= 0 && b > a) return body.substring(a, b + 1);
        a = body.indexOf('['); b = body.lastIndexOf(']');
        if (a >= 0 && b > a) return body.substring(a, b + 1);
        return "";
    }

    /**
     * 天气文字 → 图标。统一带上 U+FE0F 变体选择符，让系统用彩色 emoji 字体渲染：
     * 一是真的彩色，二是 emoji 的字宽是方正的，不会像文本符号那样笔画压到旁边的温度上。
     */
    private static String glyphCn(String text) {
        if (text == null || text.isEmpty()) return "☁\uFE0F";
        String t = text.replace("转", " ").trim().split("\\s+")[0];
        if (t.contains("晴")) return "☀\uFE0F";
        if (t.contains("多云") || t.contains("少云")) return "⛅\uFE0F";
        if (t.contains("阴")) return "☁\uFE0F";
        if (t.contains("雷")) return "⚡\uFE0F";
        if (t.contains("雪") || t.contains("冰")) return "❄\uFE0F";
        if (t.contains("雨")) return "🌧\uFE0F";
        if (t.contains("雾") || t.contains("霾") || t.contains("沙") || t.contains("尘")) return "☁\uFE0F";
        return "☁\uFE0F";
    }

    private static String codeGlyph(String code) {
        if (code == null) return "☁\uFE0F";
        switch (code) {
            case "00": case "01": return "☀\uFE0F";
            case "02": case "03": return "⛅\uFE0F";
            case "04": case "30": case "31": return "☁\uFE0F";
            case "05": case "06": case "07": case "08": return "🌧\uFE0F";
            case "09": case "10": case "11": case "12": case "13": case "14": return "🌧\uFE0F";
            case "15": case "16": case "17": case "18": case "19": return "❄\uFE0F";
            case "20": case "21": case "22": case "23": case "24": case "25": case "53": return "☁\uFE0F";
            case "26": case "27": case "28": case "29": return "⚡\uFE0F";
            default: return "☁\uFE0F";
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout((int) TIMEOUT_MS);
        c.setReadTimeout((int) TIMEOUT_MS);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "http://www.weather.com.cn/");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) != -1; ) {
                out.write(buf, 0, n);
                if (out.size() > 64 * 1024) throw new IOException("reply too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
