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
import com.mediavault.data.RemoteFrameGate
import com.mediavault.data.RemoteLibraryScanner
import com.mediavault.data.ScrapeBatchAccumulator
import com.mediavault.data.ScrapeConfig
import com.mediavault.data.ScrapeSession
import com.mediavault.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScrapeForegroundService : Service() {
    companion object {
        const val EXTRA_REBUILD = "rebuild"
        const val EXTRA_ROOT_URIS = "root_uris"
        const val EXTRA_REMOTE_IDS = "remote_ids"
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
        val remoteIds = intent?.getStringArrayListExtra(EXTRA_REMOTE_IDS)
        cancelRequested = false
        createChannel()
        startForeground(NOTIF_ID, buildNotification("准备扫描…", 0))
        workJob?.cancel()
        workJob = scope.launch {
            runScan(rebuild, rootUris, remoteIds)
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

    private suspend fun runScan(rebuild: Boolean, rootUris: List<String>?, remoteIds: List<String>?) {
        val store = repository.store
        val fullRebuild = rebuild && rootUris.isNullOrEmpty() && remoteIds.isNullOrEmpty()
        if (fullRebuild) {
            store.clearAllScrapeRecords()
            repository.stripContentItems()
            repository.stripRemoteItems()
        }

        val scrapeSettings = ScrapeConfig.readSettings(this)
        val threads = scrapeSettings.threadCount
        val remoteFrame = scrapeSettings.remoteFrameConcurrency
        val scrapeSession = ScrapeSession(store)
        val frameGate = RemoteFrameGate(remoteFrame)
        val batchAcc = ScrapeBatchAccumulator(repository)
        var scannedThisRun = 0
        var lastNotifAtMs = 0L

        fun pushUiAndMaybeNotif(fileLabel: String, refreshNotif: Boolean) {
            val total = batchAcc.currentLibrarySize()
            val countLine = ScrapeProgressFormat.countLine(this@ScrapeForegroundService, scannedThisRun, total)
            val file = ScrapeProgressFormat.ellipsizeFileName(fileLabel)
            val forceJob = refreshNotif
            manager.onProgress(countLine, scannedThisRun, total, file, forceJobWrite = forceJob)
            if (!refreshNotif) return
            val compact = ScrapeProgressFormat.collapsedCompact(scannedThisRun, total)
            val notifBody = if (file.isNotBlank()) "$compact\n$file" else compact
            updateNotification(notifBody, scannedThisRun)
            lastNotifAtMs = System.currentTimeMillis()
        }

        fun persistOneBlocking(item: MediaItem) {
            kotlinx.coroutines.runBlocking {
                persistMutex.withLock {
                    batchAcc.offer(item)
                    batchAcc.afterOffer()
                    scannedThisRun++
                    val refreshNotif = ScrapeProgressThrottle.shouldRefreshNotification(
                        scannedThisRun,
                        lastNotifAtMs,
                    )
                    val showFile = refreshNotif ||
                        scannedThisRun % ScrapeProgressThrottle.UI_TITLE_EVERY_ITEMS == 0
                    val label = if (showFile) item.displayTitle() else ""
                    pushUiAndMaybeNotif(label, refreshNotif)
                }
            }
        }

        val scanLocal = rootUris.isNullOrEmpty() && remoteIds.isNullOrEmpty() || !rootUris.isNullOrEmpty()
        val scanRemote = rootUris.isNullOrEmpty() && remoteIds.isNullOrEmpty() || !remoteIds.isNullOrEmpty()

        try {
            if (scanLocal) {
                LocalScanner.scanTreeUrisParallel(
                    this@ScrapeForegroundService,
                    store,
                    scrapeSession,
                    scrapeSettings,
                    rebuild,
                    threadCount = threads,
                    rootUrisFilter = rootUris,
                    frameGate = frameGate,
                    shouldCancel = { cancelRequested },
                    onFile = { item -> persistOneBlocking(item) },
                    onStatus = { msg ->
                        if (ScrapeProgressFormat.isPrepStatus(msg)) {
                            val total = batchAcc.currentLibrarySize()
                            val refreshNotif = lastNotifAtMs == 0L ||
                                System.currentTimeMillis() - lastNotifAtMs >= ScrapeProgressThrottle.NOTIF_MIN_INTERVAL_MS
                            manager.onProgress(msg, scannedThisRun, total, "", forceJobWrite = refreshNotif)
                            if (refreshNotif) {
                                updateNotification(msg, scannedThisRun)
                                lastNotifAtMs = System.currentTimeMillis()
                            }
                        } else {
                            val file = ScrapeProgressFormat.fileFromLegacyMessage(msg)
                            pushUiAndMaybeNotif(file, refreshNotif = true)
                        }
                    },
                )
            }
            if (!cancelRequested && scanRemote) {
                RemoteLibraryScanner.scanRemotesParallel(
                    this@ScrapeForegroundService,
                    store,
                    scrapeSession,
                    scrapeSettings,
                    rebuild,
                    remoteIds,
                    threads,
                    frameGate,
                    shouldCancel = { cancelRequested },
                    onFile = { item -> persistOneBlocking(item) },
                    onStatus = { msg ->
                        if (ScrapeProgressFormat.isPrepStatus(msg)) {
                            val total = batchAcc.currentLibrarySize()
                            val refreshNotif = lastNotifAtMs == 0L ||
                                System.currentTimeMillis() - lastNotifAtMs >= ScrapeProgressThrottle.NOTIF_MIN_INTERVAL_MS
                            manager.onProgress(msg, scannedThisRun, total, "", forceJobWrite = refreshNotif)
                            if (refreshNotif) {
                                updateNotification(msg, scannedThisRun)
                                lastNotifAtMs = System.currentTimeMillis()
                            }
                        } else {
                            val file = ScrapeProgressFormat.fileFromLegacyMessage(msg)
                            pushUiAndMaybeNotif(file, refreshNotif = true)
                        }
                    },
                )
            }
            persistMutex.withLock {
                val total = batchAcc.flushAll()
                repository.reload()
                if (scannedThisRun > 0) {
                    val countLine = ScrapeProgressFormat.countLine(
                        this@ScrapeForegroundService,
                        scannedThisRun,
                        total,
                    )
                    manager.onProgress(
                        countLine,
                        scannedThisRun,
                        total,
                        "",
                        forceJobWrite = true,
                    )
                }
                if (cancelRequested) {
                    manager.onCancelled(scannedThisRun, total)
                } else {
                    manager.onFinished(total, scannedThisRun)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            persistMutex.withLock {
                batchAcc.flushAll()
                repository.reload()
            }
            manager.onCancelled(scannedThisRun, repository.library.value.items.size)
        } catch (e: Exception) {
            persistMutex.withLock {
                batchAcc.flushAll()
                repository.reload()
            }
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