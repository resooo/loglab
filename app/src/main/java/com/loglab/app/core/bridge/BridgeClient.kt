package com.loglab.app.core.bridge

import com.loglab.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HostBridge HTTP 客户端（与插件中的 curl 调用等价）。
 * 默认地址 http://127.0.0.1:7980，接口：/api/health、/api/shell。
 */
@Singleton
class BridgeClient @Inject constructor(
    private val settings: SettingsRepository,
    private val auth: BridgeAuth
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun healthCheck(): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl()}/api/health")
                .apply { token().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                json.decodeFromString<HealthResponse>(response.body?.string().orEmpty())
            }
        }
    }

    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(ShellRequest(command))
            val request = Request.Builder()
                .url("${baseUrl()}/api/shell")
                .apply { token().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val parsed = runCatching {
                    json.decodeFromString<ShellResponse>(response.body?.string().orEmpty())
                }.getOrElse { ShellResponse(success = false, stdout = "", stderr = "响应解析失败") }
                if (parsed.success) parsed.stdout
                else throw IllegalStateException(parsed.stderr.ifBlank { "HostBridge 执行失败" })
            }
        }
    }

    private suspend fun baseUrl(): String =
        settings.bridgeUrlOnce().trimEnd('/')

    private fun token(): String = auth.token()
}
