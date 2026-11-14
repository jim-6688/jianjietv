package com.gathin.tvhome.mobile.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * ADB 线上协议的一个包：24 字节头 + payload。全部小端，magic = command ^ 0xffffffff。
 * 只实现手机端需要的 CNXN / AUTH / OPEN / OKAY / WRTE / CLSE，不实现 sync 之外的服务。
 */
final class AdbPacket {

    static final int A_CNXN = 0x4e584e43;   // "CNXN"
    static final int A_AUTH = 0x48545541;   // "AUTH"
    static final int A_OPEN = 0x4e45504f;   // "OPEN"
    static final int A_OKAY = 0x59414b4f;   // "OKAY"
    static final int A_CLSE = 0x45534c43;   // "CLSE"
    static final int A_WRTE = 0x45545257;   // "WRTE"

    static final int A_VERSION = 0x01000000;
    static final int MAX_DATA = 4096;
    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    static final byte[] EMPTY = new byte[0];

    final int command;
    final int arg0;
    final int arg1;
    final byte[] data;

    AdbPacket(int command, int arg0, int arg1, byte[] data) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.data = data == null ? EMPTY : data;
    }

    /** 编码成可直接写进 socket 的字节流（头 + payload）。 */
    byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command);
        b.putInt(arg0);
        b.putInt(arg1);
        b.putInt(data.length);
        b.putInt(crc32(data));
        b.putInt(command ^ 0xffffffff);
        b.put(data);
        return b.array();
    }

    /** 从流里读一个完整包；连上断开发生在中途时抛 EOFException。 */
    static AdbPacket read(InputStream in) throws IOException {
        byte[] head = readFully(in, 24);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt();
        int arg0 = b.getInt();
        int arg1 = b.getInt();
        int length = b.getInt();
        int crc = b.getInt();
        int magic = b.getInt();
        if (magic != (command ^ 0xffffffff)) throw new IOException("ADB 包头 magic 不匹配");
        if (length < 0 || length > 1024 * 1024) throw new IOException("ADB 包长度异常: " + length);
        byte[] data = length == 0 ? EMPTY : readFully(in, length);
        if (crc32(data) != crc) throw new IOException("ADB 包 CRC32 校验失败");
        return new AdbPacket(command, arg0, arg1, data);
    }

    private static byte[] readFully(InputStream in, int size) throws IOException {
        byte[] buf = new byte[size];
        int read = 0;
        while (read < size) {
            int n = in.read(buf, read, size - read);
            if (n < 0) throw new EOFException("ADB 连接已断开");
            read += n;
        }
        return buf;
    }

    static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return (int) crc.getValue();
    }

    /** ADB 的服务字符串以 NUL 结尾。 */
    static byte[] cString(String text) {
        return (text + "\0").getBytes(StandardCharsets.UTF_8);
    }
}
