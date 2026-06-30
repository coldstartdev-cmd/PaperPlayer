package com.jp.paperplayer.viewmodel

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.jp.paperplayer.data.LyricLine
import com.jp.paperplayer.data.PlayCountStore
import com.jp.paperplayer.data.Song
import com.jp.paperplayer.lyrics.LyricsExtractor
import com.jp.paperplayer.service.PlaybackService
import com.jp.paperplayer.translation.translateLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentSong: Song? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyrics: List<LyricLine> = emptyList(),
    val currentLyricIndex: Int = -1,
    val translatedLyrics: List<LyricLine>? = null,
    val isTranslating: Boolean = false,
    val showTranslation: Boolean = false,
    val translationError: String? = null,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var songList: List<Song> = emptyList()

    private val playCountStore = PlayCountStore(getApplication())

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId?.toLongOrNull()
            if (mediaId != null) playCountStore.increment(mediaId)
            val song = songList.firstOrNull { it.id == mediaId }
            val filePath = mediaItem?.mediaMetadata?.extras?.getString("filePath") ?: ""
            _state.update { it.copy(currentSong = song) }
            extractLyrics(filePath)
        }
    }

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java)
        )
        val future = MediaController.Builder(getApplication(), token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)
                startPositionLoop()
            } catch (e: Exception) {
                // Service not yet ready — will retry on next interaction
            }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    /** Polls the player position 5× per second to drive the progress bar and lyrics highlight. */
    private fun startPositionLoop() {
        viewModelScope.launch {
            while (true) {
                val c = controller
                if (c != null) {
                    val pos = c.currentPosition.coerceAtLeast(0L)
                    val dur = if (c.duration == C.TIME_UNSET) 0L else c.duration.coerceAtLeast(0L)
                    val idx = findCurrentLyricIndex(_state.value.lyrics, pos)
                    _state.update { it.copy(positionMs = pos, durationMs = dur, currentLyricIndex = idx) }
                }
                delay(200L)
            }
        }
    }

    fun play(songs: List<Song>, startIndex: Int) {
        songList = songs
        val c = controller ?: return

        val items = songs.map { song ->
            MediaItem.Builder()
                .setUri(song.uri)
                .setMediaId(song.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.albumArtUri)
                        .setExtras(Bundle().apply { putString("filePath", song.filePath) })
                        .build()
                )
                .build()
        }

        _state.update { it.copy(currentSong = songs[startIndex]) }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()

        extractLyrics(songs[startIndex].filePath)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun pause() {
        controller?.pause()
    }

    fun skipNext() {
        controller?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        val c = controller ?: return
        if (c.currentPosition > 3_000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun playShuffle(songs: List<Song>) {
        val counts = playCountStore.getAllCounts()
        val shuffled = smartShuffle(songs, counts)
        _shuffleEnabled.value = true
        play(shuffled, 0)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        _shuffleEnabled.value = enabled
    }

    private fun smartShuffle(songs: List<Song>, counts: Map<Long, Int>): List<Song> {
        return songs
            .groupBy { counts[it.id] ?: 0 }
            .entries
            .sortedBy { it.key }
            .flatMap { it.value.shuffled() }
    }

    fun toggleTranslation(targetLanguage: String) {
        val s = _state.value
        if (s.showTranslation) {
            _state.update { it.copy(showTranslation = false) }
            return
        }
        if (s.translatedLyrics != null) {
            _state.update { it.copy(showTranslation = true) }
            return
        }
        if (s.lyrics.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isTranslating = true, translationError = null) }
            try {
                val translated = withContext(Dispatchers.IO) {
                    translateLyrics(s.lyrics, targetLanguage = targetLanguage)
                }
                _state.update { it.copy(translatedLyrics = translated, isTranslating = false, showTranslation = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isTranslating = false, translationError = e.message ?: "Translation failed") }
            }
        }
    }

    fun reloadCurrentLyrics() {
        val path = _state.value.currentSong?.filePath ?: return
        extractLyrics(path)
    }

    private fun extractLyrics(filePath: String) {
        viewModelScope.launch {
            _state.update { it.copy(lyrics = emptyList(), currentLyricIndex = -1, translatedLyrics = null, showTranslation = false) }
            if (filePath.isEmpty()) return@launch
            val lyrics = withContext(Dispatchers.IO) { LyricsExtractor.extract(filePath) }
            _state.update { it.copy(lyrics = lyrics) }
        }
    }

    private fun findCurrentLyricIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
        if (lyrics.isEmpty()) return -1
        var index = 0
        for (i in lyrics.indices) {
            if (lyrics[i].timeMs <= positionMs) index = i else break
        }
        return index
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
