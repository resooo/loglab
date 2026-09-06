package com.loglab.app.service

import com.loglab.app.core.logcat.LogcatConfig
import com.loglab.app.core.report.AppLogger
import com.loglab.app.data.model.LogEntry
import com.loglab.app.data.repository.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class TailState(
    val running: Boolean = false,
    val packageName: String? = null,
    val pid: Int? = null,
    val received: Int = 0,
    val ratePerSecond: Float = 0f,
    val error: String? = null,
    val statusLine: String = ""
)

/**
 * 实时跟踪会话：由前台服务与 UI 共享。
 *
 * UI 离开页面后服务仍在收集日志，回到页面时直接读取缓冲，不丢日志。
 *
 * 性能关键——攒批刷新：
 *   高频日志（每秒上百条）若逐条更新 StateFlow，每次都会触发
 *   全列表拷贝 + LazyColumn 全量 key diff + 滚动协程取消重启，
 *   主线程被打爆后表现为「不自动滚动 + 卡顿闪退」。
 *   因此日志先进无界队列，由单一消费者每 100ms 合并一批刷入状态，
 *   UI 更新频率降低一个数量级，滚动协程有时间完成贴底。
 */
@Singleton
class TailSession @Inject constructor(
    private val repository: LogRepository,
    private val logger: AppLogger
) {
    companion object {
        const val MAX_BUFFER = 5000
        /** UI 刷新节流间隔 */
        const val FLUSH_INTERVAL_MS = 100L
        /** 队列积压超过该条数时跳过节流立即刷出，防止延迟无限累积 */
        const val IMMEDIATE_FLUSH = 256
        /** PID 跟随轮询间隔：目标应用重启后 PID 会变，需要重建日志流 */
        const val PID_POLL_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lines = MutableStateFlow<List<LogEntry>>(emptyList())
    val lines: StateFlow<List<LogEntry>> = _lines.asStateFlow()

    private val _state = MutableStateFlow(TailState())
    val state: StateFlow<TailState> = _state.asStateFlow()

    /** 每次会话独立队列：旧会话残留随旧队列一起丢弃 */
    private var queue = Channel<LogEntry>(Channel.UNLIMITED)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var paused = false

    private var counter = 0
    private var lastRateCheck = 0L

    val isActive: Boolean get() = job?.isActive == true

    fun start(packageName: String?, config: LogcatConfig) {
        if (isActive) return
        clear()
        paused = false
        counter = 0
        lastRateCheck = System.currentTimeMillis()
        _state.value = TailState(
            running = true,
            packageName = packageName,
            statusLine = "正在连接…"
        )
        queue = Channel(Channel.UNLIMITED)

        job = scope.launch {
            var producer: Job? = null
            // 生产者：日志流 → 无界队列（trySend 永不阻塞收集循环）
            fun startProducer() {
                producer?.cancel()
                producer = launch {
                    repository.tail(packageName, config).collect { entry ->
                        if (!paused) queue.trySend(entry)
                    }
                }
            }
            startProducer()
            // PID 跟随：目标应用被杀/重启/起了子进程后，旧 --pid 流再也收不到它的日志，
            // 这里定期比对 PID 集合，变了就重建流（否则表现为"跟踪突然静默"）
            val watcher = if (!packageName.isNullOrBlank()) {
                launch { watchPid(packageName, ::startProducer) }
            } else {
                null
            }
            // 消费者：攒批 + 节流刷新
            val batch = ArrayList<LogEntry>(IMMEDIATE_FLUSH)
            try {
                while (isActive) {
                    batch.clear()
                    batch.add(queue.receive())
                    while (batch.size < IMMEDIATE_FLUSH) {
                        val more = queue.tryReceive().getOrNull() ?: break
                        batch.add(more)
                    }
                    appendBatch(batch)
                    if (batch.size >= IMMEDIATE_FLUSH) continue
                    delay(FLUSH_INTERVAL_MS)
                }
            } finally {
                producer?.cancel()
                watcher?.cancel()
            }
        }

        scope.launch {
            while (isActive && scope.isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastRateCheck).coerceAtLeast(1) / 1000f
                val rate = counter / elapsed
                counter = 0
                lastRateCheck = now
                _state.value = _state.value.copy(ratePerSecond = rate)
            }
        }
    }

    /**
     * PID 跟随：轮询目标包名的 PID 集合，变化则重建日志流。
     *
     * 防抖：需要连续两次轮询拿到同一个"新集合"才重建——pgrep 在进程启停瞬间
     * 可能给出抖动结果，否则会频繁重启流导致漏日志。
     */
    private suspend fun watchPid(packageName: String, restart: () -> Unit) {
        var last = runCatching { repository.resolvePids(packageName) }.getOrDefault(emptyList())
        var pending: List<Int>? = null
        while (true) {
            delay(PID_POLL_MS)
            val now = runCatching { repository.resolvePids(packageName) }.getOrDefault(emptyList())
            if (now.isEmpty() || now.toSet() == last.toSet()) {
                pending = null
                continue
            }
            if (pending == now) {
                logger.log("TAIL", "目标进程变化：${last.joinToString(",")} → ${now.joinToString(",")}，重建日志流")
                queue.trySend(
                    LogEntry(
                        raw = "检测到 $packageName 进程变化：PID ${now.joinToString(",")}，已重新跟踪",
                        tag = "LogLab"
                    )
                )
                last = now
                pending = null
                restart()
            } else {
                pending = now
            }
        }
    }

    fun setPaused(pause: Boolean) {
        paused = pause
        _state.value = _state.value.copy(statusLine = if (pause) "已暂停" else "跟踪中")
    }

    fun stop() {
        job?.cancel()
        job = null
        paused = false
        _state.value = _state.value.copy(running = false, ratePerSecond = 0f, statusLine = "已停止")
    }

    fun clear() {
        _lines.value = emptyList()
        _state.value = _state.value.copy(received = 0)
    }

    private fun appendBatch(batch: List<LogEntry>) {
        if (batch.isEmpty()) return
        counter += batch.size
        val current = _lines.value
        val next = ArrayList<LogEntry>(current.size + batch.size)
        next.addAll(current)
        next.addAll(batch)
        if (next.size > MAX_BUFFER) {
            // 保留最新的 MAX_BUFFER 条（丢弃最旧）
            val overflow = next.size - MAX_BUFFER
            _lines.value = ArrayList(next.subList(overflow, next.size))
        } else {
            _lines.value = next
        }
        _state.value = _state.value.copy(
            received = _lines.value.size,
            pid = batch.lastOrNull()?.pid?.takeIf { it.isNotBlank() }?.toIntOrNull()
                ?: _state.value.pid,
            statusLine = "跟踪中"
        )
    }
}
