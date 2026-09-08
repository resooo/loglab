package com.loglab.app.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.loglab.app.core.report.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 GitHub Releases 的应用内更新：
 *   检查：GET /repos/<owner>/<repo>/releases/latest（公开仓库免认证）
 *   对比：tag 形如 v1.6.0，与 BuildConfig.VERSION_NAME 按 semver 数字段比较
 *   下载：取 assets 里第一个 .apk，流式写到 cacheDir/update/，进度回调
 *   安装：FileProvider + ACTION_VIEW（package-archive），未知来源权限缺失时引导跳系统设置
 *
 * 发布约定（与 CI .github/workflows/release.yml 一致）：
 *   push tag v<versionName>（例 v1.6.0）→ Actions 自动构建 release APK 并传到该 Release。
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    companion object {
        /** 开源仓库地址：检查更新与「前往 GitHub」都用它 */
        const val REPO_OWNER = "resooo"
        const val REPO_NAME = "loglab"
        const val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
        private const val LATEST_API =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

        /** 更新包缓存目录（FileProvider 已在 file_paths.xml 放行 cache-path/update） */
        private const val UPDATE_DIR = "update"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------- 数据模型 ----------------

    @Serializable
    data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = REPO_URL,
        val assets: List<GhAsset> = emptyList()
    )

    @Serializable
    data class GhAsset(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = "",
        val size: Long = 0
    )

    /** 面向 UI 的检查结果 */
    data class UpdateInfo(
        val version: String,      // 归一化后的版本号，如 1.6.0
        val tag: String,          // 原始 tag，如 v1.6.0
        val notes: String,        // Release 说明（body）
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long
    )

    // ---------------- 版本比较 ----------------

    /** "v1.6.0" / "1.6.0-beta" → [1, 6, 0]；非数字段按 0 处理 */
    fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").substringBefore('-')
            .split('.').map { seg -> seg.filter { it.isDigit() }.toIntOrNull() ?: 0 }

    /** remote 是否比 local 新（逐段数字比较，段数不齐补 0） */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote)
        val l = parseVersion(local)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    // ---------------- 检查更新 ----------------

    /** 本机版本名（debug 包带 "-debug" 后缀，比较时 parseVersion 会按 '-' 截断） */
    fun localVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"

    /**
     * 拉取最新 Release；无新版返回 null，网络/解析失败抛异常由 UI 层兜底提示。
     * debug 包 versionName 带 "-debug" 后缀，parseVersion 已按 '-' 截断，无需特殊处理。
     */
    suspend fun checkLatest(localVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = http.newCall(
            Request.Builder()
                .url(LATEST_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "LogLab-App") // GitHub API 强制要求 UA
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub API ${resp.code}")
            resp.body?.string() ?: error("响应为空")
        }
        val release = json.decodeFromString<GhRelease>(body)
        logger.log("Update", "最新 Release：${release.tagName}（本地 $localVersion）")

        if (!isNewer(release.tagName, localVersion)) return@withContext null

        val notes = cleanNotes(release.body.orEmpty())
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
            ?: return@withContext UpdateInfo(
                version = release.tagName.removePrefix("v"),
                tag = release.tagName,
                notes = notes,
                apkName = "", apkUrl = "", apkSize = 0
            )
        UpdateInfo(
            version = release.tagName.removePrefix("v"),
            tag = release.tagName,
            notes = notes,
            apkName = apk.name,
            apkUrl = apk.downloadUrl,
            apkSize = apk.size
        )
    }

    /**
     * 把 GitHub Release 的 markdown 正文转成适合弹窗阅读的纯文本：
     * 去掉标题井号/加粗星号/行内代码反引号，链接只留文字，去掉列表连字符，压缩多余空行。
     */
    private fun cleanNotes(body: String): String =
        body.lineSequence()
            .map { raw ->
                var line = raw.trim()
                    .removePrefix("#").trim()
                    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                    .replace(Regex("\\*([^*]+)\\*"), "$1")
                    .replace(Regex("`([^`]+)`"), "$1")
                    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                if (line.startsWith("- ") || line.startsWith("* ")) line = line.substring(2).trim()
                line
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    // ---------------- 下载 ----------------

    /**
     * 下载 APK 到 cacheDir/update/<tag>.apk；onProgress(0..100)。
     * 返回下载完成的文件。同名文件已完整存在则直接复用（断点场景回来自动续装）。
     */
    suspend fun downloadApk(info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
            val target = File(dir, "${info.tag}.apk")
            if (target.exists() && target.length() == info.apkSize && info.apkSize > 0) {
                logger.log("Update", "复用已下载的 ${target.name}")
                onProgress(100)
                return@withContext target
            }
            // 残缺文件先清掉
            target.delete()

            logger.log("Update", "开始下载 ${info.apkUrl}")
            http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败 HTTP ${resp.code}")
                val src = resp.body ?: error("下载响应为空")
                val total = maxOf(info.apkSize, src.contentLength())
                src.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
            logger.log("Update", "下载完成：${target.absolutePath}（${target.length()} 字节）")
            target
        }

    // ---------------- 安装 ----------------

    /**
     * 拉起系统安装器。
     * @return null = 已成功跳起安装器；非 null = 提示文案（如需先去授予「安装未知应用」权限）
     */
    fun installApk(file: File): String? {
        val pm = context.packageManager
        // minSdk 26，canRequestPackageInstalls 可直接用；没有授权则先引导去系统设置
        if (!pm.canRequestPackageInstalls()) {
            logger.log("Update", "缺少「安装未知应用」授权，跳转设置页")
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return "请先允许「安装未知应用」，然后回到这里点「安装」"
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // UpdateManager 持有 Application context，非 Activity 启动必须加此 flag
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                logger.log("Update", "拉起安装器失败：${it.message}")
                return "拉起安装器失败：${it.message}"
            }
        return null
    }

    /** 清理更新缓存（下载中断的残缺包等） */
    fun clearCache() {
        File(context.cacheDir, UPDATE_DIR).deleteRecursively()
    }
}
