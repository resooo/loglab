package com.loglab.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.loglab.app.data.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "logcat_grabber_settings")

/**
 * 设置仓库：DataStore(Preferences) + JSON 整体序列化，读取成本低、实现简单。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("app_settings")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?: AppSettings()
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        context.settingsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(next)
        }
    }

    // ---- 读取单项（给 Channel 层用，避免持有 Flow）----
    suspend fun adbHostOnce(): String = current().adbHost
    suspend fun adbPortOnce(): Int = current().adbPort
    suspend fun bridgeUrlOnce(): String = current().bridgeUrl
    suspend fun policyOnce(): com.loglab.app.core.channel.ChannelPolicy = current().channelPolicy

    suspend fun rememberPackage(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return
        update { current ->
            val merged = (listOf(pkg) + current.recentPackages)
                .distinct()
                .take(12)
            current.copy(recentPackages = merged)
        }
    }

    suspend fun setOnboarded(done: Boolean = true) = update { it.copy(onboarded = done) }

    suspend fun setAdbPaired(paired: Boolean) = update { it.copy(adbPaired = paired) }
}
