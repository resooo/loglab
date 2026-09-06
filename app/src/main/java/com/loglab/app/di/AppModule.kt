package com.loglab.app.di

import android.content.Context
import com.loglab.app.core.adb.AdbKeyStore
import com.loglab.app.core.adb.BuiltinAdbBackend
import com.loglab.app.core.adb.KadbAdbBackend
import com.loglab.app.core.adb.KadbCertPersistence
import com.loglab.app.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAdbKeyStore(@ApplicationContext context: Context): AdbKeyStore =
        AdbKeyStore(context)

    @Provides
    @Singleton
    fun provideBuiltinAdbBackend(
        keyStore: AdbKeyStore,
        settings: SettingsRepository
    ): BuiltinAdbBackend = BuiltinAdbBackend(
        keyStore = keyStore,
        hostProvider = { settings.adbHostOnce() },
        portProvider = { settings.adbPortOnce() }
    )

    @Provides
    @Singleton
    fun provideKadbCertPersistence(settings: SettingsRepository): KadbCertPersistence =
        KadbCertPersistence(settings)

    @Provides
    @Singleton
    fun provideKadbAdbBackend(
        settings: SettingsRepository,
        certPersistence: KadbCertPersistence,
        logger: com.loglab.app.core.report.AppLogger
    ): KadbAdbBackend = KadbAdbBackend(
        certPersistence = certPersistence,
        hostProvider = { settings.adbHostOnce() },
        portProvider = { settings.adbPortOnce() },
        logger = logger
    )
}
