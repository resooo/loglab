package com.loglab.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.loglab.app.MainActivity
import com.loglab.app.R
import com.loglab.app.core.crash.CrashParser
import com.loglab.app.core.crash.CrashStore
import com.loglab.app.core.report.AppLogger
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * 应用崩溃监控前台服务：用户离开 App 去复现崩溃时保持监听不断。
 *
 * 原理：流式读取 `logcat -b crash`（系统崩溃缓冲区，所有应用的
 * Java 闪退 / Native 崩溃 / ANR 都会写入），解析成事件后交给 [CrashStore]。
 */
@AndroidEntryPoint
class CrashMonitorService : android.app.Service() {

    @Inject lateinit var channelManager: com.loglab.app.core.channel.ChannelManager
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var store: CrashStore
    @Inject lateinit var logger: AppLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private val retries = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("正在监听应用崩溃…"),
                    if (Build.VERSION.SDK_INT >= 34) {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    }
                )
                store.setMonitoring(true)
                startMonitor()
            }

            ACTION_STOP -> stopMonitor()
        }
        return START_STICKY
    }

    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.IO) {
            // 确保 ADB 已连接（最多自动重试 2 次）
            var connected = runCatching {
                channelManager.autoConnect(settings.policyOnce()).isSuccess
            }.getOrDefault(false)
            var attempt = 0
            while (!connected && attempt < 2) {
                attempt++
                store.setMessage("连接 ADB 失败，正在重试（$attempt/2）…")
                delay(2_000)
                connected = runCatching {
                    channelManager.autoConnect(settings.policyOnce()).isSuccess
                }.getOrDefault(false)
            }
            if (!connected) {
                store.setMessage("无法连接 ADB：请确认「无线调试」已开启，然后回到本页重新开始监控")
                stopForegroundAndSelf()
                return@launch
            }
            store.setMessage(null)
            logger.log("CRASH", "开始监控崩溃（logcat -b crash）")
            observeCaptureCount()

            val parser = CrashParser()
            try {
                // 只从当天 0 点开始回放：crash buffer 会保留开机以来的全部崩溃，
                // 不加 -T 的话每次开启监控都把几天前的老崩溃重新解析一遍（刷屏且无意义）
                val today = java.time.LocalDate.now()
                val since = "%02d-%02d 00:00:00.000".format(today.monthValue, today.dayOfMonth)
                channelManager.adbChannel.executeStream("logcat -b crash -v time -T \"$since\"")
                    .collect { line ->
                        parser.feed(line)?.let { store.add(it) }
                    }
                parser.flush()?.let { store.add(it) }
                scheduleRetry("日志流意外结束")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log("CRASH", "监控中断：${e.message}", e)
                scheduleRetry(e.message ?: "未知错误")
            }
        }
    }

    /** 断流自动重连（无线调试开关切换/网络抖动都会断一次），最多 5 次后放弃 */
    private suspend fun scheduleRetry(reason: String) {
        if (!store.monitoring.value) return
        val n = retries.incrementAndGet()
        if (n > 5) {
            store.setMessage("多次重连失败（$reason），已停止监控，请检查网络后重新开始")
            stopForegroundAndSelf()
            return
        }
        store.setMessage("监控中断（$reason），正在自动重连（$n/5）…")
        delay(3_000)
        if (store.monitoring.value) startMonitor()
    }

    private fun observeCaptureCount() {
        scope.launch {
            store.events.map { it.size }.distinctUntilChanged().collect { n ->
                updateNotification("正在监听应用崩溃 · 已捕获 $n 个")
            }
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        store.setMonitoring(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        createChannel()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, CrashMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tail)
            .setContentTitle("应用崩溃监控")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_stat_tail, "停止", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "崩溃监控",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "监控应用崩溃时保持后台运行" }
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "crash_monitor"
        const val NOTIFICATION_ID = 2102
        const val ACTION_START = "com.loglab.app.action.START_CRASH_MONITOR"
        const val ACTION_STOP = "com.loglab.app.action.STOP_CRASH_MONITOR"
    }
}
