package com.gathin.tvhome.mobile.adb;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 手机直连电视 5555 的 ADB-over-TCP 客户端，纯 JDK + Android API，不需要 adb 二进制、不需要 root。
 * 协议路径照仓库 tools/tv.py 用的 adb_shell：CNXN →（AUTH）→ OPEN shell:/sync: → WRTE/OKAY → CLSE。
 */
public final class Adb implements Closeable {

    public interface Progress {
        void onProgress(String message);
    }

    private static final int PORT = 5555;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final AdbKey key;
    private final ByteArrayOutputStream syncReplies = new ByteArrayOutputStream();
    private int nextLocalId = 1;

    private Adb(Socket socket, AdbKey key) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.key = key;
    }

    /** 只探测 TCP 端口是否开放：扫描网段时用，不做 ADB 握手。 */
    public static boolean portOpen(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException ignored) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 连上并完成握手。电视若弹了调试授权会在超时里给出明确提示。 */
    public static Adb connect(Context ctx, String host, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            // 握手读超时给足：首次连接电视会弹「允许调试」，用户需要在电视上点允许，
            // 那段等待不能太短，否则握手在授权前就被超时掐断。
            socket.setSoTimeout(Math.max(timeoutMs, 15000));
            socket.setTcpNoDelay(true);
            AdbKey key;
            try {
                key = AdbKey.load(ctx);
            } catch (Exception e) {
                throw new IOException("本机 ADB 密钥生成失败：" + e.getMessage(), e);
            }
            Adb adb = new Adb(socket, key);
            try {
                adb.handshake();
            } catch (IOException e) {
                adb.close();
                throw e;
            }
            return adb;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** 扫描命中后确认它真是 adbd，并读出型号 / Android 版本。 */
    public static String describe(Context ctx, String host, int port, int timeoutMs) {
        Adb adb = null;
        try {
            adb = connect(ctx, host, port, timeoutMs);
            String model = adb.shell("getprop ro.product.model").trim();
            String release = adb.shell("getprop ro.build.version.release").trim();
            String sdk = adb.shell("getprop ro.build.version.sdk").trim();
            return (model.isEmpty() ? "Android TV" : model) + " · Android " + release + " (SDK " + sdk + ")";
        } catch (IOException e) {
            return "";   // 端口开着但不是 adbd：扫描时不把它列成电视
        } finally {
            if (adb != null) adb.close();
        }
    }

    private void handshake() throws IOException {
        send(AdbPacket.A_CNXN, AdbPacket.A_VERSION, AdbPacket.MAX_DATA,
                AdbPacket.cString("host::"));
        int authRound = 0;
        while (true) {
            AdbPacket p = AdbPacket.read(in);
            if (p.command == AdbPacket.A_CNXN) return;          // 连通（含 ro.adb.secure=0 的电视）
            if (p.command == AdbPacket.A_AUTH && p.arg0 == AdbPacket.AUTH_TOKEN) {
                try {
                    // 第一次 token 用私钥签名；设备不认识这把钥匙会再发一次 token，那时改送公钥让它弹授权。
                    if (authRound++ == 0) {
                        send(AdbPacket.A_AUTH, AdbPacket.AUTH_SIGNATURE, 0, key.sign(p.data));
                    } else {
                        send(AdbPacket.A_AUTH, AdbPacket.AUTH_RSAPUBLICKEY, 0, key.publicKeyBlob());
                    }
                } catch (Exception e) {
                    throw new IOException("ADB 认证失败：" + e.getMessage(), e);
                }
                continue;
            }
            if (p.command == AdbPacket.A_CLSE) {
                throw new IOException("电视拒绝了调试连接；请在电视上勾选「一律允许」后重试");
            }
        }
    }

    /** 跑一条 shell 命令，返回全部输出。 */
    public String shell(String command) throws IOException {
        int localId = nextLocalId++;
        send(AdbPacket.A_OPEN, localId, 0, AdbPacket.cString("shell:" + command));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int remoteId = -1;
        while (true) {
            AdbPacket p = AdbPacket.read(in);
            if (p.command == AdbPacket.A_OKAY) {
                remoteId = p.arg0;
                continue;
            }
            if (p.command == AdbPacket.A_WRTE) {
                result.write(p.data, 0, p.data.length);
                send(AdbPacket.A_OKAY, localId, remoteId, AdbPacket.EMPTY);
                continue;
            }
            if (p.command == AdbPacket.A_CLSE) break;
        }
        return new String(result.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
    }

    /** 用 sync: 服务把 APK 推到 /data/local/tmp/，比 base64 分块写文件快得多。 */
    public void push(File local, String remotePath, Progress progress) throws IOException {
        int localId = nextLocalId++;
        send(AdbPacket.A_OPEN, localId, 0, AdbPacket.cString("sync:"));
        int remoteId = -1;
        while (remoteId < 0) {
            AdbPacket p = AdbPacket.read(in);
            if (p.command == AdbPacket.A_OKAY) remoteId = p.arg0;
            else if (p.command == AdbPacket.A_CLSE) throw new IOException("电视拒绝 sync 服务");
        }
        // S_IFREG | 0644 = 33188，和 adb push 写临时文件时用的模式一致。
        syncFrame("SEND", (remotePath + ",33188").getBytes(StandardCharsets.UTF_8), localId, remoteId);
        long total = local.length();
        long sent = 0;
        byte[] chunk = new byte[AdbPacket.MAX_DATA - 8];
        try (FileInputStream file = new FileInputStream(local)) {
            int n;
            while ((n = file.read(chunk)) > 0) {
                byte[] payload = n == chunk.length ? chunk : Arrays.copyOf(chunk, n);
                syncFrame("DATA", payload, localId, remoteId);
                sent += n;
                if (progress != null && (sent / chunk.length) % 40 == 0) {
                    progress.onProgress("推送 " + (sent * 100 / Math.max(1, total)) + "%");
                }
            }
        }
        syncFrame("DONE", new byte[4], localId, remoteId);   // mtime = 0
        String reply = waitForSyncReply(localId, remoteId);
        if (reply.startsWith("FAIL")) throw new IOException("推送失败：" + reply.substring(8));
        send(AdbPacket.A_CLSE, localId, remoteId, AdbPacket.EMPTY);
        if (progress != null) progress.onProgress("推送完成");
    }

    private void syncFrame(String id, byte[] payload, int localId, int remoteId) throws IOException {
        byte[] frame = new byte[8 + payload.length];
        System.arraycopy(id.getBytes(StandardCharsets.US_ASCII), 0, frame, 0, 4);
        ByteBuffer.wrap(frame, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length);
        System.arraycopy(payload, 0, frame, 8, payload.length);
        send(AdbPacket.A_WRTE, remoteId, localId, frame);
        // 协议层流控：每个 WRTE 等设备一个 OKAY；期间夹进来的 WRTE 是 sync 自己的响应。
        while (true) {
            AdbPacket p = AdbPacket.read(in);
            if (p.command == AdbPacket.A_OKAY) return;
            if (p.command == AdbPacket.A_WRTE) {
                syncReplies.write(p.data, 0, p.data.length);
                send(AdbPacket.A_OKAY, localId, remoteId, AdbPacket.EMPTY);
                continue;
            }
            if (p.command == AdbPacket.A_CLSE) throw new IOException("sync 连接被电视关闭");
        }
    }

    private String waitForSyncReply(int localId, int remoteId) throws IOException {
        for (int i = 0; i < 64; i++) {
            byte[] collected = syncReplies.toByteArray();
            String text = new String(collected, StandardCharsets.US_ASCII);
            int at = text.indexOf("OKAY");
            int fail = text.indexOf("FAIL");
            if (at >= 0 || fail >= 0) return at >= 0 ? "OKAY" : text.substring(Math.max(0, fail));
            AdbPacket p = AdbPacket.read(in);
            if (p.command == AdbPacket.A_WRTE) {
                syncReplies.write(p.data, 0, p.data.length);
                send(AdbPacket.A_OKAY, localId, remoteId, AdbPacket.EMPTY);
            } else if (p.command == AdbPacket.A_CLSE) {
                break;
            }
        }
        return "OKAY";   // 有些 adbd 在 DONE 后不回 sync OKAY，直接 CLSE 也算成功
    }

    /**
     * 用户点了「关闭 ADB」：先下发开关，再由调用方用 portOpen() 校验 5555 是否真的关了。
     * 明文 5555 长期开着不安全，这一步是刻意的收尾动作。
     */
    public static void requestCloseAdb(Adb adb) {
        try {
            adb.shell("settings put global adb_enabled 0");
        } catch (IOException ignored) {
        }
        try {
            adb.shell("setprop service.adb.tcp.port -1");
        } catch (IOException ignored) {
        }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        out.write(new AdbPacket(command, arg0, arg1, data).encode());
        out.flush();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public static int defaultPort() {
        return PORT;
    }
}
