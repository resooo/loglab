package com.loglab.app.core.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.report.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 选择器用的一条应用信息。
 *
 * 图标/应用名来自**本机 PackageManager**（毫秒级，不走 ADB）；
 * 是否在跑、是否前台来自 shell（需要 ADB 通道，取不到就退化为纯列表）。
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val running: Boolean = false,
    val foreground: Boolean = false
)

@Singleton
class AppInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelManager: ChannelManager,
    private val logger: AppLogger
) {
    private val pm: PackageManager get() = context.packageManager

    /**
     * 构建应用列表：第三方在前、系统在后；运行的、前台的、最近抓过的提权到前面。
     * @param recent 最近抓过的包名（设置里记录的），用于置顶
     */
    suspend fun load(recent: List<String> = emptyList()): List<AppInfo> = withContext(Dispatchers.IO) {
        val installed = runCatching { pm.getInstalledPackages(PackageManager.GET_META_DATA) }
            .getOrNull()
            .orEmpty()

        val running = runningProcessNames().orEmpty()
        val foregroundPkg = foregroundPackage()
        logger.log("APPS", "已安装 ${installed.size} 个；运行中进程 ${running.size} 个；前台=${foregroundPkg ?: "未知"}")

        val list = installed.mapNotNull { pi ->
            val pkg = pi.packageName ?: return@mapNotNull null
            val label = runCatching {
                pi.applicationInfo?.loadLabel(pm)?.toString()
            }.getOrNull().orEmpty().ifBlank { pkg }
            val isSystem = runCatching {
                (pi.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            }.getOrDefault(false)
            val isRunning = running.any { it == pkg || it.startsWith("$pkg:") }
            AppInfo(
                packageName = pkg,
                label = label,
                isSystem = isSystem,
                running = isRunning,
                foreground = pkg == foregroundPkg
            )
        }

        // 排序：前台 > 运行中 > 最近抓过 > 普通第三方 > 系统应用；同级按名称
        val recentSet = recent.toSet()
        list.sortedWith(
            compareByDescending<AppInfo> { it.foreground }
                .thenByDescending { it.running }
                .thenByDescending { it.packageName in recentSet }
                .thenBy { it.isSystem }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
    }

    /** 图标按需加载（LazyColumn 只会对可见项调用，不会一次拉几百个） */
    fun icon(packageName: String): Drawable? = runCatching {
        pm.getApplicationIcon(packageName)
    }.onFailure {
        logger.log("APPS", "加载图标失败 $packageName：${it.message}")
    }.getOrNull()

    /** 应用名（label）：取不到返回 null，调用方回落到包名 */
    fun labelOf(packageName: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    /** 当前前台包名（dumpsys 取不到时返回 null，不影响主流程） */
    private suspend fun foregroundPackage(): String? {
        val channel = channelManager.active() ?: return null
        val out = channel.execute(
            "dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | head -3"
        ).getOrNull() ?: return null
        // 形如：topResumedActivity=ActivityRecord{... com.xxx/.ui.MainActivity t123}
        val regex = Regex("([a-zA-Z0-9_.-]+)/[.a-zA-Z0-9_$]+")
        return regex.find(out)?.groupValues?.getOrNull(1)
    }

    /** 正在运行的进程名集合 */
    private suspend fun runningProcessNames(): Set<String>? {
        val channel = channelManager.active() ?: return null
        val out = channel.execute("ps -A -o NAME 2>/dev/null").getOrNull() ?: return null
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
