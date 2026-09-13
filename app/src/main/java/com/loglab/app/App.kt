package com.loglab.app

import android.app.Application
import android.os.Build
import com.loglab.app.core.adb.NsdCacheCleaner
import com.loglab.app.core.report.AppLogger
import com.loglab.app.core.report.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var logger: AppLogger

    @Inject
    lateinit var nsdCacheCleaner: NsdCacheCleaner

    override fun onCreate() {
        super.onCreate()
        // 必须尽早安装：崩溃栈落盘，设置页可查看，弥补"App 闪退时抓不到自身崩溃"的盲区
        CrashReporter.install(this)
        // 退出时清空 mDNS 缓存关联，避免下次启动扫到无线调试的旧端口
        runCatching { nsdCacheCleaner.attach() }
            .onFailure { logger.log("APP", "mDNS 退出清理器挂载失败：${it.message}") }
        runCatching {
            logger.log(
                "APP",
                "应用启动 · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT}"
            )
        }
    }
}
