package com.loglab.app.core.report

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自捕获：任何未捕获异常都会把完整堆栈写入应用私有目录，
 * 下次启动后可在设置页查看/复制——App 自身闪退时用户拿不到 logcat，
 * 这是定位真机闪退的关键兜底手段。
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_LEN = 20_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val trace = Log.getStackTraceString(throwable).take(MAX_LEN)
                File(appContext.filesDir, FILE_NAME).writeText(
                    buildString {
                        appendLine("时间: $time")
                        appendLine("线程: ${thread.name}")
                        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})")
                        appendLine()
                        append(trace)
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 读取上次崩溃报告，无崩溃记录时返回 null */
    fun read(context: Context): String? = runCatching {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (f.exists() && f.length() > 0) f.readText() else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
    }
}
