package com.gathin.tvhome.mobile.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** ADB 包头/CRC 是纯字节逻辑，能在 JVM 里锁住，避免实机连不上时连协议都不可信。 */
public class AdbPacketTest {

    @Test
    public void encodesAndReadsBack() throws Exception {
        byte[] data = AdbPacket.cString("shell:getprop ro.product.model");
        AdbPacket packet = new AdbPacket(AdbPacket.A_OPEN, 7, 0, data);
        byte[] wire = packet.encode();
        assertEquals(24 + data.length, wire.length);
        AdbPacket back = AdbPacket.read(new ByteArrayInputStream(wire));
        assertEquals(AdbPacket.A_OPEN, back.command);
        assertEquals(7, back.arg0);
        assertEquals(0, back.arg1);
        assertArrayEquals(data, back.data);
    }

    @Test
    public void crcMatchesJavaCrc32() {
        byte[] data = "hello adb".getBytes(StandardCharsets.US_ASCII);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data, 0, data.length);
        assertEquals((int) crc.getValue(), AdbPacket.crc32(data));
    }

    @Test
    public void magicIsComplementOfCommand() throws Exception {
        byte[] wire = new AdbPacket(AdbPacket.A_CNXN, 1, 2, AdbPacket.EMPTY).encode();
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(wire).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt();
        b.position(20);
        assertEquals(command ^ 0xffffffff, b.getInt());
    }

    @Test
    public void corruptMagicIsRejected() throws Exception {
        byte[] wire = new AdbPacket(AdbPacket.A_OKAY, 1, 2, AdbPacket.EMPTY).encode();
        wire[20] = (byte) (wire[20] ^ 0x01);
        try {
            AdbPacket.read(new ByteArrayInputStream(wire));
            fail("损坏的 magic 应该被拒绝");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void serviceStringsEndWithNul() {
        byte[] data = AdbPacket.cString("sync:");
        assertEquals(0, data[data.length - 1]);
        assertEquals(6, data.length);
    }
}
