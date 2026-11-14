package com.gathin.tvhome.mobile;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 手机推送安装前的本地校验：把 HaloTV「手机推送校验」要的几项（类型 / 包名 / 签名 / 内部号）
 * 在动手推之前就查清楚，推错包、同包名不同签名、降版这些坑都在手机上提前拦下。
 *
 * <p>全部走 Android 框架 API，不引任何第三方库——和 mobile 模块的零依赖基调一致。
 */
final class ApkVerifier {

    /** 解析出来的 APK 信息；{@code valid=false} 表示文件根本不是合法 APK。 */
    static final class ApkInfo {
        final boolean valid;
        final String packageName;
        final long versionCode;     // 内部号
        final String versionName;
        final String certSha256;    // 签名证书 SHA-256 指纹（小写十六进制），读不到为 null

        private ApkInfo(boolean valid, String packageName, long versionCode, String versionName, String certSha256) {
            this.valid = valid;
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.certSha256 = certSha256;
        }

        static ApkInfo invalid() {
            return new ApkInfo(false, null, -1, null, null);
        }

        static ApkInfo of(String packageName, long versionCode, String versionName, String certSha256) {
            return new ApkInfo(true, packageName, versionCode, versionName, certSha256);
        }
    }

    /**
     * 读取本地 APK 的包名 / 内部号 / 签名指纹；不是合法 APK 时返回 {@link ApkInfo#invalid()}。
     *
     * <p>这里读 {@code signatures} 字段是为兼容 minSdk 26（API 28 以下没有 signingInfo），
     * 该字段在较新 SDK 上已弃用，故局部抑制该告警。
     */
    @SuppressWarnings("deprecation")
    static ApkInfo read(File apk, Context ctx) {
        if (apk == null || !apk.isFile() || apk.length() == 0) return ApkInfo.invalid();
        // 类型：至少得是 zip 容器（APK 本质是 zip），否则后面框架 API 也读不出。
        if (!isZip(apk)) return ApkInfo.invalid();
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(),
                PackageManager.GET_SIGNATURES | PackageManager.GET_META_DATA);
        if (pi == null) return ApkInfo.invalid();
        String cert = null;
        if (pi.signatures != null && pi.signatures.length > 0) {
            cert = sha256Hex(pi.signatures[0].toByteArray());
        }
        return ApkInfo.of(pi.packageName, versionCodeOf(pi), pi.versionName, cert);
    }

    /** 从 {@code dumpsys package <pkg>} / {@code pm dump} 的输出里取 versionCode；没有返回 -1。 */
    static long parseVersionCode(String dumpsys) {
        if (dumpsys == null) return -1;
        // 形如 "versionCode=1 minSdk=26" 或 "versionCode=1"，取第一个数字。
        int at = dumpsys.indexOf("versionCode=");
        if (at < 0) return -1;
        int end = at + "versionCode=".length();
        int len = dumpsys.length();
        int start = end;
        while (end < len && Character.isDigit(dumpsys.charAt(end))) end++;
        if (end == start) return -1;
        try {
            return Long.parseLong(dumpsys.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 证书 DER 的 SHA-256，转小写十六进制；读不到返回 null。 */
    static String sha256Hex(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCodeOf(PackageInfo pi) {
        if (android.os.Build.VERSION.SDK_INT >= 28) return pi.getLongVersionCode();
        return pi.versionCode;
    }

    private static boolean isZip(File apk) {
        try (InputStream in = new FileInputStream(apk)) {
            byte[] head = new byte[4];
            int n = in.read(head);
            return n == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        } catch (IOException e) {
            return false;
        }
    }

    private ApkVerifier() {
    }
}
