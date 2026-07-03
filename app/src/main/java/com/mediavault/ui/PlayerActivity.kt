package com.mediavault.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.media3.common.Format
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.HistoryStore
import com.mediavault.data.PlaybackProgressStore
import com.mediavault.data.SubtitlePrefs
import com.mediavault.data.SubtitleTrackRanker
import com.mediavault.playback.PlaylistBuilder
import com.mediavault.remote.RemoteDataSourceFactory
import com.mediavault.remote.RemoteErrorMessages
import com.mediavault.remote.RemotePath
import com.mediavault.remote.RemotePlayUri
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mediavault.util.ScreenshotSaver
import java.io.File

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var playlist: List<PlaylistBuilder.Episode> = emptyList()
    private var playlistIndex = 0
    private var externalSubtitles: List<String> = emptyList()
    private sealed class SubSelection {
        object Auto : SubSelection()
        object Off : SubSelection()
        data class ManualTier(val tier: SubtitleTrackRanker.Tier) : SubSelection()
        data class External(val index: Int) : SubSelection()
        data class Embedded(val groupIndex: Int, val trackIndex: Int) : SubSelection()
    }
    private var subSelection: SubSelection = SubSelection.Auto
    private var autoResolved = false

    private lateinit var chromeController: PlayerChromeController

    private val historyStore by lazy { HistoryStore(this) }
    private val progressStore by lazy { PlaybackProgressStore(this) }
    private val progressHandler = Handler(Looper.getMainLooper())
    private var currentMediaPath: String = ""
    private var pendingResumeMs: Long = 0L
    private var remoteDurationHintShown = false
    private val progressTick = object : Runnable {
        override fun run() {
            persistPlaybackProgress()
            progressHandler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FusionFragmentLayouts.player(this))

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
            ?: resolveStartUri(startPath, store)
        externalSubtitles = episode?.subtitles ?: run {
            val arr = libItem?.raw?.optJSONArray("subtitles")
            if (arr == null) emptyList()
            else (0 until arr.length()).map { arr.optString(it) }
        }
        externalSubtitles = SubtitlePrefs.sortSubtitlePaths(this, externalSubtitles)
        subSelection = subSelectionFromPrefs()

        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView.useController = false

        chromeController = PlayerChromeController(this, { player }) { pos ->
            persistPlaybackProgress(pos)
        }
        chromeController.bind(playerView.rootView)

        val remoteFactory = RemoteDataSourceFactory(this, store)
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
                    if (state == Player.STATE_READY) {
                        maybeShowRemoteDurationHint(exo)
                    }
                    if (state == Player.STATE_ENDED) {
                        handleEndOfMedia()
                    }
                    updatePlayIcon()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    applyAudioPreference(exo, tracks)
                    applySubtitleSelection(exo, tracks)
                    updateSessionInfo()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayIcon()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val cause = error.cause ?: error
                    Toast.makeText(
                        this@PlayerActivity,
                        getString(R.string.player_remote_error_fmt, RemoteErrorMessages.userMessage(this@PlayerActivity, cause)),
                        Toast.LENGTH_LONG,
                    ).show()
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
        FusionFocusHelper.applyFusionToolbarFocus(window.decorView)
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
        updateResultPath()
        autoResolved = false
        remoteDurationHintShown = false
        val builder = ExoMediaItem.Builder().setUri(uri)
        if (externalSubtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                externalSubtitles.map { sub ->
                    val subUri = Uri.parse(sub)
                    ExoMediaItem.SubtitleConfiguration.Builder(subUri)
                        .setMimeType(guessSubMime(subUri))
                        .setLanguage("und")
                        .build()
                },
            )
        }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        exo.setMediaItem(builder.build())
        exo.prepare()
        exo.playWhenReady = true
        findViewById<TextView>(R.id.playerTitleOverlay)?.text = title
        updateSessionInfo()
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
        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { showAudioMenu() }
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
        val rawDur = p.duration
        val pos = if (rawDur > 0) {
            (p.currentPosition + ms).coerceIn(0L, rawDur)
        } else {
            (p.currentPosition + ms).coerceAtLeast(0L)
        }
        p.seekTo(pos)
        persistPlaybackProgress(pos)
    }

    private fun playIndex(index: Int) {
        if (index !in playlist.indices) return
        playlistIndex = index
        val ep = playlist[index]
        externalSubtitles = SubtitlePrefs.sortSubtitlePaths(this, ep.subtitles)
        subSelection = subSelectionFromPrefs()
        historyStore.add(ep.path)
        pendingResumeMs = progressStore.getPositionMs(ep.path)
        player?.let { loadEpisode(it, ep.uri, ep.title, ep.path) }
        findViewById<TextView>(R.id.playerTitleOverlay).text = ep.title
        updateNavButtons()
        updatePlayIcon()
        updateSessionInfo()
    }

    private fun updateNavButtons() {
        findViewById<ImageButton>(R.id.btnPrev).isEnabled = playlistIndex > 0
        findViewById<ImageButton>(R.id.btnNext).isEnabled = playlistIndex < playlist.size - 1
        findViewById<ImageButton>(R.id.btnPlaylist).isEnabled = playlist.size > 1
    }

    private fun updateSessionInfo() {
        val info = findViewById<TextView>(R.id.playerSessionInfo) ?: return
        val total = playlist.size.coerceAtLeast(1)
        val position = if (playlistIndex in playlist.indices) playlistIndex + 1 else 1
        val source = if (RemotePath.isRemote(currentMediaPath)) {
            getString(R.string.player_source_remote)
        } else {
            getString(R.string.player_source_local)
        }
        info.text = getString(
            R.string.player_session_info_fmt,
            position,
            total,
            source,
            subtitleStateLabel(),
            audioStateLabel(),
            autoplayTargetLabel(),
        )
        info.visibility = View.VISIBLE
    }

    private fun subtitleStateLabel(): String {
        val state = when (subSelection) {
            is SubSelection.Auto -> getString(R.string.player_session_subtitle_auto)
            is SubSelection.Off -> getString(R.string.player_session_subtitle_off)
            is SubSelection.ManualTier,
            is SubSelection.Embedded,
            is SubSelection.External -> getString(R.string.player_session_subtitle_manual)
        }
        return getString(R.string.player_session_subtitle_fmt, state)
    }

    private fun audioStateLabel(): String {
        val selected = selectedAudioLabel(player?.currentTracks)
        return if (PlaybackPrefs.getAudioMode(this) == PlaybackPrefs.AudioMode.MANUAL) {
            val label = PlaybackPrefs.getAudioLabel(this) ?: selected
            if (label != null) {
                getString(R.string.player_session_audio_manual_fmt, label)
            } else {
                getString(R.string.player_session_audio_default)
            }
        } else if (selected != null) {
            getString(R.string.player_session_audio_auto_fmt, selected)
        } else {
            getString(R.string.player_session_audio_default)
        }
    }

    private data class AudioCandidate(
        val group: Tracks.Group,
        val trackIndex: Int,
        val label: String,
        val match: String,
        val language: String?,
    )

    private fun audioCandidates(tracks: Tracks): List<AudioCandidate> {
        val out = mutableListOf<AudioCandidate>()
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                out.add(
                    AudioCandidate(
                        group = group,
                        trackIndex = i,
                        label = audioTrackLabel(format, i),
                        match = audioMatchKey(format),
                        language = format.language?.trim()?.takeIf { it.isNotBlank() }?.lowercase(),
                    ),
                )
            }
        }
        return out
    }

    private fun selectedAudioLabel(tracks: Tracks?): String? {
        if (tracks == null) return null
        return audioCandidates(tracks).firstOrNull { it.group.isTrackSelected(it.trackIndex) }?.label
    }

    private fun audioTrackLabel(format: Format, index: Int): String {
        val name = format.label?.trim()?.takeIf { it.isNotBlank() }
            ?: format.language?.trim()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.audio_track_number_fmt, index + 1)
        val channels = if (format.channelCount > 0) {
            getString(R.string.audio_track_channels_fmt, format.channelCount)
        } else {
            null
        }
        return listOfNotNull(name, channels).joinToString(" · ")
    }

    private fun audioMatchKey(format: Format): String =
        listOf(
            format.language.orEmpty(),
            format.label.orEmpty(),
            format.id.orEmpty(),
            format.sampleMimeType.orEmpty(),
            format.codecs.orEmpty(),
        )
            .joinToString("|") { it.trim().lowercase() }
            .takeIf { it.isNotBlank() }
            ?: "audio"

    private fun applyAudioPreference(exo: ExoPlayer, tracks: Tracks) {
        if (PlaybackPrefs.getAudioMode(this) != PlaybackPrefs.AudioMode.MANUAL) return
        val match = PlaybackPrefs.getAudioMatch(this) ?: return
        val preferredLanguage = PlaybackPrefs.getAudioLanguage(this)
        val preferredLabel = PlaybackPrefs.getAudioLabel(this)
        val candidates = audioCandidates(tracks)
        val pick = candidates.firstOrNull { it.match == match }
            ?: candidates.firstOrNull { preferredLanguage != null && it.language == preferredLanguage }
            ?: candidates.firstOrNull { preferredLabel != null && it.label.equals(preferredLabel, ignoreCase = true) }
            ?: return
        if (pick.group.isTrackSelected(pick.trackIndex)) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(pick.group.mediaTrackGroup, pick.trackIndex))
            .build()
    }

    private fun autoplayTargetLabel(): String {
        if (playlist.size <= 1) return getString(R.string.player_autoplay_target_single)
        return when (PlaybackPrefs.getAutoplayMode(this)) {
            PlaybackPrefs.AutoplayMode.OFF -> getString(R.string.player_autoplay_target_stop)
            PlaybackPrefs.AutoplayMode.REPEAT_ONE -> getString(R.string.player_autoplay_target_repeat_current)
            PlaybackPrefs.AutoplayMode.SEQUENTIAL -> {
                val next = playlist.getOrNull(playlistIndex + 1)
                if (next != null) {
                    getString(R.string.player_autoplay_target_next_fmt, next.title)
                } else {
                    getString(R.string.player_autoplay_target_stop)
                }
            }
            PlaybackPrefs.AutoplayMode.LOOP_COLLECTION -> {
                val nextIndex = if (playlistIndex < playlist.size - 1) playlistIndex + 1 else 0
                val next = playlist.getOrNull(nextIndex)
                if (next != null) {
                    getString(R.string.player_autoplay_target_loop_fmt, next.title)
                } else {
                    getString(R.string.player_autoplay_target_single)
                }
            }
        }
    }

    private fun showAudioMenu() {
        val p = player ?: return
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val autoMark = if (PlaybackPrefs.getAudioMode(this) == PlaybackPrefs.AudioMode.AUTO) "● " else "○ "
        options.add("$autoMark${getString(R.string.audio_auto)}")
        actions.add {
            PlaybackPrefs.setAudioAuto(this)
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
            updateSessionInfo()
        }

        val candidates = audioCandidates(p.currentTracks)
        if (candidates.isEmpty()) {
            options.add(getString(R.string.audio_track_empty))
            actions.add { updateSessionInfo() }
        } else {
            candidates.forEach { candidate ->
                val mark = if (candidate.group.isTrackSelected(candidate.trackIndex)) "● " else "○ "
                options.add("$mark${getString(R.string.audio_track_fmt, candidate.label)}")
                actions.add {
                    PlaybackPrefs.setAudioManual(this, candidate.match, candidate.label, candidate.language)
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(candidate.group.mediaTrackGroup, candidate.trackIndex))
                        .build()
                    updateSessionInfo()
                }
            }
        }

        MvDialog.show(
            MvDialog.builder(this)
                .setTitle(R.string.audio_pick)
                .setItems(options.toTypedArray()) { _, which -> actions[which]() },
        )
    }

    private fun showPlaylist() {
        if (playlist.isEmpty()) return
        val autoplayLabel = getString(
            R.string.player_autoplay_current_fmt,
            PlaybackPrefs.label(this),
        )
        val header = "⚙ ${getString(R.string.player_autoplay_title)} · $autoplayLabel"
        val labels = (listOf(header) + playlist.mapIndexed { i, ep ->
            val mark = if (i == playlistIndex) "▶ " else ""
            "$mark${ep.title}"
        }).toTypedArray()
        MvDialog.show(
            MvDialog.builder(this)
                .setTitle(R.string.player_playlist)
                .setItems(labels) { _, which ->
                    if (which == 0) showAutoplayMenu()
                    else playIndex(which - 1)
                },
        )
    }

    private fun showAutoplayMenu() {
        val modes = listOf(
            PlaybackPrefs.AutoplayMode.SEQUENTIAL,
            PlaybackPrefs.AutoplayMode.REPEAT_ONE,
            PlaybackPrefs.AutoplayMode.LOOP_COLLECTION,
            PlaybackPrefs.AutoplayMode.OFF,
        )
        val current = PlaybackPrefs.getAutoplayMode(this)
        val labels = modes.map { m ->
            val mark = if (m == current) "● " else "○ "
            "$mark${PlaybackPrefs.label(this, m)}"
        }.toTypedArray()
        MvDialog.show(
            MvDialog.builder(this)
                .setTitle(R.string.player_autoplay_title)
                .setItems(labels) { _, which ->
                    PlaybackPrefs.setAutoplayMode(this, modes[which])
                    updateSessionInfo()
                },
        )
    }

    private fun maybeShowRemoteDurationHint(exo: ExoPlayer) {
        if (remoteDurationHintShown) return
        if (RemotePath.parse(currentMediaPath) == null) return
        if (exo.duration > 0) return
        remoteDurationHintShown = true
        Toast.makeText(this, R.string.player_duration_unknown_seek_hint, Toast.LENGTH_LONG).show()
    }

    private fun handleEndOfMedia() {
        val mode = PlaybackPrefs.getAutoplayMode(this)
        when (mode) {
            PlaybackPrefs.AutoplayMode.OFF -> Unit
            PlaybackPrefs.AutoplayMode.REPEAT_ONE -> {
                val p = player ?: return
                p.seekTo(0L)
                p.playWhenReady = true
            }
            PlaybackPrefs.AutoplayMode.SEQUENTIAL -> {
                if (playlistIndex < playlist.size - 1) playIndex(playlistIndex + 1)
            }
            PlaybackPrefs.AutoplayMode.LOOP_COLLECTION -> {
                if (playlist.isEmpty()) return
                val next = if (playlistIndex < playlist.size - 1) playlistIndex + 1 else 0
                playIndex(next)
            }
        }
    }

    private fun showSubtitleMenu() {
        val p = player ?: return
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val autoMark = if (subSelection is SubSelection.Auto) "● " else "○ "
        options.add("$autoMark${getString(R.string.subtitle_auto)}")
        actions.add {
            SubtitlePrefs.setPersistedAuto(this)
            subSelection = SubSelection.Auto
            autoResolved = false
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            applySubtitleSelection(p, p.currentTracks)
            updateSessionInfo()
        }

        val offMark = if (subSelection is SubSelection.Off) "● " else "○ "
        options.add("$offMark${getString(R.string.subtitle_off)}")
        actions.add {
            SubtitlePrefs.setPersistedOff(this)
            subSelection = SubSelection.Off
            disableTextTracks(p)
            updateSessionInfo()
        }

        val (embeddedGroups, externalGroups) = classifyTextGroups(p.currentTracks)
        embeddedGroups.forEach { (gi, group) ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val label = group.getTrackFormat(i).language ?: "内嵌 #$i"
                val current = subSelection
                val mark = if (current is SubSelection.Embedded && current.groupIndex == gi && current.trackIndex == i) "● " else "○ "
                options.add("$mark${getString(R.string.subtitle_embedded_fmt, label)}")
                val tg: TrackGroup = group.mediaTrackGroup
                actions.add {
                    val fmt = group.getTrackFormat(i)
                    val tier = SubtitleTrackRanker.tierFromTokenString(
                        "${fmt.language.orEmpty()} ${fmt.label.orEmpty()} ${fmt.id.orEmpty()}",
                    )
                    SubtitlePrefs.setPersistedManualTier(this, tier)
                    subSelection = SubSelection.ManualTier(tier)
                    autoResolved = true
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(tg, i))
                        .build()
                    updateSessionInfo()
                }
            }
        }
        externalGroups.forEachIndexed { extIdx, pair ->
            val (_, group) = pair
            if (extIdx !in externalSubtitles.indices) return@forEachIndexed
            val current = subSelection
            val mark = if (current is SubSelection.External && current.index == extIdx) "● " else "○ "
            options.add("$mark${getString(R.string.subtitle_external_fmt, File(externalSubtitles[extIdx]).name)}")
            val tg: TrackGroup = group.mediaTrackGroup
            actions.add {
                val path = externalSubtitles[extIdx]
                val tier = SubtitleTrackRanker.tierFromTokenString(path)
                SubtitlePrefs.setPersistedManualTier(this, tier)
                subSelection = SubSelection.ManualTier(tier)
                autoResolved = true
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(tg, 0))
                    .build()
                updateSessionInfo()
            }
        }

        MvDialog.show(
            MvDialog.builder(this)
                .setTitle(R.string.subtitle_pick)
                .setItems(options.toTypedArray()) { _, which -> actions[which]() },
        )
    }

    /**
     * 外挂字幕通过 MediaItem.SubtitleConfiguration 由 Merging 源追加，
     * 故按出现顺序：先内嵌（容器内）后外挂；外挂条目数等于 externalSubtitles.size。
     */
    private fun classifyTextGroups(tracks: Tracks): Pair<List<Pair<Int, Tracks.Group>>, List<Pair<Int, Tracks.Group>>> {
        val textGroups = mutableListOf<Pair<Int, Tracks.Group>>()
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type == C.TRACK_TYPE_TEXT) textGroups.add(gi to g)
        }
        val externalCount = externalSubtitles.size.coerceAtMost(textGroups.size)
        val embedded = textGroups.dropLast(externalCount)
        val external = textGroups.takeLast(externalCount)
        return embedded to external
    }

    private fun subSelectionFromPrefs(): SubSelection = when (SubtitlePrefs.getPersistedMode(this)) {
        SubtitlePrefs.PersistedMode.OFF -> SubSelection.Off
        SubtitlePrefs.PersistedMode.MANUAL_TIER -> SubSelection.ManualTier(
            SubtitleTrackRanker.Tier.fromOrdinal(SubtitlePrefs.getManualTier(this)),
        )
        SubtitlePrefs.PersistedMode.AUTO -> SubSelection.Auto
    }

    private fun applySubtitleSelection(exo: ExoPlayer, tracks: Tracks) {
        when (val sel = subSelection) {
            is SubSelection.Off -> disableTextTracks(exo)
            is SubSelection.Embedded -> {
                val (embedded, _) = classifyTextGroups(tracks)
                val g = embedded.firstOrNull { it.first == sel.groupIndex }?.second ?: return
                if (sel.trackIndex !in 0 until g.length || !g.isTrackSupported(sel.trackIndex)) return
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, sel.trackIndex))
                    .build()
            }
            is SubSelection.External -> {
                val (_, external) = classifyTextGroups(tracks)
                val pair = external.getOrNull(sel.index) ?: return
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(pair.second.mediaTrackGroup, 0))
                    .build()
            }
            is SubSelection.ManualTier, is SubSelection.Auto -> {
                if (sel is SubSelection.Auto && autoResolved) return
                val (embedded, external) = classifyTextGroups(tracks)
                if (embedded.isEmpty() && external.isEmpty()) return
                val pick = when (sel) {
                    is SubSelection.ManualTier -> {
                        pickPreferredTextTrack(embedded, sel.tier)
                            ?: pickPreferredTextTrack(external, sel.tier)
                    }
                    else -> {
                        pickPreferredTextTrack(embedded, null)
                            ?: pickPreferredTextTrack(external, null)
                    }
                } ?: return
                if (sel is SubSelection.Auto) autoResolved = true
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(pick.first, pick.second))
                    .build()
            }
        }
    }

    /** 内嵌优先，再外挂；按 [SubtitlePrefs] 语言偏好或指定 [tier] 选轨。 */
    private fun pickPreferredTextTrack(
        groups: List<Pair<Int, Tracks.Group>>,
        tier: SubtitleTrackRanker.Tier?,
    ): Pair<TrackGroup, Int>? {
        if (groups.isEmpty()) return null
        val primary = SubtitlePrefs.getPrimary(this)
        val candidates = mutableListOf<Triple<TrackGroup, Int, SubtitleTrackRanker.Tier>>()
        for ((_, g) in groups) {
            val tg = g.mediaTrackGroup
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val fmt = g.getTrackFormat(i)
                val token = "${fmt.language.orEmpty()} ${fmt.label.orEmpty()} ${fmt.id.orEmpty()}"
                candidates.add(Triple(tg, i, SubtitleTrackRanker.tierFromTokenString(token)))
            }
        }
        if (candidates.isEmpty()) return null
        val pool = if (tier != null) {
            candidates.filter { it.third == tier }.ifEmpty { candidates }
        } else candidates
        val best = pool.minByOrNull { (tg, idx, t) ->
            SubtitleTrackRanker.rankTrackTokens(
                tg.getFormat(idx).language,
                tg.getFormat(idx).label,
                tg.getFormat(idx).id,
                primary,
            )
        } ?: return null
        return best.first to best.second
    }

    private fun disableTextTracks(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun updateResultPath() {
        if (currentMediaPath.isBlank()) return
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, currentMediaPath))
    }

    private fun reloadCurrentMedia() {
        val ep = playlist.getOrNull(playlistIndex)
        val pos = player?.currentPosition ?: 0L
        pendingResumeMs = 0L
        if (ep != null) {
            player?.let {
                loadEpisode(it, ep.uri, ep.title, ep.path)
                it.seekTo(pos)
            }
            return
        }
        val store = (application as MediaVaultApp).repository.store
        val uri = resolveStartUri(currentMediaPath, store)
        val title = findViewById<TextView>(R.id.playerTitleOverlay).text?.toString().orEmpty()
        player?.let {
            loadEpisode(it, uri, title, currentMediaPath)
            it.seekTo(pos)
        }
    }

    private fun resolveStartUri(startPath: String, store: com.mediavault.data.MediaStore): Uri {
        if (startPath.startsWith("content://") || startPath.startsWith("file://")) {
            return Uri.parse(startPath)
        }
        if (RemotePath.isRemote(startPath)) {
            val parsed = RemotePath.parse(startPath)
            if (parsed != null) {
                return RemotePlayUri.forRelative(parsed.configId, parsed.relativePath)
            }
        }
        val f = File(startPath)
        if (f.isAbsolute && f.exists()) return Uri.fromFile(f)
        return Uri.parse(startPath)
    }

    private fun takeScreenshot() {
        val pv = findViewById<PlayerView>(R.id.playerView)
        val w = pv.width.coerceAtLeast(1)
        val h = pv.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        fun saveBitmap() {
            val result = ScreenshotSaver.savePng(this, bmp)
            bmp.recycle()
            result.fold(
                onSuccess = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() },
                onFailure = { e ->
                    Toast.makeText(this, e.message ?: getString(R.string.screenshot_failed), Toast.LENGTH_SHORT).show()
                },
            )
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
        const val EXTRA_RESULT_PATH = "player_result_path"

        fun intent(ctx: Context, path: String, title: String): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)

        fun intent(ctx: Context, uri: Uri, title: String): Intent =
            intent(ctx, uri.toString(), title)
    }
}
