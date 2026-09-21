package com.loglab.app.core.system

import android.content.Context
import android.util.Log
import com.loglab.app.core.channel.Channel

/**
 * 用 LogLab 自己的 ADB 通道，给自己授予 `WRITE_SECURE_SETTINGS`。
 *
 * ## 原理
 *
 * 移植自 Shizuku 的 `ShizukuService.grantRuntimePermission(...)` 调用（13.6.0）。
 *
 * 链路：
 * ```
 * ① 用户完成一次无线调试配对（配对码由系统界面显示，App 无法读取）
 * ② 连上本机 adbd，此后执行的 shell 命令身份是 shell (uid 2000)
 * ③ shell 天然拥有 `pm grant` 的能力 → 给自己授 WRITE_SECURE_SETTINGS
 * ④ 拿到权限后即可写 Settings.Global，开启无线调试，形成自动化闭环
 * ```
 *
 * 这不是提权漏洞 —— `pm grant` 本就是 ADB shell 的标准能力，
 * Shizuku 也只是把「用户手动敲命令」这一步自动化了。
 *
 * ## 为什么值得做
 *
 * 拿到这个权限后可以：
 *  - 自动开启「无线调试」，用户不用每次去开发者选项手动点
 *  - 把连接有效期设为 0（永不过期），避免 7 天后突然连不上
 *  - 关机重启后自动恢复连接
 *
 * ## 幂等性
 *
 * 权限一旦授予会持久化（直到 App 卸载或重装）。
 * 本类会先检查、已有则跳过，不会重复执行 `pm grant`。
 */
object SelfGrant {

    private const val TAG = "SelfGrant"

    /** `pm grant` 的标记串，用于在混合输出中定位执行结果 */
    private const val LOG_TAG = "LOGLAB_SELFGRANT"

    /**
     * 确保 `WRITE_SECURE_SETTINGS` 已授予。
     *
     * @param context 用于回读权限状态（`checkSelfPermission` 是 App 侧权威判据）
     * @param channel 当前可用的 ADB 通道（必须已在连接状态）
     * @return 成功时携带 true 表示「本次新授予」，false 表示「原本就有」
     */
    suspend fun ensureWriteSecureSettings(
        context: Context,
        channel: Channel
    ): Result<Boolean> {
        // ① 已有权限 → 直接返回，避免无谓的 shell 往返
        if (WirelessDebugSettings.hasWriteSecureSettings(context)) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS 已授予，跳过")
            return Result.success(false)
        }

        val packageName = context.packageName
        val permission = WirelessDebugSettings.WRITE_SECURE_SETTINGS

        // ② 执行 pm grant。注意：成功时无输出、失败也常常无输出，
        //    因此不能靠 stdout 判断，必须回读 checkSelfPermission。
        val command = buildString {
            append("pm grant ").append(packageName).append(' ').append(permission)
            append(" 2>&1; echo \"").append(LOG_TAG).append("_EXIT=$?\"")
        }

        val output = channel.execute(command).getOrElse { e ->
            Log.w(TAG, "pm grant 执行失败", e)
            return Result.failure(e)
        }
        Log.i(TAG, "pm grant 输出: ${output.trim()}")

        // ③ 回读确认 —— 唯一可信的判据
        val granted = WirelessDebugSettings.hasWriteSecureSettings(context)
        Log.i(TAG, "回读结果: granted=$granted")

        return if (granted) {
            Result.success(true)
        } else {
            Result.failure(
                IllegalStateException("pm grant 未能授予权限，shell 输出：${output.trim()}")
            )
        }
    }

    /**
     * 检查并（在需要时）授予权限，附带完整诊断日志。
     *
     * 供 `StartupCheck` 在连接成功后调用：一步到位完成自授权限 + 开启无线调试。
     *
     * @return 是否最终具备写入能力
     */
    suspend fun ensureAndReport(context: Context, channel: Channel): Boolean {
        Log.i(TAG, "开始自授权限；当前状态 ${WirelessDebugSettings.describe(context)}")

        val grantResult = ensureWriteSecureSettings(context, channel)
        if (grantResult.isFailure) {
            Log.w(TAG, "自授权限失败", grantResult.exceptionOrNull())
            return false
        }

        // 权限到手后顺手把连接有效期设为永不过期
        val enabled = WirelessDebugSettings.enableWirelessDebug(context)
        Log.i(TAG, "自授权限完成；开启无线调试=$enabled；状态 ${WirelessDebugSettings.describe(context)}")
        return WirelessDebugSettings.hasWriteSecureSettings(context)
    }
}
