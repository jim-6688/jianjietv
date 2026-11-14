package com.gathin.tvhome.mobile;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.text.InputType;
import android.widget.EditText;

import com.gathin.tvhome.mobile.adb.Adb;

import java.io.IOException;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 功能 2：发现电视。优先用 mDNS 发现 _adb._tcp（开了网络 ADB 的 Android TV 会广播），
 * 失败再用「深度扫描」按手机当前网段并发探测 5555；命中后做 ADB 握手确认并读型号。
 * 手动填 IP 作为兜底，并给出明确的失败原因。
 */
public class ScanActivity extends ToolActivity {

    private static final int PORT = 5555;
    private static final String ADB_SERVICE = "_adb._tcp";

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final Set<String> seen = new HashSet<>();

    @Override
    protected String title() {
        return "2 · 扫描发现电视";
    }

    @Override
    protected String initialStatus() {
        return "手机与电视须连同一 Wi-Fi；优先 mDNS 自动发现，失败再用网段扫描";
    }

    @Override
    protected void build() {
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);

        String saved = Prefs.host(this);
        if (!saved.isEmpty()) {
            heading("已保存电视：" + saved);
            action(R.drawable.ic_wifi, "连接自检（分步检测 5555 / 握手 / 授权）",
                    v -> diagnose(saved));
        }

        heading("本机网络");
        String info = localIpInfo();
        line(info.isEmpty() ? "未能读取到本机地址；请确认已连上 Wi-Fi"
                : "手机当前地址：\n" + info + "\n（电视必须在这个网段，否则搜不到）");

        action(R.drawable.ic_scan, "自动发现（mDNS，推荐）", v -> discoverMdns());
        action(R.drawable.ic_wifi, "深度扫描整个网段", v -> scan());

        EditText manual = new EditText(this);
        manual.setHint("或手动填电视 IP，如 192.168.6.11");
        manual.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addView(manual);
        action(R.drawable.ic_tv, "连接这个 IP", v -> check(manual.getText().toString().trim()));

        heading("为什么搜不到？");
        line("1）电视要先开启「网络 ADB 调试 / 无线调试」（见第 1 步按品牌引导）。");
        line("2）手机和电视必须连同一个 Wi-Fi（看上面本机网段是否匹配）。");
        line("3）部分电视开了调试但不广播，用「深度扫描」；仍不行就手动填 IP。");
        line("4）首次连接电视会弹「允许调试」，要在电视上勾选「一律允许」再重试。");
    }

    // ── mDNS 自动发现 ──────────────────────────────────────────────
    private void discoverMdns() {
        if (nsd == null) {
            status("本机不支持 mDNS，请用深度扫描");
            return;
        }
        if (discovery != null) {
            status("正在发现中…");
            return;
        }
        seen.clear();
        status("正在 mDNS 发现 " + ADB_SERVICE + " …（约 6 秒）");
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String s, int err) {
                discovery = null;
                status("mDNS 启动失败，请用深度扫描");
            }

            @Override
            public void onStopDiscoveryFailed(String s, int err) {
            }

            @Override
            public void onDiscoveryStarted(String s) {
            }

            @Override
            public void onDiscoveryStopped(String s) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo i, int err) {
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            InetAddress host = resolved.getHost();
                            if (host == null) return;
                            confirmAndAdd(host.getHostAddress(), resolved.getPort());
                        }
                    });
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        try {
            nsd.discoverServices(ADB_SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovery = null;
            status("mDNS 不可用：" + e.getMessage() + "，请用深度扫描");
            return;
        }
        // 6 秒后自动停止，避免一直占用。
        background(() -> {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ignored) {
            }
            stopDiscovery();
            runOnUiThread(() -> status("mDNS 发现结束；没找到就试「深度扫描」或手动 IP"));
        });
    }

    private void stopDiscovery() {
        if (nsd != null && discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (Exception ignored) {
            }
        }
        discovery = null;
    }

    // ── 确认一台并加入列表 ─────────────────────────────────────────
    private void confirmAndAdd(String host, int port) {
        if (host == null) return;
        final String key = host + ":" + port;
        synchronized (seen) {
            if (!seen.add(key)) return;
        }
        background(() -> {
            String desc = Adb.describe(this, host, port, 4000);
            if (desc.isEmpty()) {
                status("发现 " + host + " 但不是 ADB（已忽略）");
                return;
            }
            final String ip = host;
            final String deviceDesc = desc;
            runOnUiThread(() -> action(R.drawable.ic_tv, ip + " · " + deviceDesc,
                    v -> save(ip, deviceDesc)));
            status("发现电视：" + ip + "，点它保存");
        });
    }

    // ── 深度扫描整个网段 ───────────────────────────────────────────
    private void scan() {
        status("正在扫描 …");
        background(() -> {
            String subnet = localSubnet();
            if (subnet == null) {
                status("拿不到本机局域网地址，请手动填 IP");
                return;
            }
            ExecutorService pool = Executors.newFixedThreadPool(48);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 1; i <= 254; i++) {
                final String ip = subnet + i;
                futures.add(pool.submit(() -> Adb.portOpen(ip, PORT, 250) ? ip : null));
            }
            List<String> open = new ArrayList<>();
            for (Future<String> f : futures) {
                try {
                    String ip = f.get();
                    if (ip != null) open.add(ip);
                } catch (Exception ignored) {
                }
            }
            pool.shutdown();
            if (open.isEmpty()) {
                status(subnet + "0/24 没有开放 5555；先按第 1 步在电视上打开网络 ADB");
                return;
            }
            status("发现 " + open.size() + " 个开放 5555，正在确认 ADB …");
            int confirmed = 0;
            for (String ip : open) {
                String desc = Adb.describe(this, ip, PORT, 4000);
                if (desc.isEmpty()) continue;
                confirmed++;
                final String deviceIp = ip;
                final String deviceDesc = desc;
                runOnUiThread(() -> action(R.drawable.ic_tv, deviceIp + " · " + deviceDesc,
                        v -> save(deviceIp, deviceDesc)));
            }
            final int count = confirmed;
            status(count == 0 ? "端口开着但都不是可用的 ADB（可能要电视允许调试）"
                    : "确认 " + count + " 台电视，点选一台保存");
        });
    }

    // ── 手动指定 IP ───────────────────────────────────────────────
    private void check(String ip) {
        if (ip.isEmpty()) {
            status("请先填 IP");
            return;
        }
        status("正在连接 " + ip + " …");
        background(() -> {
            if (!Adb.portOpen(ip, PORT, 800)) {
                status(ip + "：5555 连不上。\n可能：①电视没开网络 ADB ②手机电视不在同一 Wi-Fi ③IP 填错。"
                        + "先回第 1 步开启，或用深度扫描。");
                return;
            }
            String desc = Adb.describe(this, ip, PORT, 5000);
            if (desc.isEmpty()) {
                status(ip + "：端口开放但 ADB 握手失败。\n请在电视上允许调试（勾选「一律允许」）后重试；"
                        + "部分电视调试在等授权弹窗。");
                return;
            }
            save(ip, desc);
        });
    }

    /**
     * 对已保存的电视做分步连接自检，把「连不上」的卡点显式化：
     * 端口不通 / 握手被拒（没点一律允许）/ 超时（在等授权）。
     * 连不上是所有连 TV 功能的总开关，先把它查清，功能才能用。
     */
    private void diagnose(final String host) {
        status("正在对 " + host + " 做连接自检 …");
        background(() -> {
            if (!Adb.portOpen(host, PORT, 1500)) {
                runOnUiThread(() -> status("自检 ① 未通过：端口 5555 不通。\n"
                        + "说明电视没在监听网络 ADB，常见原因：\n"
                        + "· 电视「网络 ADB 调试 / 网络调试」开关没打开（注意「USB 调试」≠「网络调试」）；\n"
                        + "· 创维/酷开 S81 等机型：开了开关也必须「彻底断电再开机」一次，5555 才监听；\n"
                        + "· 手机与电视不在同一 Wi-Fi，或跨了 VLAN / 访客网络。\n"
                        + "先回第 1 步按品牌指引确认，再回来重试。"));
                return;
            }
            runOnUiThread(() -> line("① 端口 5555 开放 ✓"));
            try {
                Adb adb = Adb.connect(this, host, PORT, 8000);
                String model = adb.shell("getprop ro.product.model").trim();
                adb.close();
                runOnUiThread(() -> {
                    line("② ADB 握手 + 授权通过 ✓（" + (model.isEmpty() ? "型号未知" : model) + "）");
                    status("自检通过：" + host + " 连接正常，可正常使用遥控 / 软件管理 / 同步 / 数据源。");
                });
            } catch (IOException e) {
                String msg = e.getMessage();
                String reason;
                if (msg != null && msg.contains("拒绝调试")) {
                    reason = "② 握手被电视拒绝：电视弹过「允许调试」但没勾「一律允许」。请在电视上重新出现弹窗时勾选「一律允许」，再回来重试。";
                } else if (msg != null && (msg.contains("timed out") || msg.contains("超时"))) {
                    reason = "② 握手超时：多半是电视在等你在「允许调试」弹窗上确认。请到电视前点「允许（一律允许）」，再重试自检。";
                } else if (msg != null && (msg.contains("refused") || msg.contains("Connection refused"))) {
                    reason = "② 连接被拒（" + msg + "）：5555 端口短暂不可达，可能电视刚重启或网络抖动，稍后重试。";
                } else {
                    reason = "② 握手段出错：" + msg;
                }
                final String show = reason;
                runOnUiThread(() -> {
                    line(show);
                    status("自检未通过：" + host + "。见上方 ② 的原因。");
                });
            }
        });
    }

    private void save(String ip, String desc) {
        Prefs.setDevice(this, ip, desc);
        status("已保存：" + ip + " · " + desc + "\n回主页可做第 3 步或用遥控器");
    }

    // ── 本机地址 ──────────────────────────────────────────────────
    /** 显示所有 IPv4  site-local 地址，让用户确认与电视同网段。 */
    private static String localIpInfo() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            StringBuilder sb = new StringBuilder();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InterfaceAddress a : nic.getInterfaceAddresses()) {
                    InetAddress ip = a.getAddress();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) {
                        sb.append(nic.getDisplayName()).append("：").append(ip.getHostAddress());
                        if (a.getNetworkPrefixLength() > 0) sb.append("/").append(a.getNetworkPrefixLength());
                        sb.append("\n");
                    }
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 取手机 Wi-Fi 的 IPv4 前三段；优先 wlan 接口；拿不到时返回 null。 */
    private static String localSubnet() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            // 第一遍优先 wlan / eth 这类真实网卡。
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                String name = nic.getDisplayName().toLowerCase();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                if (!(name.contains("wlan") || name.contains("eth") || name.contains("ap") ||
                        name.contains("bond"))) continue;
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (!(ip instanceof Inet4Address) || !ip.isSiteLocalAddress()) continue;
                    byte[] b = ip.getAddress();
                    return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".";
                }
            }
            // 第二遍放宽：任意 site-local IPv4。
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface nic = all.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (!(ip instanceof Inet4Address) || !ip.isSiteLocalAddress()) continue;
                    byte[] b = ip.getAddress();
                    return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".";
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDiscovery();
    }
}
