package com.gathin.tvhome.mobile;

/**
 * 手机版对 TV 软件包的操作边界，沿用仓库 AGENTS.md / PROMPT.md 的规则：
 * 系统包只禁用不卸载，核心包一律不碰，所有禁用都能用 pm enable 还原。
 * 用户已确认：只有用户自己安装的第三方 App 才允许真正卸载。
 */
public final class Safety {

    /** 禁用/卸载这些会让电视起不来，脚本 tools/disable.sh 也拒绝同样的名单。 */
    private static final String[] CORE = {
            "android", "com.android.shell", "com.android.systemui", "com.android.phone",
            "com.android.settings", "com.android.tv.settings", "com.android.permissioncontroller",
            "com.android.packageinstaller", "com.android.providers.", "com.android.ext.services",
            "com.android.ext.shared", "android.ext.services", "android.ext.shared",
            "com.google.android.ext.", "com.google.android.overlay.modules",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending"
    };

    private Safety() {
    }

    public static boolean isCore(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        for (String prefix : CORE) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 系统包只禁用；只有第三方包允许卸载，且核心包永远不允许。 */
    public static boolean canUninstall(String pkg, boolean systemApp) {
        return !systemApp && !isCore(pkg);
    }

    public static boolean canDisable(String pkg) {
        return !isCore(pkg);
    }

    public static String disable(String pkg) {
        if (!canDisable(pkg)) throw new IllegalArgumentException("核心包不能禁用: " + pkg);
        return "pm disable-user --user 0 " + pkg;
    }

    public static String enable(String pkg) {
        if (pkg == null || pkg.isEmpty()) throw new IllegalArgumentException("包名不能为空");
        return "pm enable --user 0 " + pkg;
    }

    public static String uninstall(String pkg, boolean systemApp) {
        if (!canUninstall(pkg, systemApp)) {
            throw new IllegalArgumentException("系统包/核心包只能禁用，不能卸载: " + pkg);
        }
        return "pm uninstall --user 0 " + pkg;
    }

    /** 列出已装包时区分系统应用的命令（shell 输出含路径）。 */
    public static String listPackages() {
        return "pm list packages -f";
    }
}
