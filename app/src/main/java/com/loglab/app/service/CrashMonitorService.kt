package com.loglab.app.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
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
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.crash.CrashEvent

/**
 * 应用崩溃监控前台服务：用户离开 App 去复现崩溃时保持监听不断。
 *
 * 原理：流式读取 `logcat -b crash`（系统崩溃缓冲区，所有应用的
 * Java 闪退 / Native 崩溃 / ANR 都会写入），解析成事件后交给 [CrashStore]。
 */
@AndroidEntryPoint
class CrashMonitorService : android.app.Service() {

    @Inject lateinit var channelManager: ChannelManager
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
                // 不加 -T 的话每次开启监控都把几天前的老崩溃重新解析一遍（刷屏且无意义）。
                // ★ 时间格式必须是 "MM-dd HH:mm:ss.SSS"（不带年份）：
                //   logcat 的 -T 只按此格式解析，带年份的 ISO 8601 会让输出全被过滤，
                //   表现为「开始监控后一条都读不到」。
                val today = java.time.LocalDate.now()
                val since = "%02d-%02d 00:00:00.000".format(today.monthValue, today.dayOfMonth)
                channelManager.adbChannel.executeStream("logcat -b crash -v time -T \"$since\"")
                    .collect { line ->
                        // ★ 段边界先于 feed 判断：feed 会「用新行结束旧块」，
                        //   等它返回事件时新行已被吃掉，无法区分「刚结束的崩溃」与续行。
                        //   这里在末行到达时就把上一段提前产出，解决崩溃缓冲区尾部静默
                        //   导致的「已抓到但不显示」。
                        if (parser.isSegmentBoundary(line)) parser.flush()?.let { publish(it) }
                        parser.feed(line)?.let { publish(it) }
                    }
                parser.flush()?.let { publish(it) }
                scheduleRetry("日志流意外结束")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log("CRASH", "监控中断：${e.message}", e)
                scheduleRetry(e.message ?: "未知错误")
            }
        }
    }

    /**
     * 入库一条捕获到的崩溃，并记录一条可诊断的运行日志。
     *
     * 加日志的原因：用户报「监控到了但没显示」时，光看 store 有没有数据无法判断
     * 是被去重挡了、被 7 天窗口挡了，还是解析器压根没认出——把包名/类型/时间
     * 连同入库结果一起写下来，设置页的运行日志里就能直接定位。
     */
    private fun publish(event: CrashEvent) {
        val added = store.add(event)
        logger.log(
            "CRASH",
            "解析到崩溃：${event.packageName ?: "（未解析到包名）"} · ${event.type} · " +
                "${event.time.ifEmpty { "（无时间）" }} · ${if (added) "已入库" else "已存在/超期，跳过"}"
        )
    }

    /** 断流自动重连（无线调试开关切换/网络抖动都会断一次），最多 5 次后放弃 */
    private suspend fun scheduleRetry(reason: String) {        if (!store.monitoring.value) return
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

    /**
     * 通知的差异描述。相同部分（图标 / ongoing / 停止按钮）统一在
     * [ServiceNotification.build] 内实现，此处只声明「本服务是谁」。
     */
    private val notificationSpec: ServiceNotification.Spec by lazy {
        ServiceNotification.Spec(
            channelId = CHANNEL_ID,
            channelName = getString(R.string.notification_channel_name),
            channelDesc = getString(R.string.notification_channel_desc),
            notificationId = NOTIFICATION_ID,
            title = "应用崩溃监控",
            serviceClass = CrashMonitorService::class.java,
            stopAction = ACTION_STOP
        )
    }

    private fun updateNotification(text: String) {
        ServiceNotification.update(this, notificationSpec, text)
    }

    private fun buildNotification(text: String): Notification =
        ServiceNotification.build(this, notificationSpec, text)

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
