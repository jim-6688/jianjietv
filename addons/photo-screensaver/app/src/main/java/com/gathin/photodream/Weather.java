package com.gathin.photodream;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

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
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The weather now, for the weather option. Prefers 中国天气网 (China Weather Network): when the app's files
 * dir holds a weather.txt with a city id, it reads the current conditions from d1.weather.com.cn, which needs
 * no account and no key. A key may be added on the same line to use the official open-data API instead.
 * Without a city id it falls back to Open-Meteo with the TV's coarse location, so the feature still works out
 * of the box. Only called while the option is on; nothing else goes out.
 */
final class Weather {
    static final long REFRESH_MS = 30 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private Weather() {}

    static boolean permitted(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether the TV's location services are on (Settings > Privacy > Location on Google TV). */
    static boolean locationOn(Context context) {
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return !lm.getProviders(true).isEmpty();
    }

    /** The newest location the TV knows, or a fresh one; {@code done} gets null when there is none. */
    @SuppressWarnings("deprecation")
    static void locate(Context context, Consumer<Location> done) {
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm == null || !permitted(context) || !locationOn(context)) {
            done.accept(null);
            return;
        }
        try {
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) {
                done.accept(best);
                return;
            }
            if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                done.accept(null);
                return;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, context.getMainExecutor(),
                        done::accept);
            } else {
                // All four methods spelled out: before Android 11 they have no defaults.
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                    @Override public void onLocationChanged(Location location) { done.accept(location); }
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
                    @Override public void onProviderEnabled(String provider) { }
                    @Override public void onProviderDisabled(String provider) { }
                }, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException e) {
            done.accept(null);
        }
    }

    /**
     * "北京 12°  \u2600" for the place, blocking: call it off the main thread.
     * {@code latitude}/{@code longitude} may be NaN when the TV has no location: 中国天气网 does not need one,
     * only the Open-Meteo fallback does.
     */
    static String now(Context context, double latitude, double longitude)
            throws IOException, JSONException {
        // Prefer 中国天气网 when a city id is configured; it is domestic and needs no key.
        String cn = nowCn(context);
        if (cn != null) return cn;
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw new IOException("no city configured and no location");
        }
        // Two decimals is about 1 km: enough for the weather, no more of where the TV is than that.
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f"
                + "&longitude=%.2f&current=temperature_2m,weather_code,is_day", latitude, longitude);
        JSONObject current = get(url).getJSONObject("current");
        int temp = (int) Math.round(current.getDouble("temperature_2m"));
        return temp + "°  " + symbol(current.getInt("weather_code"), current.optInt("is_day", 1) == 1);
    }

    /** Whether a 中国天气网 city id sits in weather.txt, so the weather can show without any location. */
    static boolean hasCity(Context context) {
        return cityId(context) != null;
    }

    /** The city id from weather.txt, or null when there is none (or it is not an id). */
    private static String cityId(Context context) {
        String line = readLine(context, "weather.txt");
        if (line == null) return null;
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || !parts[0].matches("\\d{6,12}")) return null;
        return parts[0];
    }

    /**
     * 中国天气网实况. Reads weather.txt from the app's files dir: one line, "&lt;cityid&gt;" or
     * "&lt;cityid&gt; &lt;key&gt;". With a key it uses the official open-data API; without one it uses the
     * keyless current-conditions endpoint, which only needs a Referer header. Returns null when nothing is
     * configured or the request fails, so the caller can fall back to Open-Meteo.
     * Find the city id with tools/weather_city.py, then push the file to the TV:
     *   TV_HOST=&lt;ip&gt; tools/tv.py "mkdir -p /sdcard/Android/data/com.gathin.photodream/files"
     *   TV_HOST=&lt;ip&gt; tools/tv.py "echo 101010100 &gt; .../files/weather.txt"
     */
    private static String nowCn(Context context) {
        String line = readLine(context, "weather.txt");
        if (line == null) return null;
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || !parts[0].matches("\\d{6,12}")) return null;
        String id = parts[0];
        String key = parts.length > 1 ? parts[1] : "";
        // The official open-data API when a key was configured.
        if (!key.isEmpty()) {
            try {
                JSONObject sk = skObject(getText("https://api.weather.com.cn/sk?cityid=" + id + "&key=" + key));
                return format(sk);
            } catch (Exception ignored) {
                // No key, no network, or an error body: try the keyless endpoint below.
            }
        }
        try {
            JSONObject sk = skObject(getText("https://d1.weather.com.cn/sk_2d/" + id + ".html"));
            return format(sk);
        } catch (Exception e) {
            return null;   // offline or a bad id: let the caller fall back
        }
    }

    /** "北京 12°  \u2600" from a 中国天气网 reading. */
    private static String format(JSONObject sk) throws JSONException {
        Object raw = sk.opt("temp");
        if (raw == null) throw new JSONException("no temperature in reply");
        int temp = (int) Math.round(Double.parseDouble(String.valueOf(raw).trim()));
        String city = sk.optString("cityname", sk.optString("city", ""));
        String glyph = symbolCn(sk.optString("weather", ""));
        return (city.isEmpty() ? "" : city + " ") + temp + "°  " + glyph;
    }

    /**
     * The reading object out of a 中国天气网 reply. The keyless endpoint answers "var dataSK={...};", the
     * official API answers {"sk":{...}} or a JSONP array; accept all of them.
     */
    private static JSONObject skObject(String body) throws JSONException {
        String json = extractJson(body);
        if (json.isEmpty()) throw new JSONException("no JSON in reply");
        JSONObject o = new JSONObject(json);
        if (o.has("sk")) return o.getJSONObject("sk");
        if (o.has("data")) {
            JSONArray arr = o.getJSONArray("data");
            if (arr.length() > 0) {
                JSONObject first = arr.getJSONObject(0);
                return first.has("sk") ? first.getJSONObject("sk") : first;
            }
        }
        return o;   // already the reading itself
    }

    /** The {...} or [...] part of a reply that may be wrapped in JavaScript. */
    private static String extractJson(String body) {
        int a = body.indexOf('{');
        int b = body.lastIndexOf('}');
        if (a >= 0 && b > a) return body.substring(a, b + 1);
        a = body.indexOf('[');
        b = body.lastIndexOf(']');
        if (a >= 0 && b > a) return body.substring(a, b + 1);
        return "";
    }

    /** Map a Chinese weather word to a glyph every TV font has. */
    static String symbolCn(String text) {
        if (text == null || text.isEmpty()) return "\u2601";
        String t = text.replace("转", " ").trim().split("\\s+")[0];
        if (t.contains("晴")) return "\u2600";                         // sun
        if (t.contains("多云") || t.contains("少云")) return "\u26C5"; // sun behind cloud
        if (t.contains("阴")) return "\u2601";                         // cloud
        if (t.contains("雷")) return "\u26A1";                         // thunder
        if (t.contains("雪") || t.contains("冰")) return "\u2744";     // snow
        if (t.contains("雨")) return "\u2602";                         // rain
        if (t.contains("雾") || t.contains("霾") || t.contains("沙") || t.contains("尘")) return "\u2601";
        return "\u2601";                                               // default: cloud
    }
    /**
     * 中国天气网 3 天预报："今天 ☀ 12°/-3°  明天 ⛅ 8°/-1°  后天 🌧 6°/0°"。
     * 读取 weather.txt 的城市编码，调用 15 天预报接口取前 3 天；失败返回 null，调用方可回退 Open-Meteo。
     * 与 now() 一样，只在天气开启时调用，不会有其他网络请求。
     */
    static String forecastCn(Context context) {
        String id = cityId(context);
        if (id == null) return null;
        String body;
        try {
            body = getText("https://d1.weather.com.cn/weatherforecast/" + id + ".html");
        } catch (Exception e) {
            try {
                body = getText("http://d1.weather.com.cn/weatherforecast/" + id + ".html");
            } catch (Exception ignored) {
                return null;   // 离线或接口不可用：让调用方回退
            }
        }
        try {
            String json = extractJson(body);
            if (json.isEmpty()) return null;
            JSONObject f = new JSONObject(json);
            JSONArray days = f.getJSONObject("f").getJSONArray("f1");
            String[] when = {"今天", "明天", "后天"};
            int n = Math.min(3, days.length());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                JSONObject d = days.getJSONObject(i);
                String glyph = symbolCode(d.optString("fa", ""));   // 白天天气代码
                String hi = d.optString("fc", "--");                // 白天最高温
                String lo = d.optString("fd", "--");                // 夜间最低温
                if (hi.startsWith("-")) hi = "−" + hi.substring(1);
                if (lo.startsWith("-")) lo = "−" + lo.substring(1);
                if (i > 0) sb.append("   ");
                sb.append(when[i]).append(' ').append(glyph).append(' ')
                        .append(hi).append('°').append('/').append(lo).append('°');
            }
            return sb.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /** 中国天气网天气现象代码（fa/fb 字段，"01"=晴）映射到每个 TV 字体都有的字形。 */
    static String symbolCode(String code) {
        if (code == null) return "\u2601";
        switch (code) {
            case "00": case "01": return "\u2600";                 // 晴
            case "02": case "03": return "\u26C5";                 // 多云 / 少云
            case "04": case "30": case "31": return "\u2601";      // 阴
            case "05": case "06": case "07": case "08": return "\u2602"; // 小雨 / 中雨 / 大雨 / 阵雨
            case "09": case "10": case "11": case "12": case "13": case "14": return "\u2602"; // 暴雨等
            case "15": case "16": case "17": case "18": case "19": return "\u2744"; // 雪
            case "20": case "21": case "22": case "23": case "24": case "25": case "53": return "\u2601"; // 雾 / 霾 / 沙尘
            case "26": case "27": case "28": case "29": return "\u26A1"; // 雷阵雨
            default: return "\u2601";                               // 默认：云
        }
    }

    private static String readLine(Context context, String name) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, name);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /** A GET that returns the body as text. Sends the Referer 中国天气网 requires. */
    private static String getText(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Referer", "http://www.weather.com.cn/");
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (in.read(buffer)) != -1; ) {
                out.write(buffer, 0, n);
                if (out.size() > 64 * 1024) throw new IOException("reply too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /** A plain symbol for a WMO weather code, from characters every TV font has. */
    static String symbol(int code, boolean day) {
        if (code == 0 || code == 1) return day ? "\u2600" : "\u263E";   // sun, moon
        if (code == 2) return "\u26C5";                                  // sun behind cloud
        if (code == 3 || code == 45 || code == 48) return "\u2601";      // cloud (fog too)
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "\u2744";   // snow
        if (code >= 95) return "\u26A1";                                 // thunder
        return "\u2602";                                                 // drizzle, rain, showers
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (in.read(buffer)) != -1; ) {
                out.write(buffer, 0, n);
                if (out.size() > 64 * 1024) throw new IOException("reply too large");
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }
}
