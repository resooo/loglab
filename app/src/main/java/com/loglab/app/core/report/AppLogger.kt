package com.loglab.app.core.report

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 自身运行日志：把启动检查 / ADB 连接 / 端口更新 / 日志抓取的关键节点
 * 同时写入内存环形缓冲与 filesDir/app.log，设置页可查看、复制、清除。
 *
 * 存在理由：用户拿不到 App 自身的 logcat，遇到"端口没更新 / 连不上 / 静默断流"
 * 这类问题只能靠猜。有了这份日志，任何一步做过什么、连的是哪个端口、
 * mDNS 扫到几个服务，都能一眼看清。
 *
 * 只记录流程节点，不记录被抓取的日志正文。
 */
@Singleton
class AppLogger @Inject constructor(@ApplicationContext context: Context) {

    private val file = File(context.applicationContext.filesDir, LOG_FILE)
    private val lock = Any()
    private val buffer = ArrayList<String>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    fun log(tag: String, message: String, t: Throwable? = null) {
        val time = synchronized(fmt) { fmt.format(Date()) }
        val line = buildString {
            append(time).append(" [").append(tag).append("] ").append(message)
            if (t != null) append("\n").append(android.util.Log.getStackTraceString(t))
        }
        synchronized(lock) {
            buffer.add(line)
            while (buffer.size > MAX_ENTRIES) buffer.removeAt(0)
            runCatching {
                // 文件过大时保留后一半，避免无限增长
                if (file.length() > MAX_FILE_BYTES) {
                    val keep = file.readLines().takeLast(MAX_ENTRIES / 2)
                    file.writeText(keep.joinToString("\n") + "\n")
                }
                file.appendText(line + "\n")
            }
        }
    }

    /** 最近 [limit] 条（最新在最后） */
    fun read(limit: Int = MAX_ENTRIES): String = synchronized(lock) {
        buffer.takeLast(limit).joinToString("\n")
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        runCatching { file.delete() }
    }

    private companion object {
        const val LOG_FILE = "app.log"
        const val MAX_ENTRIES = 800
        const val MAX_FILE_BYTES = 512 * 1024
    }
}
