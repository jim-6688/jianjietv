package com.gathin.tvhome;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** 把一段文字（这里就是手机要打开的配置页地址）画成二维码 Bitmap。 */
final class QrCode {

    /** 白底深色块：手机摄像头对反色二维码支持不一，这里按标准配色来。 */
    static final int LIGHT = 0xFFFFFFFF;
    static final int DARK = 0xFF10151B;

    private QrCode() {
    }

    /**
     * 二维码的模块矩阵，{@code true} 是深色块。拆出来是为了能在纯 JVM 单测里用 zxing 自己解一遍，
     * 确认「深色 = true」这个方向和编码都没错（反了手机就扫不出来）。
     */
    static boolean[][] matrix(String text, int sizePx) {
        if (text == null || text.isEmpty() || sizePx <= 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            // 留 2 个模块的静区，贴在浅色卡片上也能扫得稳。
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix bits = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = bits.getWidth();
            int h = bits.getHeight();
            boolean[][] modules = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    modules[y][x] = bits.get(x, y);
                }
            }
            return modules;
        } catch (Exception e) {
            return null;   // 内容过长或编码失败：不显示，但面板其它部分照常用
        }
    }

    /** 生成边长约 sizePx 的二维码，失败返回 null（调用方据此隐藏图片）。 */
    static Bitmap bitmap(String text, int sizePx) {
        boolean[][] modules = matrix(text, sizePx);
        if (modules == null) return null;
        int h = modules.length;
        int w = modules[0].length;
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = modules[y][x] ? DARK : LIGHT;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }
}
