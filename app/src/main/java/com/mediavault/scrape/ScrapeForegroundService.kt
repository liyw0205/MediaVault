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
import com.mediavault.data.ScrapeConfig
import com.mediavault.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScrapeForegroundService : Service() {
    companion object {
        const val EXTRA_REBUILD = "rebuild"
        const val EXTRA_ROOT_URIS = "root_uris"
        private const val CHANNEL_ID = "mediavault_scrape"
        private const val NOTIF_ID = 4102
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workJob: Job? = null
    @Volatile
    private var cancelRequested = false
    private val persistMutex = Mutex()

    private val app get() = application as MediaVaultApp
    private val manager get() = app.scrapeManager
    private val repository get() = app.repository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rebuild = intent?.getBooleanExtra(EXTRA_REBUILD, false) ?: false
        val rootUris = intent?.getStringArrayListExtra(EXTRA_ROOT_URIS)
        cancelRequested = false
        createChannel()
        startForeground(NOTIF_ID, buildNotification("准备扫描…", 0))
        workJob?.cancel()
        workJob = scope.launch {
            runScan(rebuild, rootUris)
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

    private suspend fun runScan(rebuild: Boolean, rootUris: List<String>?) {
        val store = repository.store
        if (rebuild && rootUris.isNullOrEmpty()) {
            store.clearScrapeRecord()
            repository.stripContentItems()
        }

        val threads = ScrapeConfig.readThreadCount(this)
        var scannedThisRun = 0

        suspend fun persistOne(item: MediaItem) {
            persistMutex.withLock {
                repository.appendSingleContentItem(item).onSuccess { total ->
                    scannedThisRun++
                    val msg = "已入库 $scannedThisRun 条（库 $total）· ${item.displayTitle()}"
                    manager.onProgress(msg, scannedThisRun, total)
                    updateNotification(msg, scannedThisRun)
                }.onFailure { e ->
                    manager.onError(e.message ?: "写入失败")
                    throw e
                }
            }
        }

        try {
            LocalScanner.scanTreeUrisParallel(
                this,
                store,
                rebuild,
                threadCount = threads,
                rootUrisFilter = rootUris,
                shouldCancel = { cancelRequested },
                onFile = { item ->
                    runBlocking {
                        persistOne(item)
                    }
                },
                onStatus = { msg ->
                    val total = repository.library.value.items.size
                    manager.onProgress(msg, scannedThisRun, total)
                    updateNotification(msg, scannedThisRun)
                },
            )
            if (cancelRequested) {
                manager.onCancelled(scannedThisRun, repository.library.value.items.size)
            } else {
                manager.onFinished(repository.library.value.items.size, scannedThisRun)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            manager.onCancelled(scannedThisRun, repository.library.value.items.size)
        } catch (e: Exception) {
            if (manager.state.value.phase != ScrapePhase.ERROR) {
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