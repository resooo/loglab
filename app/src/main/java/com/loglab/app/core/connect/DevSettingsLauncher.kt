package com.loglab.app.core.connect

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.loglab.app.R

/**
 * 跳转到系统的「开发者选项」页面。
 *
 * 为什么需要它：「无线调试未开启」和「未配对」是**两个完全不同**的状态，
 * 引导动作也不一样——
 *
 *   - 无线调试没开 → 该去系统里把那个开关打开（本文件的职责）
 *   - 已开但没配对 → 该让用户点「使用配对码配对设备」，再回 App 填码（连接页的职责）
 *
 * 这两件事以前被合并成一个「去开启」按钮跳到连接页，用户到了连接页看到的却是
 * 配对步骤，会以为是自己没配对——所以这里补上真正打开开关的入口。
 *
 * 跳转按优先级尝试，全部失败时用 Toast 告知手动路径（不静默失败）：
 *  1. `ACTION_APPLICATION_DEVELOPMENT_SETTINGS` —— 直达开发者选项（最准）
 *  2. `ACTION_SETTINGS` 带 `:settings:development_settings` 组件 —— 部分 ROM 需要显式组件
 *  3. `ACTION_SETTINGS` —— 退到系统设置首页，至少让用户能自己找进去
 */
object DevSettingsLauncher {

    /** 跳转结果：true=已跳起（或已弹出手动路径提示）；false=完全没有可跳的目标 */
    fun open(context: Context): Boolean {
        val candidates = buildList {
            add(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            // 部分定制 ROM（含部分国产 ROM）只认显式组件形式
            add(
                Intent(Settings.ACTION_SETTINGS).apply {
                    component = android.content.ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"
                    )
                }
            )
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            val launched = runCatching {
                // 从 Application Context 启动必须带 NEW_TASK
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (launched) return true
        }
        // 都跳不起来：明确告诉用户手动路径，不要静默失败
        runCatching {
            Toast.makeText(
                context,
                context.getString(R.string.dev_settings_manual_hint),
                Toast.LENGTH_LONG
            ).show()
        }
        return false
    }

    /** 当前系统版本是否支持「无线调试」（Android 11 / API 30 起才有） */
    fun wirelessDebuggingSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
