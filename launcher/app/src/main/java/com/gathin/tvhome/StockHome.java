package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/**
 * 电视原来那个桌面（厂商自带 launcher）：找出来，点一下切回去。
 *
 * 本应用被设为默认桌面之后，原桌面仍然能以 HOME 意图查到——只要它还处于启用状态（安装脚本会停用它，
 * 那就查不到，卡片也不显示）。这里只读包管理器，不发广播、不改默认桌面：切回去是一次性的，遥控器再按
 * Home 仍然回到简洁桌面。
 */
final class StockHome {
    private StockHome() { }

    /** 原桌面的 HOME activity：排除自己、只看已启用的；找不到（或只剩自己）时返回 null。 */
    static ComponentName find(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> launchers;
        try {
            // 不带 MATCH_DISABLED_COMPONENTS：已停用的原桌面查不出来，也就不会给出一张点了会失败的卡片。
            launchers = pm.queryIntentActivities(home, 0);
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (launchers == null) return null;
        String self = context.getPackageName();
        for (ResolveInfo info : launchers) {
            if (info.activityInfo == null || info.activityInfo.name == null) continue;
            if (self.equals(info.activityInfo.packageName)) continue;
            return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
        }
        return null;
    }

    static boolean installed(Context context) {
        return find(context) != null;
    }

    /** 切回原桌面；没有原桌面或启动失败（刚被停用、被卸载）返回 false。 */
    static boolean open(Context context) {
        ComponentName target = find(context);
        if (target == null) return false;
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
