package com.gathin.tvhome.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrandsTest {

    @Test
    public void everyBrandHasAtLeastOneStep() {
        Brands.Brand[] all = Brands.all();
        assertTrue("至少要覆盖常见品牌", all.length >= 8);
        for (Brands.Brand brand : all) {
            assertFalse(brand.name.isEmpty());
            assertTrue(brand.name + " 没有开启步骤", brand.steps.length > 0);
            for (String step : brand.steps) assertFalse(step.isEmpty());
        }
    }

    @Test
    public void nonAndroidTvIsFlaggedAndStopped() {
        boolean found = false;
        for (Brands.Brand brand : Brands.all()) {
            if (!brand.androidTv) {
                found = true;
                assertTrue("非 Android TV 要明确写停止", brand.steps[0].contains("停止"));
            }
        }
        assertTrue("必须有一项提醒 Tizen/webOS/Roku 不适用", found);
    }

    @Test
    public void guideNeverMentionsFactoryMenuAsAnInstruction() {
        String warning = Brands.warning();
        assertTrue(warning.contains("工厂"));
        for (Brands.Brand brand : Brands.all()) {
            for (String step : brand.steps) {
                assertFalse("不要把进工厂菜单写成步骤：" + step, step.startsWith("设置 → 工厂"));
            }
        }
    }
}
