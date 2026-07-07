package com.jp.paperplayer.ui.songlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.scanner.MusicScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SongListViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = MusicScanner(app)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun loadSongs() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _songs.value = scanner.scan()
            _isLoading.value = false
        }
    }
}
