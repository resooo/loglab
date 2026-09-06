package com.loglab.app.core.crash

import kotlinx.serialization.Serializable

/**
 * 一次应用崩溃记录。
 *
 * @param time   发生时间（取自 logcat 行内时间戳，格式 yyyy-MM-dd HH:mm:ss）
 * @param packageName 崩溃应用包名；Native 崩溃可能解析不到，为 null
 * @param type   崩溃类型：异常类名 / "Native 崩溃 (SIGSEGV)" / "ANR（应用无响应）"
 * @param summary 单行摘要（异常消息或触发原因）
 * @param stack  完整堆栈原文
 */
@Serializable
data class CrashEvent(
    val time: String,
    val packageName: String?,
    val type: String,
    val summary: String,
    val stack: String
) {
    val displayPackage: String get() = packageName ?: "未知应用（未解析到包名）"
}
