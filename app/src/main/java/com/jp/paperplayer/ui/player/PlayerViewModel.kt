package com.jp.paperplayer.ui.player

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
import com.jp.paperplayer.data.PlayCountStore
import com.jp.paperplayer.data.SettingsStore
import com.jp.paperplayer.data.ShuffleStrategy
import com.jp.paperplayer.data.TranslationStore
import com.jp.paperplayer.lyrics.LyricsExtractor
import com.jp.paperplayer.model.data.LyricLine
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.model.ui.PlayerState
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

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var songList: List<Song> = emptyList()

    private val playCountStore = PlayCountStore(getApplication())
    private val translationStore = TranslationStore(getApplication())
    private val settingsStore = SettingsStore(getApplication())

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _shuffleStrategy = MutableStateFlow(settingsStore.getShuffleStrategy())
    val shuffleStrategy: StateFlow<ShuffleStrategy> = _shuffleStrategy.asStateFlow()

    private val _playCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val playCounts: StateFlow<Map<Long, Int>> = _playCounts.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId?.toLongOrNull()
            if (mediaId != null) {
                playCountStore.increment(mediaId)
                _playCounts.value = playCountStore.getAllCounts()
            }
            val song = songList.firstOrNull { it.id == mediaId }
            val filePath = mediaItem?.mediaMetadata?.extras?.getString("filePath") ?: ""
            _state.update { it.copy(currentSong = song, currentQueueIndex = controller?.currentMediaItemIndex ?: -1) }
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
                _playCounts.value = playCountStore.getAllCounts()
                startPositionLoop()
            } catch (e: Exception) {
                // Service not yet ready — will retry on next interaction
            }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    private fun startPositionLoop() {
        viewModelScope.launch {
            while (true) {
                val c = controller
                if (c != null) {
                    val pos = c.currentPosition.coerceAtLeast(0L)
                    val dur = if (c.duration == C.TIME_UNSET) 0L else c.duration.coerceAtLeast(0L)
                    val idx = findCurrentLyricIndex(_state.value.displayLyrics, pos)
                    _state.update { it.copy(positionMs = pos, durationMs = dur, currentLyricIndex = idx) }
                }
                delay(200L)
            }
        }
    }

    fun play(songs: List<Song>, startIndex: Int) {
        songList = songs
        val c = controller ?: return

        val items = songs.map(::buildMediaItem)

        _state.update { it.copy(currentSong = songs[startIndex], queue = songs, currentQueueIndex = startIndex) }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()

        extractLyrics(songs[startIndex].filePath)
    }

    // Loads a song as "current" (so screens reading playerState.currentSong/lyrics work,
    // e.g. opening the lyrics editor for a song from the tag editor) without starting playback.
    // Explicitly pauses after prepare() because playWhenReady persists across setMediaItems() —
    // if something else was already playing, the new item would otherwise start playing too.
    fun loadForEditing(song: Song) {
        songList = listOf(song)
        val c = controller ?: return

        _state.update { it.copy(currentSong = song, queue = songList, currentQueueIndex = 0) }
        c.setMediaItems(listOf(buildMediaItem(song)), 0, 0L)
        c.prepare()
        c.pause()

        extractLyrics(song.filePath)
    }

    fun playNext(song: Song) {
        val c = controller ?: return

        if (c.mediaItemCount == 0) {
            play(listOf(song), 0)
            return
        }

        val insertIndex = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(insertIndex, buildMediaItem(song))
        songList = songList.toMutableList().also { it.add(insertIndex, song) }
        _state.update { it.copy(queue = songList) }
    }

    // ── Queue ────────────────────────────────────────────────────────────────

    fun jumpToQueueIndex(index: Int) {
        controller?.seekTo(index, 0L)
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in songList.indices) return
        c.removeMediaItem(index)
        songList = songList.toMutableList().also { it.removeAt(index) }
        _state.update { s ->
            s.copy(
                queue = songList,
                currentQueueIndex = if (index < s.currentQueueIndex) s.currentQueueIndex - 1 else s.currentQueueIndex,
            )
        }
    }

    private fun buildMediaItem(song: Song): MediaItem {
        return MediaItem.Builder()
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

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    fun pause() { controller?.pause() }
    fun skipNext() { controller?.seekToNextMediaItem() }

    fun skipPrevious() {
        val c = controller ?: return
        if (c.currentPosition > 3_000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun playShuffle(songs: List<Song>) {
        val shuffled = when (_shuffleStrategy.value) {
            ShuffleStrategy.SMART -> smartShuffle(songs, playCountStore.getAllCounts())
            ShuffleStrategy.RANDOM -> songs.shuffled()
        }
        _shuffleEnabled.value = true
        play(shuffled, 0)
    }

    // Re-shuffles the current queue in place — the playing song keeps playing from where it
    // was (moved to the front of the queue), everything else is re-randomized. This is what the
    // shuffle button on the player screen triggers on every tap, unlike playShuffle() (which
    // starts a brand new playback session from the whole library, used by the song list's menu).
    //
    // Uses removeMediaItems/addMediaItems rather than setMediaItems() — setMediaItems() replaces
    // the whole timeline (including the currently playing item), which caused an audible stutter
    // even when re-supplying the same item at the same position. Removing/adding items other than
    // the active one leaves its playback completely untouched.
    fun reshuffleQueue() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (current !in songList.indices) return

        val currentSong = songList[current]
        val rest = songList.filterIndexed { i, _ -> i != current }
        val shuffledRest = when (_shuffleStrategy.value) {
            ShuffleStrategy.SMART -> smartShuffle(rest, playCountStore.getAllCounts())
            ShuffleStrategy.RANDOM -> rest.shuffled()
        }

        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
        c.addMediaItems(shuffledRest.map(::buildMediaItem))

        val newSongList = listOf(currentSong) + shuffledRest
        songList = newSongList
        _shuffleEnabled.value = true
        _state.update { it.copy(queue = newSongList, currentQueueIndex = 0) }
    }

    fun setShuffleStrategy(strategy: ShuffleStrategy) {
        settingsStore.setShuffleStrategy(strategy)
        _shuffleStrategy.value = strategy
    }

    private fun smartShuffle(songs: List<Song>, counts: Map<Long, Int>): List<Song> {
        return songs
            .groupBy { counts[it.id] ?: 0 }
            .entries
            .sortedBy { it.key }
            .flatMap { it.value.shuffled() }
    }

    // ── Language / translation ────────────────────────────────────────────────

    fun switchToLanguage(langCode: String?) {
        val s = _state.value
        if (langCode == null) {
            _state.update { it.copy(displayLyrics = s.lyrics, activeLanguage = null) }
            return
        }
        val songId = s.currentSong?.id ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { translationStore.load(songId, langCode) }
            if (saved != null) {
                _state.update { it.copy(displayLyrics = saved, activeLanguage = langCode) }
            }
        }
    }

    fun requestTranslationForEditing(targetLanguage: String) {
        val s = _state.value
        if (s.lyrics.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isTranslating = true, translationError = null) }
            try {
                val translated = withContext(Dispatchers.IO) {
                    translateLyrics(s.lyrics, targetLanguage)
                }
                _state.update {
                    it.copy(
                        isTranslating = false,
                        pendingEditorLanguage = targetLanguage,
                        pendingEditorLines = translated,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isTranslating = false, translationError = e.message ?: "Translation failed")
                }
            }
        }
    }

    fun clearPendingEditor() {
        _state.update { it.copy(pendingEditorLanguage = null, pendingEditorLines = emptyList()) }
    }

    fun saveTranslation(langCode: String, lines: List<LyricLine>) {
        val songId = _state.value.currentSong?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            translationStore.save(songId, langCode, lines)
            val langs = translationStore.getAvailableLanguages(songId)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        availableLanguages = langs,
                        displayLyrics = lines,
                        activeLanguage = langCode,
                    )
                }
            }
        }
    }

    // ── Lyrics loading ────────────────────────────────────────────────────────

    fun reloadCurrentLyrics() {
        val path = _state.value.currentSong?.filePath ?: return
        extractLyrics(path)
    }

    private fun extractLyrics(filePath: String) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    lyrics = emptyList(),
                    displayLyrics = emptyList(),
                    currentLyricIndex = -1,
                    activeLanguage = null,
                    availableLanguages = emptyList(),
                    pendingEditorLanguage = null,
                    pendingEditorLines = emptyList(),
                    isTranslating = false,
                    translationError = null,
                )
            }
            if (filePath.isEmpty()) return@launch

            val lyrics = withContext(Dispatchers.IO) { LyricsExtractor.extract(filePath) }
            _state.update { it.copy(lyrics = lyrics, displayLyrics = lyrics) }

            val songId = _state.value.currentSong?.id ?: return@launch
            val langs = withContext(Dispatchers.IO) { translationStore.getAvailableLanguages(songId) }
            _state.update { it.copy(availableLanguages = langs) }
        }
    }

    private fun findCurrentLyricIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
        if (lyrics.isEmpty()) return -1
        // Plain (untimed) lyrics all carry timeMs == 0 — there's nothing to highlight,
        // and without this check every line's timestamp would satisfy "<= positionMs",
        // making it jump straight to (and stay on) the last line as soon as playback starts.
        if (lyrics.all { it.timeMs == 0L }) return -1
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
