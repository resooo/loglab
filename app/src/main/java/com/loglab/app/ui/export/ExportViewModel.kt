package com.loglab.app.ui.export

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loglab.app.core.channel.ChannelManager
import com.loglab.app.core.export.LogExporter
import com.loglab.app.core.logcat.LogBuffer
import com.loglab.app.core.logcat.LogPriority
import com.loglab.app.core.logcat.LogcatConfig
import com.loglab.app.data.model.AppSettings
import com.loglab.app.data.model.ExportResult
import com.loglab.app.data.repository.LogRepository
import com.loglab.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val repository: LogRepository,
    val channelManager: ChannelManager,
    private val exporter: LogExporter,
    private val settings: SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val channelState = channelManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.loglab.app.core.channel.ChannelState(null))

    var packageName by androidx.compose.runtime.mutableStateOf("")
        private set
    var fileName by androidx.compose.runtime.mutableStateOf(defaultFileName())
        private set
    var maxLines by androidx.compose.runtime.mutableStateOf(5000)
        private set
    var buffer by androidx.compose.runtime.mutableStateOf(LogBuffer.MAIN)
        private set
    var priority by androidx.compose.runtime.mutableStateOf(LogPriority.VERBOSE)
        private set
    var keywordInput by androidx.compose.runtime.mutableStateOf("")
        private set
    var addHeader by androidx.compose.runtime.mutableStateOf(true)
        private set
    var gzip by androidx.compose.runtime.mutableStateOf(false)
        private set
    var busy by androidx.compose.runtime.mutableStateOf(false)
        private set
    var status by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    private val _history = MutableStateFlow<List<File>>(emptyList())
    val history: StateFlow<List<File>> = _history.asStateFlow()

    var lastResult by androidx.compose.runtime.mutableStateOf<ExportResult?>(null)
        private set

    init {
        refreshHistory()
        viewModelScope.launch { buffer = settings.current().defaultBuffer }
    }

    fun onPackageChange(value: String) { packageName = value }
    fun onFileNameChange(value: String) { fileName = value }
    fun onMaxLinesChange(value: String) { maxLines = value.toIntOrNull()?.coerceIn(0, 500_000) ?: 0 }
    fun onBufferChange(value: LogBuffer) { buffer = value }
    fun onPriorityChange(value: LogPriority) { priority = value }
    fun onKeywordChange(value: String) { keywordInput = value }
    fun onAddHeaderChange(value: Boolean) { addHeader = value }
    fun onGzipChange(value: Boolean) { gzip = value }

    fun regenerateFileName() { fileName = defaultFileName() }

    suspend fun export(): Result<ExportResult> = withContext(Dispatchers.IO) {
        busy = true
        status = "正在抓取并导出…"
        val config = LogcatConfig(
            buffer = buffer,
            globalPriority = priority,
            maxLines = maxLines,
            streaming = false,
            keywords = keywordInput.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }
        )
        val result = repository.exportCapture(
            packageName = packageName.trim().ifBlank { null },
            fileName = fileName,
            config = config,
            addHeader = addHeader,
            gzip = gzip
        )
        result.onSuccess { exported ->
            lastResult = exported
            status = "已导出 ${exported.lineCount} 行 · ${formatSize(exported.byteSize)}"
            refreshHistory()
            packageName.trim().takeIf { it.isNotBlank() }?.let { pkg ->
                settings.rememberPackage(pkg)
            }
        }.onFailure {
            status = "导出失败：${it.message}"
        }
        busy = false
        result
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = exporter.exportDir()
                .listFiles()
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        }
    }

    fun delete(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { file.delete() }
            refreshHistory()
        }
    }

    /** 把已导出的文件复制到用户通过 SAF 选择的位置 */
    suspend fun saveTo(uri: android.net.Uri, file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法写入目标位置")
            status = "已保存到指定位置"
        }
    }

    fun shareFile(file: File): android.content.Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val mime = if (file.name.endsWith(".gz")) "application/gzip" else "text/plain"
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 防御：若从非 Activity context 启动 chooser，target 也需 NEW_TASK
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

        fun defaultFileName(): String = "logcat_${nameFormat.format(Date())}.log"

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
            else -> "%.2f MB".format(bytes / 1024f / 1024f)
        }
    }
}
