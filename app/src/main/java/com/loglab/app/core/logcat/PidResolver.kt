package com.loglab.app.core.logcat

import com.loglab.app.core.channel.Channel

/**
 * 包名 → PID 解析，多级回退（与插件 adb_get_pid 一致，并增强兼容性）：
 *   1. pidof             最快，toybox 自带
 *   2. ps -A -o PID,NAME 新版 toybox 格式（PID 在第一列）
 *   3. ps -A | grep      老版本格式（PID 在第二列，USER 是第一列）
 *   4. pgrep -f          兜底
 *
 * 关键改进：区分「命令执行失败」（通道/权限问题，透传真实错误）与
 * 「命令都成功但无匹配」（应用确实未运行），不再把两者混为一谈。
 */
class PidResolver(private val channel: Channel) {

    suspend fun resolve(packageName: String): Result<Int> {
        if (packageName.isBlank()) {
            return Result.failure(IllegalArgumentException("包名为空"))
        }

        val notes = mutableListOf<String>()

        // 1. pidof（包名只含 [a-zA-Z0-9.]，无需引号，减少 shell 层不确定性）
        runStep(notes, "pidof") { channel.execute("pidof $packageName 2>/dev/null") }
            ?.let { firstPid(it) }
            ?.let { return Result.success(it) }

        // 2. ps -A -o PID,NAME | grep（Android 9+ toybox）
        runStep(notes, "ps-o") { channel.execute("ps -A -o PID,NAME 2>/dev/null | grep -m1 -- $packageName") }
            ?.let { firstNumericToken(it) }
            ?.let { return Result.success(it) }

        // 3. ps -A | grep（老版 toybox / busybox：PID 在第二列）
        runStep(notes, "ps") { channel.execute("ps -A 2>/dev/null | grep -m1 -- $packageName") }
            ?.let { firstNumericToken(it) }
            ?.let { return Result.success(it) }

        // 4. pgrep -f 兜底
        runStep(notes, "pgrep") { channel.execute("pgrep -f $packageName 2>/dev/null | head -1") }
            ?.let { firstNumericToken(it) }
            ?.let { return Result.success(it) }

        // 全部失败：有「执行异常」→ 通道问题，透传真实错误；否则 → 应用确实未运行
        val execErrors = notes.filter { it.contains("失败") }
        return if (execErrors.isNotEmpty()) {
            Result.failure(IllegalStateException("无法获取 $packageName 的 PID：${execErrors.joinToString("; ")}"))
        } else {
            Result.failure(
                IllegalStateException(
                    "无法获取 $packageName 的 PID，应用可能未运行（pidof/ps/pgrep 均无匹配：${notes.joinToString("; ")}）"
                )
            )
        }
    }

    /** 执行一条命令：成功返回输出（可能为空），失败记录诊断并返回 null */
    private suspend fun runStep(notes: MutableList<String>, name: String, block: suspend () -> Result<String>): String? =
        runCatching { block().getOrThrow() }.fold(
            onSuccess = { out ->
                if (out.isBlank()) notes.add("${name}无输出") else notes.add("${name}有输出但未匹配")
                out
            },
            onFailure = { e ->
                notes.add("${name}失败:${e.message ?: e::class.java.simpleName}")
                null
            }
        )

    private fun firstPid(output: String): Int? = firstNumericToken(output)

    /** 取输出中第一个「纯数字」token，兼容 PID 在第一列（-o 格式）或第二列（标准 ps 格式） */
    private fun firstNumericToken(output: String): Int? =
        output.trim()
            .split(Regex("\\s+"))
            .firstOrNull { it.isNotEmpty() && it.matches(Regex("\\d+")) && it != "0" }
            ?.toIntOrNull()

    /** 列出已安装第三方包名（用于包名选择弹窗） */
    suspend fun listPackages(keyword: String = ""): List<String> {
        val command = if (keyword.isBlank()) {
            "pm list packages -3 2>/dev/null | sed 's/package://' | sort"
        } else {
            "pm list packages 2>/dev/null | grep -- '$keyword' | sed 's/package://' | head -100"
        }
        return channel.execute(command).getOrNull()
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()
    }
}
