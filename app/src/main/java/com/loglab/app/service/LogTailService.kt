package com.loglab.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.loglab.app.MainActivity
import com.loglab.app.R
import com.loglab.app.core.logcat.LogcatConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * 实时日志跟踪前台服务：让 tail 在退出页面后继续跑，并在通知栏显示进度。
 */
@AndroidEntryPoint
class LogTailService : android.app.Service() {

    @Inject
    lateinit var tailSession: TailSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() }
                val config = intent.getStringExtra(EXTRA_CONFIG)?.let { raw ->
                    runCatching { Json.decodeFromString<LogcatConfig>(raw) }.getOrNull()
                } ?: LogcatConfig()

                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("正在启动…"),
                    if (Build.VERSION.SDK_INT >= 34) {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    }
                )

                tailSession.start(packageName, config)
                observeSession()
            }

            ACTION_STOP -> stopTailing()
        }
        return START_STICKY
    }

    private fun observeSession() {
        scope.launch {
            tailSession.state
                .map { state ->
                    if (state.running) {
                        val target = state.packageName?.let { " · $it" }.orEmpty()
                        "已捕获 ${state.received} 行$target · %.1f 行/秒".format(state.ratePerSecond)
                    } else {
                        state.statusLine
                    }
                }
                .distinctUntilChanged()
                .collect { text -> updateNotification(text) }
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        createChannel()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAIL, true)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LogTailService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tail)
            .setContentTitle(getString(R.string.tail_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_stat_tail, "停止", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = getString(R.string.notification_channel_desc) }
                )
            }
        }
    }

    private fun stopTailing() {
        tailSession.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "logcat_tail"
        const val NOTIFICATION_ID = 2101
        const val ACTION_START = "com.loglab.app.action.START_TAIL"
        const val ACTION_STOP = "com.loglab.app.action.STOP_TAIL"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_CONFIG = "extra_config"
        const val EXTRA_OPEN_TAIL = "extra_open_tail"
    }
}
