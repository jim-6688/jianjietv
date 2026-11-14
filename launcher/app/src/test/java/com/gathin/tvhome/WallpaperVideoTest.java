package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The bundled Aerial clips switch to http; everything else keeps the scheme its manifest gave. */
public class WallpaperVideoTest {
    @Test public void anAerialClipIsPlayedOverHttp() {
        assertEquals("http://sylvan.apple.com/Videos/PA_A001_C007_SDR_20190717_SDR_2K_AVC.mov",
                WallpaperVideo.playable("https://sylvan.apple.com/Videos/PA_A001_C007_SDR_20190717_SDR_2K_AVC.mov"));
    }

    @Test public void anAerialClipAlreadyOnHttpIsLeftAlone() {
        String url = "http://sylvan.apple.com/Videos/PA_A001_C007_SDR_20190717_SDR_2K_AVC.mov";
        assertEquals(url, WallpaperVideo.playable(url));
    }

    @Test public void anotherHttpsHostKeepsHttps() {
        String url = "https://example.com/aerial.mp4";
        assertEquals(url, WallpaperVideo.playable(url));
    }

    @Test public void aLookalikeHostIsNotRewritten() {
        String url = "https://sylvan.apple.com.example.net/aerial.mp4";
        assertEquals(url, WallpaperVideo.playable(url));
    }
}
