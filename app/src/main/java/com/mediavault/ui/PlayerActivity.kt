package com.mediavault.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.HistoryStore
import com.mediavault.data.PlaybackProgressStore
import com.mediavault.playback.PlaylistBuilder
import com.mediavault.remote.RemoteDataSourceFactory
import com.mediavault.remote.RemotePath
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.io.FileOutputStream

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var playlist: List<PlaylistBuilder.Episode> = emptyList()
    private var playlistIndex = 0
    private var externalSubtitles: List<String> = emptyList()
    private var selectedSubIndex = -1 // -1 off, 0+ external, Int.MAX embedded handled separately

    private lateinit var chromeController: PlayerChromeController

    private val historyStore by lazy { HistoryStore(this) }
    private val progressStore by lazy { PlaybackProgressStore(this) }
    private val progressHandler = Handler(Looper.getMainLooper())
    private var currentMediaPath: String = ""
    private var pendingResumeMs: Long = 0L
    private val progressTick = object : Runnable {
        override fun run() {
            persistPlaybackProgress()
            progressHandler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val startPath = intent.getStringExtra(EXTRA_PATH) ?: intent.getStringExtra(EXTRA_URI_LEGACY)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        if (startPath.isNullOrBlank()) {
            finish()
            return
        }
        currentMediaPath = startPath
        pendingResumeMs = intent.getLongExtra(EXTRA_RESUME_MS, -1L).let { extra ->
            if (extra >= 0) extra else progressStore.getPositionMs(startPath)
        }

        val repo = (application as MediaVaultApp).repository
        val store = repo.store
        val libItem = repo.library.value.items.find { it.path == startPath }
        val (pl, idx) = PlaylistBuilder.buildPlaylist(repo.library.value.items, startPath, store)
        playlist = pl
        playlistIndex = if (idx >= 0) idx else 0

        val episode = playlist.getOrNull(playlistIndex)
        val uri = episode?.uri
            ?: libItem?.let { PlaylistBuilder.resolveUri(it, store) }
            ?: Uri.parse(startPath)
        externalSubtitles = episode?.subtitles ?: run {
            val arr = libItem?.raw?.optJSONArray("subtitles")
            if (arr == null) emptyList()
            else (0 until arr.length()).map { arr.optString(it) }
        }

        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView.useController = false

        chromeController = PlayerChromeController(this, { player }) { pos ->
            persistPlaybackProgress(pos)
        }
        chromeController.bind(playerView.rootView)

        val remoteFactory = RemoteDataSourceFactory(store)
        val defaultDs = DefaultDataSource.Factory(this, remoteFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(defaultDs)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { exo ->
            playerView.player = exo
            loadEpisode(exo, uri, title.ifBlank { episode?.title ?: "" }, episode?.path ?: startPath)
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && pendingResumeMs > 0) {
                        val seek = pendingResumeMs
                        pendingResumeMs = 0L
                        exo.seekTo(seek)
                    }
                    if (state == Player.STATE_ENDED && playlistIndex < playlist.size - 1) {
                        playIndex(playlistIndex + 1)
                    }
                    updatePlayIcon()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayIcon()
                }
            })
        }

        PlayerGestureController(
            playerView,
            { player },
            chromeController,
        ) { pos -> persistPlaybackProgress(pos) }.attach()

        bindControls(title.ifBlank { episode?.title ?: "" })
        updateNavButtons()
        progressHandler.postDelayed(progressTick, 10_000)
    }

    private fun persistPlaybackProgress(positionMs: Long? = null) {
        val p = player ?: return
        val path = currentMediaPath
        if (path.isBlank()) return
        val pos = positionMs ?: p.currentPosition
        progressStore.save(path, pos, p.duration)
    }

    private fun loadEpisode(exo: ExoPlayer, uri: Uri, title: String, mediaPath: String) {
        currentMediaPath = mediaPath
        val builder = ExoMediaItem.Builder().setUri(uri)
        if (externalSubtitles.isNotEmpty() && selectedSubIndex in externalSubtitles.indices) {
            val subUri = Uri.parse(externalSubtitles[selectedSubIndex])
            builder.setSubtitleConfigurations(
                listOf(
                    ExoMediaItem.SubtitleConfiguration.Builder(subUri)
                        .setMimeType(guessSubMime(subUri))
                        .setLanguage("und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        exo.setMediaItem(builder.build())
        exo.prepare()
        exo.playWhenReady = true
        findViewById<TextView>(R.id.playerTitleOverlay)?.text = title
    }

    private fun guessSubMime(uri: Uri): String {
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        return when {
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun bindControls(initialTitle: String) {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.playerTitleOverlay).text = initialTitle

        findViewById<ImageButton>(R.id.btnPlayPause).setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener { seekRel(-10_000) }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener { seekRel(10_000) }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { playIndex(playlistIndex - 1) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { playIndex(playlistIndex + 1) }
        findViewById<ImageButton>(R.id.btnPlaylist).setOnClickListener { showPlaylist() }
        findViewById<ImageButton>(R.id.btnSubtitle).setOnClickListener { showSubtitleMenu() }
        findViewById<ImageButton>(R.id.btnScreenshot).setOnClickListener { takeScreenshot() }
    }

    private fun togglePlay() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        val playing = player?.playWhenReady == true
        findViewById<ImageButton>(R.id.btnPlayPause).setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
    }

    private fun seekRel(ms: Long) {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0L)
        val pos = (p.currentPosition + ms).coerceIn(0L, dur)
        p.seekTo(pos)
        persistPlaybackProgress(pos)
    }

    private fun playIndex(index: Int) {
        if (index !in playlist.indices) return
        playlistIndex = index
        val ep = playlist[index]
        externalSubtitles = ep.subtitles
        historyStore.add(ep.path)
        pendingResumeMs = progressStore.getPositionMs(ep.path)
        player?.let { loadEpisode(it, ep.uri, ep.title, ep.path) }
        findViewById<TextView>(R.id.playerTitleOverlay).text = ep.title
        updateNavButtons()
        updatePlayIcon()
    }

    private fun updateNavButtons() {
        findViewById<ImageButton>(R.id.btnPrev).isEnabled = playlistIndex > 0
        findViewById<ImageButton>(R.id.btnNext).isEnabled = playlistIndex < playlist.size - 1
        findViewById<ImageButton>(R.id.btnPlaylist).isEnabled = playlist.size > 1
    }

    private fun showPlaylist() {
        if (playlist.isEmpty()) return
        val labels = playlist.mapIndexed { i, ep ->
            val mark = if (i == playlistIndex) "▶ " else ""
            "$mark${ep.title}"
        }.toTypedArray()
        MvDialog.builder(this)
            .setTitle(R.string.player_playlist)
            .setItems(labels) { _, which -> playIndex(which) }
            .show()
    }

    private fun showSubtitleMenu() {
        val p = player ?: return
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        options.add(getString(R.string.subtitle_off))
        actions.add {
            selectedSubIndex = -1
            disableTextTracks(p)
            reloadCurrentMedia()
        }
        externalSubtitles.forEachIndexed { i, uri ->
            options.add(getString(R.string.subtitle_external_fmt, File(uri).name))
            actions.add {
                selectedSubIndex = i
                reloadCurrentMedia()
            }
        }
        val tracks = p.currentTracks
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val label = group.getTrackFormat(i).language ?: "内嵌 #$i"
                options.add(getString(R.string.subtitle_embedded_fmt, label))
                val gi = group.mediaTrackGroup
                actions.add {
                    selectedSubIndex = -1
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(gi, i))
                        .build()
                }
            }
        }
        MvDialog.builder(this)
            .setTitle(R.string.subtitle_pick)
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun disableTextTracks(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun reloadCurrentMedia() {
        val ep = playlist.getOrNull(playlistIndex) ?: return
        val pos = player?.currentPosition ?: 0L
        pendingResumeMs = 0L
        player?.let {
            loadEpisode(it, ep.uri, ep.title, ep.path)
            it.seekTo(pos)
        }
    }

    private fun takeScreenshot() {
        val pv = findViewById<PlayerView>(R.id.playerView)
        val w = pv.width.coerceAtLeast(1)
        val h = pv.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "shots").apply { mkdirs() }
        val file = File(dir, "shot_${System.currentTimeMillis()}.png")

        fun saveBitmap() {
            try {
                FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 92, out) }
                Toast.makeText(this, getString(R.string.screenshot_saved, file.absolutePath), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "截图失败", Toast.LENGTH_SHORT).show()
            } finally {
                bmp.recycle()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val source: android.view.Surface? = when (val v = pv.videoSurfaceView) {
                is TextureView -> v.surfaceTexture?.let { android.view.Surface(it) }
                is SurfaceView -> v.holder.surface
                else -> null
            }
            if (source != null && source.isValid) {
                PixelCopy.request(source, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) saveBitmap()
                    else {
                        bmp.recycle()
                        Toast.makeText(this, R.string.screenshot_use_system, Toast.LENGTH_SHORT).show()
                    }
                }, Handler(Looper.getMainLooper()))
                return
            }
        }
        bmp.recycle()
        Toast.makeText(this, R.string.screenshot_use_system, Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        persistPlaybackProgress()
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressTick)
        persistPlaybackProgress()
        if (::chromeController.isInitialized) chromeController.release()
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_URI_LEGACY = "uri"
        private const val EXTRA_TITLE = "title"
        const val EXTRA_RESUME_MS = "resume_ms"

        fun intent(ctx: Context, path: String, title: String): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)

        fun intent(ctx: Context, uri: Uri, title: String): Intent =
            intent(ctx, uri.toString(), title)
    }
}