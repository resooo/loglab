package com.loglab.app.core.channel

import com.loglab.app.core.bridge.BridgeClient
import com.loglab.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HostBridge 通道：通过 HTTP 调用宿主 shell 能力（Shizuku / Root 场景）。
 *
 * 与插件一致：能力上只能"一次性执行"，实时跟踪通过轮询 `logcat -d` 模拟。
 */
@Singleton
class BridgeChannel @Inject constructor(
    private val client: BridgeClient,
    private val settings: SettingsRepository
) : Channel {

    override val type: ChannelType = ChannelType.BRIDGE

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
        const val POLL_WINDOW_LINES = 800
        const val DEDUP_CACHE = 4096
    }

    override suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        client.healthCheck().isSuccess
    }

    override suspend fun execute(command: String): Result<String> = withContext(Dispatchers.IO) {
        client.executeCommand(command)
    }

    /**
     * 轮询式实时跟踪：每 1.5s 拉取最近 N 行，用「时间戳|pid|tag|正文」做去重。
     * 与插件 logcat-tail.sh 的 bridge 模式行为一致。
     */
    override fun executeStream(command: String): Flow<String> = flow {
        val baseCommand = command
            .replace("logcat -v", "logcat -d -v")
            .replace(Regex("^logcat(?![ ])"), "logcat -d")
            .let { if (it.contains(" -d")) it else it.replaceFirst("logcat", "logcat -d") }
        val windowCommand = "$baseCommand -t $POLL_WINDOW_LINES"

        val seen = ArrayDeque<String>(DEDUP_CACHE)
        val seenSet = HashSet<String>()

        while (true) {
            val output = client.executeCommand(windowCommand).getOrNull()
            if (output != null) {
                for (line in output.lineSequence()) {
                    if (line.isBlank()) continue
                    val key = line
                    if (seenSet.add(key)) {
                        seen.addLast(key)
                        if (seen.size > DEDUP_CACHE) {
                            seenSet.remove(seen.removeFirst())
                        }
                        emit(line)
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun label(): String = settings.bridgeUrlOnce()

    override fun close() = Unit
}
