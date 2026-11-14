package com.gathin.tvhome.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** 手机端只记一个当前电视地址和守护状态；IP 属于设备信息，绝不写进仓库或日志上报。 */
public final class Prefs {

    private static final String NAME = "tvhome_mobile";
    private static final String KEY_HOST = "tv_host";
    private static final String KEY_MODEL = "tv_model";
    private static final String KEY_GUARDED = "tv_guarded";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String host(Context ctx) {
        return prefs(ctx).getString(KEY_HOST, "");
    }

    public static void setDevice(Context ctx, String host, String model) {
        prefs(ctx).edit().putString(KEY_HOST, host).putString(KEY_MODEL, model).apply();
    }

    public static String model(Context ctx) {
        return prefs(ctx).getString(KEY_MODEL, "");
    }

    public static boolean guarded(Context ctx) {
        return prefs(ctx).getBoolean(KEY_GUARDED, false);
    }

    public static void setGuarded(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_GUARDED, on).apply();
    }
}
