package com.loglab.app.ui.crash

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.crash.CrashParser
import com.loglab.app.core.crash.CrashStore
import com.loglab.app.core.report.AppLogger
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CrashViewModel @Inject constructor(
    private val channelManager: ChannelManager,
    private val settings: SettingsRepository,
    val store: CrashStore,
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) : ViewModel() {

    val events = store.events
    val monitoring = store.monitoring
    val message = store.message

    val channelState = channelManager.state

    /** 开始监控（交给前台服务，离开 App 也不断） */
    fun start() {
        val intent = Intent(context, com.loglab.app.service.CrashMonitorService::class.java)
            .setAction(com.loglab.app.service.CrashMonitorService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { store.setMessage("启动失败：${it.message}") }
    }

    fun stop() {
        val intent = Intent(context, com.loglab.app.service.CrashMonitorService::class.java)
            .setAction(com.loglab.app.service.CrashMonitorService.ACTION_STOP)
        runCatching { context.startService(intent) }
    }

    /**
     * 读取历史崩溃（logcat -b crash -d 一次性输出，只取当天的）。
     * 没来得及开着监控就崩溃过的，用这个补抓。
     */
    fun readHistory() {
        viewModelScope.launch {
            store.setMessage("正在读取历史崩溃…")
            logger.log("CRASH", "读取历史崩溃开始")
            val output = runCatching {
                val channel = channelManager.active()
                    ?: channelManager.autoConnect(settings.policyOnce())
                        .getOrThrow().let { channelManager.active() }
                    ?: error("ADB 未连接")
                val today = java.time.LocalDate.now()
                val since = "%02d-%02d 00:00:00.000".format(today.monthValue, today.dayOfMonth)
                channel.execute("logcat -b crash -d -v time -T \"$since\"").getOrThrow()
            }
            output.onSuccess { text ->
                val parser = CrashParser()
                val found = text.lineSequence().mapNotNull { parser.feed(it) }.toList() +
                    listOfNotNull(parser.flush())
                val added = store.addAll(found)
                store.setMessage(
                    if (found.isEmpty()) "没有找到历史崩溃（开机以来还没有应用崩溃过）"
                    else "读取到 ${found.size} 条历史崩溃（新增 $added 条）"
                )
                logger.log("CRASH", "历史崩溃读取完成：${found.size} 条，新增 $added 条")
            }.onFailure {
                store.setMessage("读取失败：${it.message}")
                logger.log("CRASH", "历史崩溃读取失败：${it.message}", it)
            }
        }
    }

    fun clear() = store.clear()

    /** 系统分享全部崩溃记录 */
    fun shareAll() {
        val text = store.exportText()
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "应用崩溃记录")
            putExtra(Intent.EXTRA_TEXT, text)
            // ViewModel 持有的是 Application context，非 Activity 启动必须加此 flag
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // chooser 是独立的新 Intent，flag 不会从 target 带过来，必须单独加
        val chooser = Intent.createChooser(send, "分享崩溃记录")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
            .onFailure { store.setMessage("分享失败：${it.message}") }
    }
}
