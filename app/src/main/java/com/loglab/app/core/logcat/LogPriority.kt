package com.loglab.app.core.logcat

import kotlinx.serialization.Serializable

@Serializable
enum class LogPriority(val letter: Char, val label: String) {
    VERBOSE('V', "Verbose"),
    DEBUG('D', "Debug"),
    INFO('I', "Info"),
    WARN('W', "Warn"),
    ERROR('E', "Error"),
    FATAL('F', "Fatal");

    companion object {
        fun from(letter: Char): LogPriority =
            entries.firstOrNull { it.letter == letter.uppercaseChar() } ?: VERBOSE
    }
}

@Serializable
enum class LogBuffer(val value: String, val label: String) {
    MAIN("main", "main 主缓冲"),
    SYSTEM("system", "system 系统"),
    CRASH("crash", "crash 崩溃"),
    RADIO("radio", "radio 无线"),
    EVENTS("events", "events 事件"),
    ALL("all", "all 全部")
}

@Serializable
enum class LogFormat(val value: String, val label: String) {
    THREADTIME("threadtime", "threadtime（推荐）"),
    TIME("time", "time"),
    BRIEF("brief", "brief"),
    LONG("long", "long"),
    RAW("raw", "raw"),
    TAG("tag", "tag"),
    PROCESS("process", "process"),
    THREAD("thread", "thread")
}
