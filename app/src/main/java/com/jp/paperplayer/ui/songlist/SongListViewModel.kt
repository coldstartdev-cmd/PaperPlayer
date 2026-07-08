package com.jp.paperplayer.ui.songlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jp.paperplayer.data.SettingsStore
import com.jp.paperplayer.model.data.MusicFolder
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.scanner.MusicScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SongListViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = MusicScanner(app)
    private val settingsStore = SettingsStore(app)

    // Full scan result; the visible library and the folder list both derive from it.
    private val allSongs = MutableStateFlow<List<Song>>(emptyList())
    private val excludedFolders = MutableStateFlow(settingsStore.getExcludedFolders())

    val songs: StateFlow<List<Song>> =
        combine(allSongs, excludedFolders) { songs, excluded ->
            if (excluded.isEmpty()) songs
            else songs.filterNot { it.folderPath() in excluded }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val musicFolders: StateFlow<List<MusicFolder>> =
        combine(allSongs, excludedFolders) { songs, excluded ->
            songs
                .filter { it.filePath.isNotBlank() }
                .groupBy { it.folderPath() }
                .filterKeys { it.isNotBlank() }
                .map { (path, folderSongs) ->
                    MusicFolder(
                        path = path,
                        name = path.substringAfterLast('/').ifBlank { path },
                        songCount = folderSongs.size,
                        excluded = path in excluded,
                    )
                }
                .sortedBy { it.path.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun loadSongs() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            allSongs.value = scanner.scan()
            _isLoading.value = false
        }
    }

    fun setFolderExcluded(path: String, excluded: Boolean) {
        val next = if (excluded) excludedFolders.value + path else excludedFolders.value - path
        settingsStore.setExcludedFolders(next)
        excludedFolders.value = next
    }

    private fun Song.folderPath(): String = filePath.substringBeforeLast('/', missingDelimiterValue = "")
}
