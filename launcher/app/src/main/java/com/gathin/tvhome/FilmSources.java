package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 数据源清单：内置源 + 手机扫码加进来的自定义源，以及上传到本机的 py 文件。
 *
 * <p>内置源写在 {@link #BUILT_IN} 里；自定义源存在 {@code film_sources} 的 {@code custom} 键里
 * （和「当前是哪个源」共用一个 SharedPreferences），手机上扫码加的源下次开机还在，电视端也会列出来。
 *
 * <p>手机上传的 .py 本身就是一个配置源：它排在内置源之前，最新上传的那条永远第一，
 * 上传完成时由 {@link ConfigServer} 自动切到它。
 */
final class FilmSources {

    /** 最新上传、要置顶的 py 文件名。 */
    private static final String KEY_PRIORITY_PY = "priority_py";

    /** 内置源：{显示名, 配置地址}。顺序即面板里的排列顺序，第一条是主接口。 */
    static final String[][] BUILT_IN = {
            {"饭总主接口", "http://www.饭太硬.net/tv"},
            {"饭总备用1", "http://fty.333232.xyz/tv"},
            {"饭总备用2", "http://fty.888484.xyz/tv"},
            {"饭总备用3", "http://www.饭太硬.art/tv"},
            {"饭总备用4", "http://fty.xxooo.cf/tv"},
            {"饭总备用5", "http://www.饭太硬.cc/tv"},
            {"肥猫主接口", "http://肥猫.net/tv"},
            {"肥猫备用1", "http://hello.肥猫.net"},
            {"肥猫备用2", "https://w.junce.dpdns.org1fm.gif"},
            {"嗷呜主接口", "http://www.英格里希嗷呜.top/tv"},
            {"嗷呜备用1", "http://itv666.cc/aowu/config.webp"},
            {"嗷呜备用2", "https://9763.kstore.vip/aowu.json"},
            {"嗷呜防失联", "http://www.英格里希嗷呜.top"},
    };

    private static final String PREFS = "film_sources";
    private static final String KEY_CUSTOM = "custom";
    /** py 文件放这里，和 SharedPreferences 分开放，文件选择器/网页下载都能直接看到。 */
    private static final String PY_DIR = "py";

    private FilmSources() {
    }

    /**
     * 全部源：上传的 py 优先级最高（最新上传的排第一），其次内置源，最后扫码加的自定义源。
     * py 的地址由常驻的 {@link ConfigServer} 提供；没连局域网时拿不到地址，就先不列出来。
     */
    static List<String[]> all(Context ctx) {
        String priority = priorityPy(ctx);
        List<String[]> py = new ArrayList<>();
        for (File file : pyFiles(ctx)) {
            String[] source = pySource(ctx, file);
            if (source == null) continue;
            if (file.getName().equals(priority)) py.add(0, source);   // 最新上传的置顶
            else py.add(source);
        }
        return order(py, new ArrayList<>(Arrays.asList(BUILT_IN)), custom(ctx));
    }

    /** 纯合并：py → 内置 → 自定义，按地址去重。抽出来方便 JVM 单测锁住优先级。 */
    static List<String[]> order(List<String[]> py, List<String[]> builtIn, List<String[]> customs) {
        List<String[]> list = new ArrayList<>();
        for (String[] source : py) addUnique(list, source);
        for (String[] source : builtIn) addUnique(list, source);
        for (String[] source : customs) addUnique(list, source);
        return list;
    }

    private static void addUnique(List<String[]> list, String[] source) {
        if (source == null) return;
        for (String[] known : list) if (known[1].equals(source[1])) return;
        list.add(source);
    }

    /** 扫码加进来的自定义源，顺序即添加顺序。 */
    static List<String[]> custom(Context ctx) {
        List<String[]> list = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_CUSTOM, null);
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "").trim();
                if (url.isEmpty()) continue;
                String name = o.optString("name", "").trim();
                list.add(new String[]{name.isEmpty() ? hostOf(url) : name, url});
            }
        } catch (Exception ignored) {
            // 存坏了就当没有，不影响内置源
        }
        return list;
    }

    /** 加一条自定义源；同一个地址只留一条，返回是否新增成功。 */
    static boolean addCustom(Context ctx, String name, String url) {
        String clean = url == null ? "" : url.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false;
        List<String[]> list = custom(ctx);
        for (String[] known : list) {
            if (known[1].equals(clean)) return false;
        }
        String label = name == null ? "" : name.trim();
        list.add(new String[]{label.isEmpty() ? hostOf(clean) : label, clean});
        saveCustom(ctx, list);
        return true;
    }

    private static void saveCustom(Context ctx, List<String[]> list) {
        JSONArray array = new JSONArray();
        for (String[] source : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", source[0]);
                o.put("url", source[1]);
                array.put(o);
            } catch (Exception ignored) {
                // JSONObject.put 只在 key 为 null 时抛，这里不会走到
            }
        }
        prefs(ctx).edit().putString(KEY_CUSTOM, array.toString()).apply();
    }

    /** 从地址里取主机名当默认名字：http://foo.bar/tv → foo.bar。 */
    static String hostOf(String url) {
        String s = url == null ? "" : url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s.isEmpty() ? "自定义源" : s;
    }

    // ---- 上传的 py ---------------------------------------------------------------------------

    /** 最新上传并置顶的 py 文件名；文件已经被删掉就当没有。 */
    static String priorityPy(Context ctx) {
        String name = prefs(ctx).getString(KEY_PRIORITY_PY, null);
        return name == null || !new File(pyDir(ctx), name).isFile() ? null : name;
    }

    /** 记下这次上传的文件，让它成为源列表里的最高优先级。 */
    static void setPriorityPy(Context ctx, String name) {
        prefs(ctx).edit().putString(KEY_PRIORITY_PY, name).apply();
    }

    /** 显示名：去掉 .py 后缀，前面加「本机 py」以便和内置源区分。 */
    static String pyLabel(String fileName) {
        String name = fileName == null ? "" : fileName;
        if (name.toLowerCase(Locale.ROOT).endsWith(".py")) name = name.substring(0, name.length() - 3);
        return "本机 py · " + (name.isEmpty() ? "source" : name);
    }

    /** py 作为配置源：名字 + 常驻服务的局域网地址；没地址时返回 null。 */
    static String[] pySource(Context ctx, File file) {
        String url = ConfigServer.pyUrl(file.getName());
        return url == null ? null : new String[]{pyLabel(file.getName()), url};
    }

    static File pyDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), PY_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return dir;
        return dir;
    }

    /** 已上传的 py，按名字排序。 */
    static List<File> pyFiles(Context ctx) {
        File[] files = pyDir(ctx).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".py"));
        List<File> list = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        list.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        return list;
    }

    /** 手机网页上看这条 py 的地址：http://电视IP:端口/py/名字.py。 */
    static String pyUrl(String host, int port, String name) {
        return "http://" + host + ":" + port + "/py/" + encode(name);
    }

    /** 存一个上传上来的 py，名字只留安全字符，重名自动加 -2、-3。 */
    static File savePy(Context ctx, String name, byte[] data) {
        String clean = safeName(name);
        File dir = pyDir(ctx);
        File file = new File(dir, clean);
        for (int i = 2; file.exists(); i++) {
            file = new File(dir, clean + "-" + i + ".py");
        }
        try {
            try (java.io.OutputStream out = new java.io.FileOutputStream(file)) {
                out.write(data);
            }
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /** 只留字母数字、点、横线、下划线和中文，去掉路径分隔符与其它符号。 */
    private static String safeName(String name) {
        String base = name == null ? "" : name.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_'
                    || (c >= 0x4E00 && c <= 0x9FFF);
            sb.append(ok ? c : '_');
        }
        String clean = sb.toString();
        if (clean.isEmpty() || clean.equals("_")) clean = "source";
        if (!clean.toLowerCase(Locale.ROOT).endsWith(".py")) clean = clean + ".py";
        return clean;
    }

    static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return text;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
