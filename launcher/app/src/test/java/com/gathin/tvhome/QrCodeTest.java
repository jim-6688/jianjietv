package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.Test;

/**
 * 电视上贴出来的二维码必须真的能扫：这里用 zxing 自己的解码器把生成的模块矩阵解一遍，
 * 内容对得上才算通过（深色/浅色画反了、静区没留、编码写错，都会在这一步露出来）。
 */
public class QrCodeTest {

    private static final String URL = "http://192.168.1.23:8899/";

    @Test
    public void theQrCodeDecodesBackToTheSameUrl() throws Exception {
        boolean[][] modules = QrCode.matrix(URL, 240);
        assertNotNull(modules);
        assertEquals(URL, decode(modules, 1));
    }

    @Test
    public void itStillDecodesAtTheSizeThePanelShows() throws Exception {
        // 面板上那块是 116dp，1080p 电视上大约是 240px，4K 上 1.5 倍字号仍是同样的像素尺寸，
        // 所以按几个常见尺寸各解一次，确认缩放到这些边长也还是清楚的。
        for (int size : new int[]{180, 240, 348}) {
            boolean[][] modules = QrCode.matrix("http://10.0.0.7:8899/", size);
            assertNotNull(modules);
            assertEquals("size " + size, "http://10.0.0.7:8899/", decode(modules, 1));
        }
    }

    @Test
    public void noMatrixForEmptyText() {
        assertEquals(null, QrCode.matrix("", 240));
        assertEquals(null, QrCode.matrix(null, 240));
    }

    /** 把模块矩阵按 scale 放大成像素，交给 zxing 解。 */
    private static String decode(boolean[][] modules, int scale) throws Exception {
        int h = modules.length;
        int w = modules[0].length;
        int[] pixels = new int[w * scale * h * scale];
        int pw = w * scale;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int color = modules[y][x] ? QrCode.DARK : QrCode.LIGHT;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        pixels[(y * scale + dy) * pw + x * scale + dx] = color;
                    }
                }
            }
        }
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(pw, h * scale, pixels)));
        return new QRCodeReader().decode(bitmap).getText();
    }
}
