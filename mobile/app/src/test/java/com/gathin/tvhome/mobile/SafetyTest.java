package com.gathin.tvhome.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** 用户确认的口径：系统包只禁用、第三方才可卸载、核心包一律不动。 */
public class SafetyTest {

    @Test
    public void corePackagesAreProtected() {
        assertTrue(Safety.isCore("com.android.systemui"));
        assertTrue(Safety.isCore("com.android.settings"));
        assertTrue(Safety.isCore("com.android.providers.settings"));
        assertTrue(Safety.isCore("com.google.android.gms"));
        assertTrue(Safety.isCore("android"));
        assertFalse(Safety.canDisable("com.android.systemui"));
        assertFalse(Safety.canUninstall("com.android.systemui", false));
    }

    @Test
    public void systemAppsCanOnlyBeDisabled() {
        String pkg = "com.vendor.stocklauncher";
        assertTrue(Safety.canDisable(pkg));
        assertFalse(Safety.canUninstall(pkg, true));
        assertEquals("pm disable-user --user 0 " + pkg, Safety.disable(pkg));
        assertEquals("pm enable --user 0 " + pkg, Safety.enable(pkg));
    }

    @Test
    public void thirdPartyAppsCanBeUninstalled() {
        String pkg = "com.example.sideloaded";
        assertTrue(Safety.canUninstall(pkg, false));
        assertEquals("pm uninstall --user 0 " + pkg, Safety.uninstall(pkg, false));
    }

    @Test
    public void uninstallRefusesSystemAndCore() {
        try {
            Safety.uninstall("com.vendor.stocklauncher", true);
            fail("系统包不该允许卸载");
        } catch (IllegalArgumentException expected) {
        }
        try {
            Safety.disable("com.android.phone");
            fail("核心包不该允许禁用");
        } catch (IllegalArgumentException expected) {
        }
    }
}
