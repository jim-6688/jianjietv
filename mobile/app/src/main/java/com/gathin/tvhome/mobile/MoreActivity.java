package com.gathin.tvhome.mobile;

import android.content.Intent;

/** 7 项功能的总览：标出每一项的状态，已实现的可以直接跳进去用。 */
public class MoreActivity extends ToolActivity {

    @Override
    protected String title() {
        return "功能汇总";
    }

    @Override
    protected String initialStatus() {
        return "7 项功能进度：已完成 1/2/3/6，4/5/7 本轮接入";
    }

    @Override
    protected void build() {
        done("1 · 按品牌引导开启 ADB", "按电视品牌给出开发者选项 / 无线调试步骤，含图文与断电重启提示。", BrandGuideActivity.class);
        done("2 · 扫描发现电视", "mDNS 自动发现 + 深度扫描 + 手动 IP，诊断信息明确。", ScanActivity.class);
        done("3 · 安装 TV 版并守护", "推送内置 TV 版 APK、设为默认桌面、启用守护服务。", SetupActivity.class);
        done("4 · TV 软件管理", "列出第三方（可卸载）与系统应用（可停用/启用），带保护名单。", AppManagerActivity.class);
        done("5 · TV 数据源", "直接对接电视配置服务：切源 / 上传 py，回显当前源。", SourcesActivity.class);
        done("6 · TV 遥控器", "方向/确认/返回/主页/菜单/音量/最近/电源；应用快捷键按品牌自动选专属键码。", RemoteActivity.class);
        done("7 · 配置同步到 TV", "编辑列数/dock/hidden/app_keys，生成 config.json 推到电视并重启桌面。", SyncActivity.class);
    }

    private void done(String title, String desc, Class<?> target) {
        heading(title);
        line(desc);
        action(R.drawable.ic_chevron_right, "打开", v -> startActivity(new Intent(this, target)));
    }
}
