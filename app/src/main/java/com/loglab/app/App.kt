package com.loglab.app

import android.app.Application
import android.os.Build
import com.loglab.app.core.report.AppLogger
import com.loglab.app.core.report.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var logger: AppLogger

    override fun onCreate() {
        super.onCreate()
        // 必须尽早安装：崩溃栈落盘，设置页可查看，弥补"App 闪退时抓不到自身崩溃"的盲区
        CrashReporter.install(this)
        runCatching {
            logger.log(
                "APP",
                "应用启动 · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT}"
            )
        }
    }
}
