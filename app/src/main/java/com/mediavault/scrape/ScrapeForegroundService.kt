package com.mediavault.scrape

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.LocalScanner
import com.mediavault.data.MediaItem
import com.mediavault.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScrapeForegroundService : Service() {
    companion object {
        const val EXTRA_REBUILD = "rebuild"
        private const val CHANNEL_ID = "mediavault_scrape"
        private const val NOTIF_ID = 4102
        const val BATCH_SIZE = 25
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workJob: Job? = null
    @Volatile
    private var cancelRequested = false

    private val app get() = application as MediaVaultApp
    private val manager get() = app.scrapeManager
    private val repository get() = app.repository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rebuild = intent?.getBooleanExtra(EXTRA_REBUILD, false) ?: false
        cancelRequested = false
        createChannel()
        startForeground(NOTIF_ID, buildNotification("准备扫描…", 0))
        workJob?.cancel()
        workJob = scope.launch {
            runScan(rebuild)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelRequested = true
        workJob?.cancel()
        super.onDestroy()
    }

    private suspend fun runScan(rebuild: Boolean) {
        val store = repository.store
        if (rebuild) {
            store.clearScrapeRecord()
            repository.stripContentItems()
        }

        var scannedThisRun = 0
        var batchBuffer = mutableListOf<MediaItem>()

        fun flushBatch(reason: String) {
            if (batchBuffer.isEmpty()) return
            val n = batchBuffer.size
            repository.appendContentBatch(batchBuffer).onSuccess { total ->
                scannedThisRun += n
                val msg = "$reason · 已入库 $scannedThisRun 条（库 $total）"
                manager.onProgress(msg, scannedThisRun, total)
                updateNotification(msg, scannedThisRun)
                batchBuffer = mutableListOf()
            }.onFailure { e ->
                manager.onError(e.message ?: "写入失败")
                throw e
            }
        }

        try {
            LocalScanner.scanTreeUrisBatched(
                this,
                store,
                rebuild,
                batchSize = BATCH_SIZE,
                shouldCancel = { cancelRequested },
                onFile = { item ->
                    batchBuffer.add(item)
                    if (batchBuffer.size >= BATCH_SIZE) flushBatch("批次")
                },
                onStatus = { msg ->
                    val total = repository.library.value.items.size
                    manager.onProgress(msg, scannedThisRun, total)
                    updateNotification(msg, scannedThisRun)
                },
            )
            flushBatch("收尾")
            if (cancelRequested) {
                manager.onCancelled(scannedThisRun, repository.library.value.items.size)
            } else {
                manager.onFinished(repository.library.value.items.size, scannedThisRun)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            flushBatch("中断前")
            manager.onCancelled(scannedThisRun, repository.library.value.items.size)
        } catch (e: Exception) {
            if (manager.state.value.phase != ScrapePhase.ERROR) {
                flushBatch("出错前")
                manager.onError(e.message ?: e.toString())
            }
        }
    }

    private fun updateNotification(text: String, count: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, count))
    }

    private fun buildNotification(text: String, count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.scrape_notif_title))
            .setContentText(text)
            .setSubText(if (count > 0) "本轮 $count 条" else null)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.scrape_notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}