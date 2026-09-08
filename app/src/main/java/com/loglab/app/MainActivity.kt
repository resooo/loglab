package com.loglab.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.loglab.app.data.repository.SettingsRepository
import com.loglab.app.ui.LogcatApp
import com.loglab.app.ui.theme.AppThemeWrapper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * AppCompatActivity（而非 ComponentActivity）：
 * 应用内语言切换依赖 AppCompatDelegate.setApplicationLocales，
 * Android 12 及以下只有 AppCompatActivity 会在重建时应用 per-app locale。
 * 对 Compose 无任何影响——AppCompatActivity 本就是 ComponentActivity 的子类。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    private var notificationPermissionGranted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationPermissionGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!notificationPermissionGranted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 直接进入主界面，不再有引导页拦截：
        // 未配对/无线调试未开启等状态由首页顶部的启动智能检查条负责提示与引导，
        // 避免任何"被挡在首页外"的情况。
        setContent {
            AppThemeWrapper(settings) {
                LogcatApp()
            }
        }
    }
}
