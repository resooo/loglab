package com.loglab.app.ui.crash

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.core.apps.AppInfoProvider
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.crash.CrashEvent
import com.loglab.app.core.crash.CrashParser
import com.loglab.app.core.crash.CrashStore
import com.loglab.app.core.report.AppLogger
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 崩溃列表时间范围：今天 / 近 7 天 */
enum class CrashRange(val days: Int, val label: String) {
    TODAY(1, "今天"),
    SEVEN_DAYS(7, "近7天");

    /** 该范围内的最早日期（yyyy-MM-dd） */
    fun sinceDate(): String =
        java.time.LocalDate.now().minusDays((days - 1).toLong()).toString()
}

@HiltViewModel
class CrashViewModel @Inject constructor(
    private val channelManager: ChannelManager,
    private val settings: SettingsRepository,
    private val appInfoProvider: AppInfoProvider,
    val store: CrashStore,
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) : ViewModel() {

    val events = store.events
    val monitoring = store.monitoring
    val message = store.message

    val channelState = channelManager.state

    /** 当前时间范围（默认今天） */
    var range by mutableStateOf(CrashRange.TODAY)
        private set

    /** 按时间范围过滤后的列表（store 只保留 7 天，这里再按页签裁剪） */
    val filteredEvents: List<CrashEvent>
        get() {
            val since = range.sinceDate()
            return events.value.filter { it.time.take(10) >= since }
        }

    fun onRangeChange(value: CrashRange) {
        range = value
    }

    /** 行图标：本机 PackageManager 毫秒级取，取不到返回 null（UI 显示默认占位） */
    fun icon(packageName: String?): Drawable? =
        packageName?.let { appInfoProvider.icon(it) }

    /** 应用名：label 优先，取不到回落包名 */
    fun appLabel(event: CrashEvent): String? {
        val pkg = event.packageName ?: return null
        return appInfoProvider.labelOf(pkg)
    }

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
     * 读取历史崩溃（logcat -b crash -d 一次性输出，回放最近 7 天）。
     * 没来得及开着监控就崩溃过的，用这个补抓；入库与展示再按时间范围过滤。
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
                // 回放窗口=保留窗口（7 天）：-T 时间参数不破坏缓冲区，只是截断输出
                val since = java.time.LocalDate.now().minusDays(6)
                val sinceStr = "%02d-%02d 00:00:00.000".format(since.monthValue, since.dayOfMonth)
                channel.execute("logcat -b crash -d -v time -T \"$sinceStr\"").getOrThrow()
            }
            output.onSuccess { text ->
                val parser = CrashParser()
                val found = text.lineSequence().mapNotNull { parser.feed(it) }.toList() +
                    listOfNotNull(parser.flush())
                val added = store.addAll(found)
                store.setMessage(
                    if (found.isEmpty()) "没有找到历史崩溃（最近 7 天内还没有应用崩溃过）"
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
