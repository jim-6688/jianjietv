package com.gathin.tvhome.mobile.adb;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;

/**
 * 手机端自己的 ADB RSA 身份（相当于桌面 ~/.android/adbkey）。
 *
 * <p>电视开了 ro.adb.secure 时会发 AUTH TOKEN，第一次用私钥签名、第二次把 adb 格式的公钥
 * 发给电视让它弹「允许调试」；用户勾了「一律允许」之后重连就能用签名通过。
 * 不少国产电视 ro.adb.secure=0，握手直接 CNXN，不需要这一步。
 */
final class AdbKey {

    private static final int KEY_BITS = 2048;
    private static final int WORDS = KEY_BITS / 32;      // 64
    private static final int MOD_BYTES = WORDS * 4;      // 256

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;

    private AdbKey(PrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    static AdbKey load(Context ctx) throws Exception {
        File dir = ctx.getFilesDir();
        File priv = new File(dir, "adbkey");
        File pub = new File(dir, "adbkey.pub");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            PrivateKey p = factory.generatePrivate(new PKCS8EncodedKeySpec(read(priv)));
            RSAPublicKey q = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(read(pub)));
            return new AdbKey(p, q);
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(KEY_BITS);
        KeyPair pair = gen.generateKeyPair();
        write(priv, pair.getPrivate().getEncoded());
        write(pub, pair.getPublic().getEncoded());
        return new AdbKey(pair.getPrivate(), (RSAPublicKey) pair.getPublic());
    }

    /** AUTH SIGNATURE 的 payload：对 20 字节 token 做 PKCS#1 v1.5 私钥签名（无 digest）。 */
    byte[] sign(byte[] token) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(token);
    }

    /** AUTH RSAPUBLICKEY 的 payload：base64(adb RSAPublicKey 结构) + NUL。 */
    byte[] publicKeyBlob() {
        String encoded = Base64.encodeToString(adbPublicKey(), Base64.NO_WRAP);
        return (encoded + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * adb 自己那套 RSAPublicKey（不是 X.509）：bits / n0inv / 小端 modulus / 小端 R^2 / exponent。
     * n0inv = -n^-1 mod 2^32，rr = (2^(32*words))^2 mod n。
     */
    private byte[] adbPublicKey() {
        BigInteger n = publicKey.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger inverse = n.mod(two32).modInverse(two32);
        BigInteger n0inv = two32.subtract(inverse).mod(two32);
        BigInteger r = BigInteger.ONE.shiftLeft(32 * WORDS);
        BigInteger rr = r.multiply(r).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + MOD_BYTES + MOD_BYTES + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(WORDS);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, MOD_BYTES));
        b.put(littleEndian(rr, MOD_BYTES));
        b.putInt(publicKey.getPublicExponent().intValue());
        return b.array();
    }

    private static byte[] littleEndian(BigInteger value, int length) {
        byte[] big = value.toByteArray();       // 大端，可能多一个符号位 0
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            int from = big.length - 1 - i;
            out[i] = from >= 0 ? big[from] : 0;
        }
        return out;
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            return bytes;
        }
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
