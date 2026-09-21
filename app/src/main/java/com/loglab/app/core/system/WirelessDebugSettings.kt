package com.loglab.app.core.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * 无线调试的系统开关读写。
 *
 * 移植自 Shizuku 的 `AdbDialogFragment.onDialogShow()` 与 `BootCompleteReceiver.adbStart()`。
 * 机制来源：RikkaApps/Shizuku 13.6.0
 *
 * 为什么需要 `WRITE_SECURE_SETTINGS`：
 *  - `adb_wifi_enabled` / `ADB_ENABLED` / `adb_allowed_connection_time` 都是
 *    Settings.Global 下的受保护设置项，普通 App 写入会被静默拒绝；
 *  - Shizuku 的做法是「先用 shell 权限 pm grant 给自己授权」，见 [SelfGrant]。
 *
 * 三个开关的作用：
 *  - `adb_wifi_enabled`           开关「无线调试」（关键项）
 *  - `Settings.Global.ADB_ENABLED` 开关「USB 调试」（adbd 总开关）
 *  - `adb_allowed_connection_time`  连接有效期，0 = 永不过期（默认 7 天）
 */
object WirelessDebugSettings {

    /** 受保护权限名，需与 AndroidManifest 声明一致 */
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /** 「无线调试」开关 */
    private const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** 连接有效期：0 表示永不断开 */
    private const val KEY_ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    /**
     * 当前是否已获得 WRITE_SECURE_SETTINGS。
     *
     * 注意：这是 **signature|privileged** 级权限，普通安装的 App 默认拿不到，
     * 必须由 shell/root 通过 `pm grant` 授予。
     */
    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 「无线调试」当前是否开启（只读，不需要权限） */
    fun isWirelessDebugEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, KEY_ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    /** 「USB 调试」当前是否开启（只读，不需要权限） */
    fun isAdbEnabled(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)

    /**
     * 开启无线调试（需要 WRITE_SECURE_SETTINGS）。
     *
     * 写完会**回读确认** —— `putInt` 不抛异常不代表写入成功，
     * 部分 ROM 会静默忽略（返回 true 但值没变），必须回读才能判定。
     *
     * @return true = 确认已开启
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun enableWirelessDebug(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        val cr = context.contentResolver
        return runCatching {
            Settings.Global.putInt(cr, KEY_ADB_WIFI_ENABLED, 1)
            @Suppress("DEPRECATION")
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, KEY_ADB_ALLOWED_CONNECTION_TIME, 0L)
            // 回读确认，杜绝「假成功」
            Settings.Global.getInt(cr, KEY_ADB_WIFI_ENABLED, 0) == 1
        }.getOrDefault(false)
    }

    /** 诊断信息：把三个开关的实际值拼成一行，便于日志排查 */
    fun describe(context: Context): String = buildString {
        val cr = context.contentResolver
        append("wireless=").append(isWirelessDebugEnabled(context))
        append(" adb=").append(isAdbEnabled(context))
        append(" connTime=").append(
            runCatching {
                Settings.Global.getLong(cr, KEY_ADB_ALLOWED_CONNECTION_TIME, -1L)
            }.getOrDefault(-1L)
        )
        append(" granted=").append(hasWriteSecureSettings(context))
    }
}
