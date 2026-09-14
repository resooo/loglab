package com.loglab.app.ui.crash

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.R
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
    val messageIsError = store.messageIsError

    val channelState = channelManager.state

    /** 当前时间范围（默认今天） */
    var range by mutableStateOf(CrashRange.TODAY)
        private set

    /**
     * 按时间范围过滤后的列表（store 只保留 7 天，这里再按页签裁剪）。
     *
     * ★ 用 derivedStateOf 而不是裸 getter：裸 getter 每次重组都会重算，
     * 且 Compose 无法把它登记成快照依赖——清空、新增记录时 UI 不会自动刷新。
     * derivedStateOf 会跟踪 events / range 两个 State 源，任一变化即失效并通知重组。
     */
    val filteredEvents: List<CrashEvent> by derivedStateOf {
        val since = range.sinceDate()
        events.value.filter { it.time.take(10) >= since }
    }

    /**
     * 强制刷新：给「离开 App 期间后台服务写入了新崩溃」这个场景兜底。
     *
     * filteredEvents 已经是 derivedStateOf，正常情况下 events 一变就自动失效。
     * 但存在一种竞态：服务在协程里改 events、界面同时在后台被冻结，回到前台时
     * 只发生了一次组合（没有额外的 State 写入触发第二次），列表就停在旧快照上。
     * 这里改一个由组合读取的状态值，把依赖显式推翻，逼出一次真正的新组合。
     *
     * 每次切回前台都会调，所以只在「内容真的变了」时才动 [listEpoch]，
     * 避免用户正在滚动列表时被无谓重建、丢失滚动位置。
     */
    fun refresh() {
        // events 是 StateFlow<List>，每次写入都会换成新引用。这里保持「引用变了才推进」
        // 的语义，避免用户滚动列表时被无谓重建；监控中的即时刷新由 listAnchor 的
        // 内容派生部分（见下）负责，不再依赖本函数。
        val snapshot = events.value
        if (snapshot !== lastSeen) {
            lastSeen = snapshot
            listEpoch++
        }
    }

    private var lastSeen: List<CrashEvent>? = null

    private var listEpoch by mutableStateOf(0)

    /**
     * 列表锚点：LazyColumn 的 key 前缀。
     *
     * 由两部分组合而成：
     *  1. [listEpoch] —— 清空 / 回到前台刷新时递增，整表替换场景用；
     *  2. **内容指纹** —— 记录条数与首条记录的标识。
     *
     * ★ 第二部分是关键：监控运行期间页面一直是「热」的，不会触发 ON_RESUME，
     *   而新崩溃是按时间**插入列表中段**（不是头部追加）。此时若锚点不变，
     *   LazyColumn 会认为那些 key 还活着而复用旧布局，界面看起来「监控了但没刷新」。
     *   把内容指纹并入锚点后，events 一变锚点立刻变，列表即时重绘。
     *
     * 指纹只用稳定量（size + 首条的时间/包名/类型），不用 hashCode 之类的
     * 概率性数值，避免碰撞导致该刷新时不刷新。
     */
    val listAnchor: Int get() = listEpoch

    /** 内容版本：条数 + 首条标识，任何新增/清空都会改变它 */
    val contentVersion: String
        get() = events.value.let { list ->
            if (list.isEmpty()) "empty" else "${list.size}-${list[0].time}-${list[0].packageName}-${list[0].type}"
        }

    /**
     * 清空全部记录。
     *
     * ★ 必须同时推进 [listEpoch]：清空后列表变空，但 LazyColumn 的 item key
     *   仍是按「内容」生成的字符串。若 key 集合与重建前有交集，Compose 会认为
     *   那些项还活着而复用旧布局，界面看起来「点了没反应」。清空属于整表替换，
     *   直接换锚点让 key 前缀整体失效，比逐项 diff 更可靠。
     */
    fun clear() {
        store.clear()
        lastSeen = events.value
        listEpoch++
    }

    fun onRangeChange(value: CrashRange) {
        range = value
    }

    /**
     * 行图标：本机 PackageManager 毫秒级取，取不到返回 null（UI 显示首字母占位）。
     *
     * 按 96px 解码：列表图标渲染尺寸 32dp，在 3x 屏上正好对应 96px，
     * 既不会因原图过大而反复缩放，也不会放大发虚。
     */
    fun icon(packageName: String?): Drawable? =
        packageName?.let { appInfoProvider.icon(it, ICON_PX) }

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
            .onFailure { store.setMessage(context.getString(R.string.crash_msg_start_failed_fmt, it.message.orEmpty()), isError = true) }
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
            store.setMessage(context.getString(R.string.crash_msg_reading))
            logger.log("CRASH", "读取历史崩溃开始")
            val output = runCatching {
                val channel = channelManager.active()
                    ?: channelManager.autoConnect(settings.policyOnce())
                        .getOrThrow().let { channelManager.active() }
                    ?: error("ADB 未连接")
                // 回放窗口=保留窗口（7 天）。
                // ★ -T 的时间格式必须是 "MM-dd HH:mm:ss.SSS"（**不带年份**）：
                //   logcat 的 -T 只按此格式解析，实测带年份的 ISO 8601
                //   （"2026-09-14T00:00:00.000"）会被判为不匹配而过滤掉**全部**输出，
                //   表现为「历史崩溃一条都读不到」。同理单独给 "MM-dd" 也不行。
                val since = java.time.LocalDate.now().minusDays(6)
                val sinceStr = "%02d-%02d 00:00:00.000".format(since.monthValue, since.dayOfMonth)
                channel.execute("logcat -b crash -d -v time -T \"$sinceStr\"").getOrThrow()
            }
            output.onSuccess { text ->
                // ★ 必须与 CrashMonitorService 用同一套「段边界 + feed」逻辑：
                //   CrashParser 是增量解析器，只在遇到下一条崩溃的开头时才产出上一条，
                //   最后一条会一直留在解析器里——缓冲区尾部静默时它就永远出不来。
                //   仅靠末尾一次 flush() 不够（顺序也反了：flush 应在 feed 之后），
                //   这正是「读取历史提示没有找到、切到近 7 天却能看到」的根因。
                val parser = CrashParser()
                val found = buildList {
                    text.lineSequence().forEach { line ->
                        if (parser.isSegmentBoundary(line)) parser.flush()?.let { add(it) }
                        parser.feed(line)?.let { add(it) }
                    }
                    parser.flush()?.let { add(it) }
                }
                val added = store.addAll(found)
                // 提示文案区分三种情况，避免「读到重复记录」被误报成「没找到」：
                //   found 为空          → 最近 7 天确实没有崩溃
                //   found 非空但新增 0  → 记录已存在（去重命中）
                store.setMessage(
                    when {
                        found.isEmpty() -> context.getString(R.string.crash_msg_none)
                        added == 0 -> context.getString(R.string.crash_msg_all_known_fmt, found.size)
                        else -> context.getString(R.string.crash_msg_found_fmt, found.size, added)
                    }
                )
                logger.log("CRASH", "历史崩溃读取完成：解析 ${found.size} 条，新增 $added 条")
            }.onFailure {
                store.setMessage(context.getString(R.string.crash_msg_read_failed_fmt, it.message.orEmpty()), isError = true)
                logger.log("CRASH", "历史崩溃读取失败：${it.message}", it)
            }
        }
    }

    /**
     * 分享一份诊断包：崩溃记录 + 运行日志 + 设备信息。
     *
     * 「监控到了但不显示」这类问题，光看界面无法判断卡在哪一环——是流没读起来、
     * 解析器没认出来、被去重挡了，还是只差界面没刷新。把过程日志一并给出，
     * 排查一次到位，不用靠反复来回猜。
     */
    fun shareDiagnostics() {
        val text = logger.diagnostics(context, store.exportText())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LogLab 诊断信息")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "分享诊断信息")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
            .onFailure {
                store.setMessage(
                    context.getString(R.string.crash_msg_share_failed_fmt, it.message.orEmpty()),
                    isError = true
                )
            }
    }

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
            .onFailure { store.setMessage(context.getString(R.string.crash_msg_share_failed_fmt, it.message.orEmpty()), isError = true) }
    }

    private companion object {
        /** 行图标解码边长（px）：32dp 在 3x 屏上的像素量级 */
        const val ICON_PX = 96
    }
}
