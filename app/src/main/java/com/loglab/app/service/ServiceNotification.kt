package com.loglab.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.loglab.app.MainActivity
import com.loglab.app.R

/**
 * 前台服务常驻通知的**统一构建器**。
 *
 * ## 为什么抽出来
 *
 * 历史上 [LogTailService] 与 [CrashMonitorService] 各自维护了一份
 * `buildNotification` / `createChannel` / `updateNotification`，
 * 三个方法**逐字重复**，只有 6 个参数不同。这带来两个实际问题：
 *  1. 改一处样式（比如加个 action、调图标）必须记得改两处，漏改就出现视觉不一致；
 *  2. 通知渠道（NotificationChannel）的创建逻辑复制两份，
 *     渠道名/描述走的是不同字符串资源，容易顾此失彼。
 *
 * 现在统一到这里：两个 Service 只说「我是谁、显示什么文案」，
 * 其余（渠道创建、PendingIntent 构造、Builder 配置）全部共享。
 *
 * ## 设计取舍
 *
 * 采用**参数对象** [Spec] 而非长参数列表：调用点更易读，
 * 且新增字段时不必修改所有调用方签名（给默认值即可）。
 */
object ServiceNotification {

    /**
     * 一个前台服务通知的差异描述。
     *
     * 刻意只保留「两个 Service 确实不同」的字段：
     * 相同部分（ongoing / onlyAlertOnce / 图标 / 停止按钮）统一在 [build] 里写死，
     * 保证所有常驻通知行为一致 —— 这正是抽取公共实现的价值所在。
     *
     * @param channelId     通知渠道 ID（各服务独立，用户可分别静音）
     * @param channelName   渠道显示名
     * @param channelDesc   渠道描述（系统设置里展示给用户）
     * @param notificationId 通知 ID（同一服务复用，实现「原地更新」而非堆积）
     * @param title         通知标题
     * @param serviceClass  该前台服务的类（用于构造「停止」按钮的 PendingIntent）
     * @param stopAction    停止动作的 Intent action
     * @param openExtra     点击通知打开 App 时要携带的 extra（可为 null）
     */
    data class Spec(
        val channelId: String,
        val channelName: String,
        val channelDesc: String,
        val notificationId: Int,
        val title: String,
        val serviceClass: Class<*>,
        val stopAction: String,
        val openExtra: Pair<String, Boolean>? = null
    )

    /**
     * 构建常驻通知。会先确保渠道已创建。
     *
     * @param text 通知正文（随时间变化的动态文案，如「已捕获 3 个」）
     */
    fun build(context: Context, spec: Spec, text: String): Notification {
        ensureChannel(context, spec)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            spec.openExtra?.let { (key, value) -> putExtra(key, value) }
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode(spec, REQUEST_OPEN),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context,
            requestCode(spec, REQUEST_STOP),
            Intent(context, spec.serviceClass).setAction(spec.stopAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_stat_tail)
            .setContentTitle(spec.title)
            .setContentText(text)
            // ↓ 以下四项对所有常驻通知都应一致，故写死于此
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_stat_tail, context.getString(R.string.action_stop), stopIntent)
            .build()
    }

    /**
     * 更新（或首次发布）通知。
     *
     * 用同一个 [Spec.notificationId] 调用 `notify` —— 系统会**原地替换**，
     * 不会堆积多条通知，这是前台服务通知的标准做法。
     */
    fun update(context: Context, spec: Spec, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(spec.notificationId, build(context, spec, text))
    }

    /**
     * 确保通知渠道存在。幂等 —— `createNotificationChannel` 对已存在的渠道
     * 只会更新名称/描述，不会重置用户已改过的设置。
     */
    private fun ensureChannel(context: Context, spec: Spec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(spec.channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                spec.channelId,
                spec.channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = spec.channelDesc }
        )
    }

    /**
     * PendingIntent 的 requestCode。
     *
     * 必须是「同一服务内稳定、不同服务间不冲突」的值：
     *  - 稳定：用 notificationId 做基址，同一个服务的两个 PendingIntent 用不同偏移；
     *  - 不冲突：不同服务 notificationId 不同（2102 / 2101），天然隔离。
     *
     * 历史实现是硬编码 0/1/2/3，两个服务各自占用相邻的号，
     * 一旦新增服务就要人工挑号，容易撞车 —— 这里改为按 notificationId 推导。
     */
    private fun requestCode(spec: Spec, kind: Int): Int =
        spec.notificationId * 10 + kind

    private const val REQUEST_OPEN = 0
    private const val REQUEST_STOP = 1
}
