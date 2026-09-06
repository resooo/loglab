package com.loglab.app.core.export

import android.content.Context
import com.loglab.app.core.channel.ChannelType
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.data.model.ExportResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ExportOptions(
    val packageName: String? = null,
    val pid: Int? = null,
    val buffer: LogBuffer = LogBuffer.MAIN,
    val keywords: List<String> = emptyList(),
    val channelType: ChannelType? = null,
    val addHeader: Boolean = true,
    val gzip: Boolean = false
)

/**
 * 日志导出：先写入应用私有目录（无需存储权限），
 * 之后由 UI 通过 SAF / 分享把文件交到用户手里。
 */
@Singleton
class LogExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun exportDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    suspend fun export(
        lines: List<String>,
        fileName: String,
        options: ExportOptions
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = fileName.sanitize()
            val finalName = if (options.gzip && !safeName.endsWith(".gz")) "$safeName.gz" else safeName
            val file = File(exportDir(), finalName)

            FileOutputStream(file).use { fileOut ->
                val writer = if (options.gzip) {
                    GZIPOutputStream(fileOut).bufferedWriter()
                } else {
                    fileOut.bufferedWriter()
                }
                writer.use { out ->
                    if (options.addHeader) {
                        out.write(buildHeader(lines.size, options))
                        out.write("\n")
                    }
                    lines.forEach { line ->
                        out.write(line)
                        out.write("\n")
                    }
                }
            }

            ExportResult(
                fileName = finalName,
                absolutePath = file.absolutePath,
                lineCount = lines.size,
                byteSize = file.length(),
                gzipped = options.gzip
            )
        }
    }

    private fun buildHeader(lineCount: Int, options: ExportOptions): String = buildString {
        appendLine("# LogLab Export")
        appendLine("# 生成时间 : ${dateFormat.format(Date())}")
        appendLine("# 通道     : ${options.channelType?.name ?: "UNKNOWN"}")
        appendLine("# 包名     : ${options.packageName ?: "(全部)"}")
        appendLine("# PID      : ${options.pid ?: "-"}")
        appendLine("# 缓冲区   : ${options.buffer.value}")
        if (options.keywords.isNotEmpty()) {
            appendLine("# 关键词   : ${options.keywords.joinToString(", ")}")
        }
        appendLine("# 行数     : $lineCount")
        appendLine("--------------------------------------------------")
    }

    private fun String.sanitize(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "logcat_export.log" }
}
