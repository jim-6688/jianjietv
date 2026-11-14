package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import java.util.List;

/**
 * 按应用数自动决定每行列数：应用少时用 3 列大卡，应用多时加到 4/5/6 列让卡片变小，
 * 目标是一屏放得下（默认两行）。设置页手动选过列数时不生效。
 * pick() 是纯计算、不碰 Android API，方便在 JVM 单测里锁住边界。
 */
final class AutoColumns {

    static final int MIN = 3;
    static final int MAX = 6;
    static final int TARGET_ROWS = 2;

    private AutoColumns() {
    }

    static int pick(int appCount) {
        return pick(appCount, TARGET_ROWS, MIN, MAX);
    }

    static int pick(int appCount, int rows, int min, int max) {
        if (appCount <= 0) return min;
        int safeRows = Math.max(1, rows);
        int needed = (appCount + safeRows - 1) / safeRows;   // ceil(appCount / rows)
        return Math.max(min, Math.min(max, needed));
    }

    /**
     * 首页实际会摆出来的卡片数近似值：Leanback 启动器应用，加上「回原桌面」和「应用库」
     * 两张合成卡。自动列数只是启发式，用查询得到的数量就够，不必把 reload() 的过滤规则
     * （hidden_apps / cards / library）在这里再跑一遍。
     */
    static int countApps(Context ctx) {
        try {
            List<ResolveInfo> apps = ctx.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
            return apps.size() + 2;
        } catch (RuntimeException ignored) {
            return 0;   // 查询被拒或系统服务异常：pick() 会退回最小列数
        }
    }
}
