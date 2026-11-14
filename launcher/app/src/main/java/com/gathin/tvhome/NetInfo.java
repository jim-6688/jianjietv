package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 顶栏时间旁的网络信息。默认只显示入口宽带运营商名称，桌面设置里的「显示 IP」打开后
 * 才换成内网 IP / 外网 IP 两行；公网出口 IP 与运营商走免 Key 接口异步取并缓存半小时。
 */
public final class NetInfo {

    private static final String PREF = "net_info";
    private static final String KEY_WAN = "wan";
    private static final String KEY_ISP = "isp";
    private static final String KEY_TIME = "time";
    private static final long WAN_TTL_MS = 30 * 60 * 1000L;
    /** 桌面设置「显示 IP」开关的 key，存在 Config.SETTINGS_NAME 里。 */
    static final String SHOW_IP_KEY = "show_ip";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean fetching;

    /** 国内可用的免 Key「回显出口 IP」接口，按可达性依次尝试；响应里抓第一个公网 IPv4。 */
    private static final String[] ENDPOINTS = {
            "https://myip.ipip.net",
            "https://www.taobao.com/help/getip.php",
            "https://api.ipify.org",
            "http://pv.sohu.com/cityjson?ie=utf-8"
    };
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");

    // 香槟金配色，替换原来的白/蓝，配合衬线字体的高端感。
    private static final int LABEL = 0xFFA99570;
    private static final int VALUE = 0xFFEAD9B2;
    private static final int TYPE = 0xFFD4B978;

    private NetInfo() {
    }

    interface Callback {
        void onWan(String ip, String isp);
    }

    /** 立即按当前开关填内容；缓存过期或运营商还没解析出来时，后台取一次再回填。 */
    static void bind(Context context, TextView target) {
        if (target == null) return;
        SharedPreferences net = prefs(context);
        boolean showIp = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SHOW_IP_KEY, false);
        String local = localIp();
        String type = linkType(context);
        String wan = cachedWan(net);
        String isp = cachedIsp(net);
        render(target, showIp, local, wan == null ? "…" : wan, isp, type);
        if (wan == null || (!showIp && isp == null)) {
            fetchWan(context, (wIp, wIsp) -> {
                if (!target.isAttachedToWindow()) return;
                render(target, showIp, localIp(), wIp == null ? "获取失败" : wIp, wIsp, linkType(context));
            });
        }
    }

    private static void render(TextView target, boolean showIp, String local, String wan, String isp, String type) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (showIp) {
            b.append(color("内网 ", LABEL)).append(color(local, VALUE));
            b.append('\n');
            b.append(color("外网 ", LABEL)).append(color(wan, VALUE));
            b.append(color(" · ", LABEL));
            b.append(color(type, TYPE));
        } else {
            // 默认不把 IP 摆在桌面上：只显示入口宽带运营商；没查到就只留连接类型。
            if (isp == null || isp.isEmpty()) {
                b.append(color(type, VALUE));
            } else {
                b.append(color(isp, VALUE));
                b.append(color(" · ", LABEL));
                b.append(color(type, TYPE));
            }
        }
        target.setText(b);
    }

    private static SpannableString color(CharSequence text, int argb) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new ForegroundColorSpan(argb), 0, s.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** 优先 wlan0 / eth0 的 IPv4；没有就取第一个非回环地址。 */
    static String localIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                String name = nic.getName();
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    if (name != null && (name.startsWith("wlan") || name.startsWith("eth"))) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {
            // 没有网络接口权限时退回占位符
        }
        return fallback != null ? fallback : "—";
    }

    static String linkType(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "未知";
        android.net.NetworkInfo active = cm.getActiveNetworkInfo();
        if (active == null || !active.isConnected()) return "未连接";
        String name = typeName(active.getType());
        // VPN 接管时活动网络是 VPN，补一层看底层到底是 Wi-Fi 还是有线。
        if (active.getType() == ConnectivityManager.TYPE_VPN) {
            for (android.net.NetworkInfo info : cm.getAllNetworkInfo()) {
                if (info == null || !info.isConnected()) continue;
                if (info.getType() == ConnectivityManager.TYPE_WIFI
                        || info.getType() == ConnectivityManager.TYPE_ETHERNET) {
                    return typeName(info.getType()) + " · VPN";
                }
            }
        }
        return name;
    }

    private static String typeName(int type) {
        switch (type) {
            case ConnectivityManager.TYPE_WIFI: return "Wi-Fi";
            case ConnectivityManager.TYPE_ETHERNET: return "以太网";
            case ConnectivityManager.TYPE_MOBILE: return "移动网络";
            case ConnectivityManager.TYPE_VPN: return "VPN";
            default: return "已连接";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static boolean fresh(SharedPreferences p) {
        return System.currentTimeMillis() - p.getLong(KEY_TIME, 0) <= WAN_TTL_MS;
    }

    private static String cachedWan(SharedPreferences p) {
        return fresh(p) ? p.getString(KEY_WAN, null) : null;
    }

    private static String cachedIsp(SharedPreferences p) {
        return fresh(p) ? p.getString(KEY_ISP, null) : null;
    }

    /** 连接类型或网络切换后清掉缓存，下一次 bind 会重新取出口 IP 与运营商。 */
    static void invalidate(Context context) {
        prefs(context).edit().remove(KEY_WAN).remove(KEY_ISP).apply();
    }

    private static void fetchWan(Context context, Callback callback) {
        if (fetching) return;
        fetching = true;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            Wan found = null;
            for (int i = 0; i < ENDPOINTS.length; i++) {
                Wan w = query(ENDPOINTS[i]);
                if (w == null) continue;
                if (found == null) found = w;
                if (w.isp != null) {          // 优先用带运营商信息的那份响应
                    found = w;
                    break;
                }
                if (i >= 1) break;            // 前两个都没写运营商就不再逐个试，别在启动时连打四个接口
            }
            if (found != null) {
                SharedPreferences.Editor e = prefs(app).edit()
                        .putString(KEY_WAN, found.ip).putLong(KEY_TIME, System.currentTimeMillis());
                if (found.isp != null) e.putString(KEY_ISP, found.isp);
                else e.remove(KEY_ISP);
                e.apply();
            }
            Wan result = found;
            fetching = false;
            MAIN.post(() -> callback.onWan(result == null ? null : result.ip,
                    result == null ? null : result.isp));
        }, "netinfo-wan").start();
    }

    private static Wan query(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (TV)");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines++ < 20) body.append(line).append('\n');
            reader.close();
            String ip = firstPublicIp(body.toString());
            return ip == null ? null : new Wan(ip, IspNames.from(body.toString()));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final class Wan {
        final String ip;
        final String isp;

        Wan(String ip, String isp) {
            this.ip = ip;
            this.isp = isp;
        }
    }

    private static String firstPublicIp(String body) {
        Matcher m = IPV4.matcher(body);
        while (m.find()) {
            String ip = m.group();
            if (isPublic(ip)) return ip;
        }
        return null;
    }

    private static boolean isPublic(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                n[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (n[i] < 0 || n[i] > 255) return false;
        }
        if (n[0] == 0 || n[0] == 10 || n[0] == 127 || n[0] >= 224) return false;
        if (n[0] == 192 && n[1] == 168) return false;
        if (n[0] == 172 && n[1] >= 16 && n[1] <= 31) return false;
        if (n[0] == 169 && n[1] == 254) return false;
        return !(n[0] == 255 && n[1] == 255 && n[2] == 255 && n[3] == 255);
    }
}
