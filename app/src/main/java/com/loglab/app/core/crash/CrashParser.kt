package com.loglab.app.core.crash

/**
 * crash buffer（logcat -b crash -v time）行流解析器。
 *
 * 两种行形态，都必须正确处理：
 *  1. **多行单条 log**：只有首行带 `MM-DD HH:MM:SS.mmm` 时间戳前缀，
 *     同一条 log 的后续行（如 native dump）是裸行；
 *  2. **逐行多条 log**（AndroidRuntime 打 Java 崩溃堆栈就是这种）：
 *     每行都是独立 log 条目，各自带时间戳与 `E/AndroidRuntime( 1234):` 前缀。
 *
 * 因此块的聚合规则：带时间戳的行开启新块，但**若与当前块同 tag+pid 且不是新崩溃
 * 特征行，则视为同一崩溃块的延续并入**——否则 Java 崩溃会被拆成碎片，
 * 只剩 `FATAL EXCEPTION` 一行，包名（Process: 行）和异常类名全部丢失。
 *
 * 块内含崩溃特征（FATAL EXCEPTION / Fatal signal / ANR in）才产出事件，
 * 其余普通日志条目直接丢弃。
 */
class CrashParser {

    private var current: MutableList<String>? = null

    /** 当前块的 tag+pid 标识（用于识别逐行输出的延续） */
    private var currentKey: Pair<String, Int>? = null

    /** 喂入一行原始输出，若上一个崩溃块就此完结则返回该事件 */
    fun feed(line: String): CrashEvent? {
        val l = line.trimEnd('\n', '\r')
        val trimmed = l.trimStart()
        if (STAMP_PREFIX.containsMatchIn(trimmed)) {
            val key = TAG_PID.find(trimmed)?.let { it.groupValues[2] to it.groupValues[3].toInt() }
            val cur = current
            if (cur != null && key != null && key == currentKey && !isCrashStart(trimmed)) {
                cur.add(l)
                if (cur.size > MAX_BLOCK_LINES) return flush()
                return null
            }
            val finished = flush()
            current = mutableListOf(l)
            currentKey = key
            return finished
        }
        val block = current ?: return null   // 块外的无时间戳行（如分隔线）直接忽略
        block.add(l)
        if (block.size > MAX_BLOCK_LINES) return flush()   // 防异常超长块
        return null
    }

    /**
     * 段边界判定：这一行是否意味着「上一段崩溃信息已经结束」。
     *
     * 用途是流式监控里的提前产出。logcat 的 crash 缓冲区存在**尾部静默**问题：
     * 一条崩溃写完后很长时间（可能永远）不会有下一行进来，而 [feed] 只在
     * 「下一行到达」或「流结束」时才 flush——于是用户复现完崩溃回到 App，
     * 事件还压在解析器的 current 里没交出去，列表看着像没抓到。
     *
     * 崩溃块真正结束的标志很明确：时间戳行 + 日志级别前缀，且 tag 换成了别人，
     * 或 tag 相同但这一行是**新的崩溃起点**。指向当前块的续行（裸行、堆栈、
     * 同 tag 的后续 log）一律返回 false，不会被误判成边界。
     *
     * ★ 只做判定，不动 current —— 保持 [feed] 单独可用的语义不变，
     *   调用方判定为 true 后再自行调 [flush] 取件。
     */
    fun isSegmentBoundary(line: String): Boolean {
        val cur = current ?: return false
        val trimmed = line.trimEnd('\n', '\r').trimStart()
        if (!STAMP_PREFIX.containsMatchIn(trimmed)) return false
        val key = TAG_PID.find(trimmed)?.let { it.groupValues[2] to it.groupValues[3].toInt() }
            ?: return true                       // 无 tag 的新时间戳行 → 新段
        if (key != currentKey) return true       // 换了 tag/pid → 新段
        return isCrashStart(trimmed)             // 同 tag 的新崩溃起点 → 新段
    }

    /** 结束当前收集（流结束时调用），返回最后一个事件 */
    fun flush(): CrashEvent? {
        val block = current ?: return null
        current = null
        currentKey = null
        if (block.isEmpty()) return null
        val text = block.joinToString("\n").trimEnd()
        if (text.isBlank()) return null

        return when {
            "FATAL EXCEPTION" in text -> javaCrash(text, block)
            "Fatal signal" in text -> nativeCrash(block)
            // tombstone 格式（Android 10+ 常见）：
            //   `*** *** *** *** ***` 分隔头 + `signal 5 (SIGTRAP), code 1 (TRAP_BRKPT)` 行
            //   注意信号行**不含 "Fatal" 前缀**（那是老格式 F/libc 才有），
            //   Chrome / WebView / 各类 NDK 应用的崩溃都走这条路。
            //   此前只认 "Fatal signal" 导致这类崩溃被整体丢弃——
            //   表现为「crash buffer 里明明有数据，解析出来 0 条」。
            TOMBSTONE_HEADER.containsMatchIn(text) && SIGNAL_LINE.containsMatchIn(text) ->
                nativeCrash(block)
            "ANR in" in text -> anrCrash(block)
            else -> null   // crash buffer 里的普通日志，不是崩溃
        }
    }

    private fun javaCrash(text: String, block: List<String>): CrashEvent {
        val pkg = Regex("Process:\\s*([\\w.$]+)").find(text)?.groupValues?.get(1)
        // 块内第一条异常声明行（跳过 "FATAL EXCEPTION: main" 本身）
        val excLine = block.firstOrNull {
            !it.contains("FATAL EXCEPTION") && EXC_MARK.containsMatchIn(it)
        }?.trim()
        val type = excLine?.let { l ->
            val head = l.substringBefore(": ").trim()
            head.ifEmpty { null }
        } ?: "Java 崩溃"
        // 摘要优先用异常消息；取不到时用线程名兜底，
        // 避免列表出现「Java 崩溃 · Java 崩溃」这种自我重复
        val excMsg = excLine?.let { l ->
            val idx = l.indexOf(": ")
            if (idx >= 0) l.substring(idx + 2).trim().takeIf { it.isNotEmpty() } else null
        }
        val thread = Regex("FATAL EXCEPTION:?\\s*([\\w.<>]+)").find(text)?.groupValues?.get(1)
        val summary = excMsg ?: (thread?.let { "线程 $it 上的未捕获异常" }) ?: "Java 未捕获异常"
        return CrashEvent(
            time = parseStamp(block.first()) ?: "",
            packageName = pkg,
            type = type,
            summary = summary.take(200),
            stack = text
        )
    }

    private fun nativeCrash(block: List<String>): CrashEvent {
        val first = block.first()
        val text = block.joinToString("\n").trimEnd()
        // 信号名两种格式：
        //   老：Fatal signal 11 (SIGSEGV), ...
        //   新：signal 5 (SIGTRAP), code 1 (TRAP_BRKPT), ...  ← tombstone
        val sig = Regex("Fatal signal \\d+ \\(([^)]+)\\)").find(first)?.groupValues?.get(1)
            ?: SIGNAL_LINE.find(text)?.groupValues?.get(1)
        return CrashEvent(
            time = parseStamp(first) ?: "",
            packageName = extractProcessName(text),
            type = "Native 崩溃${sig?.let { " ($it)" } ?: ""}",
            summary = first.substringAfter("): ").take(200),
            stack = text
        )
    }

    private fun anrCrash(block: List<String>): CrashEvent {
        val first = block.first()
        val pkg = Regex("ANR in ([\\w.$]+)").find(first)?.groupValues?.get(1)
            ?: Regex("ANR in ([\\w.$]+)").find(block.joinToString("\n"))?.groupValues?.get(1)
        return CrashEvent(
            time = parseStamp(first) ?: "",
            packageName = pkg,
            type = "ANR（应用无响应）",
            summary = "应用长时间无响应，被系统弹窗或自动关闭".take(200),
            stack = block.joinToString("\n").trimEnd()
        )
    }

    /**
     * 提取崩溃进程名，按可靠性顺序尝试：
     *  1. `Fatal signal 11 (SIGSEGV), ... in tid 3280 (pfox.android.tv), pid 3280 (pfox.android.tv)`
     *  2. 老格式 `... thread 3280 (pfox.android.tv)`
     *  3. tombstone 头 `>>> pfox.android.tv <<<`
     * 子进程名（com.foo:push）取 `:` 前的主包名。
     */
    private fun extractProcessName(text: String): String? =
        listOf(
            Regex("""in (?:tid|pid) \d+ \(([\w.$]+)"""),
            Regex("""thread \d+ \(([\w.$]+)"""),
            Regex("""\btid \d+ \(([\w.$]+)"""),
            Regex("""\bpid \d+ \(([\w.$]+)"""),
            Regex(""">>>\s*([\w.$]+)\s*<<<""")
        ).firstNotNullOfOrNull { re -> re.find(text)?.groupValues?.get(1) }
            ?.substringBefore(':')

    /** 从 logcat 首行提取时间并补全年份：MM-DD HH:MM:SS.mmm → yyyy-MM-dd HH:mm:ss */
    private fun parseStamp(first: String): String? {
        val m = STAMP_TIME.find(first) ?: return null
        val (mo, d, h, mi, s) = m.destructured
        val year = java.time.LocalDate.now().year
        return "%04d-%s-%s %s:%s:%s".format(year, mo, d, h, mi, s)
    }

    private fun isCrashStart(line: String): Boolean =
        "FATAL EXCEPTION" in line || "Fatal signal" in line || "ANR in" in line ||
            TOMBSTONE_HEADER.containsMatchIn(line)

    private companion object {
        private val STAMP_PREFIX = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+")
        private val STAMP_TIME = Regex("(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})")
        /** `E/AndroidRuntime( 3280):` / `F/libc    ( 3280):` → group2=tag, group3=pid */
        private val TAG_PID = Regex("([VDIWEF])/([\\w.-]+)\\s*\\(\\s*(\\d+)\\)")

        /**
         * tombstone 崩溃头：`*** *** *** *** ***` 或 `*** engrave_tombstone_ucontext ***`。
         * 这是 Android 10+ 由 debuggerd 写入 crash buffer 的标准格式，
         * Chrome / WebView / 各类 NDK 崩溃都走这条路。
         */
        private val TOMBSTONE_HEADER = Regex("""\*\*\*.*tombstone|\*\*\* \*\*\* \*\*\*""")

        /**
         * tombstone 的信号行：`signal 5 (SIGTRAP), code 1 (TRAP_BRKPT), fault addr --------`
         * 注意**没有** "Fatal" 前缀（老格式 F/libc 才是 `Fatal signal 11 (SIGSEGV)`）。
         */
        private val SIGNAL_LINE = Regex("""signal \d+ \((SIG[A-Z]+)\)""")
        private val EXC_MARK = Regex("(Exception|Error)\\b")
        private const val MAX_BLOCK_LINES = 400
    }
}
