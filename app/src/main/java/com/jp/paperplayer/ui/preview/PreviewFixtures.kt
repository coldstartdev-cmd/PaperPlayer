package com.jp.paperplayer.ui.preview

import android.net.Uri
import com.jp.paperplayer.model.data.LrcTrack
import com.jp.paperplayer.model.data.LyricLine
import com.jp.paperplayer.model.data.MusicBrainzMatch
import com.jp.paperplayer.model.data.Song
import com.jp.paperplayer.model.ui.EditorLine
import com.jp.paperplayer.model.ui.PlayerState
import com.jp.paperplayer.model.ui.TagEditorState
import com.jp.paperplayer.model.ui.TranslationEditorState

// Sample data shared by @Preview functions across the app — never used at runtime.
internal object PreviewFixtures {

    val song1 = Song(1L, "Midnight City", "M83", "Hurry Up, We're Dreaming", Uri.EMPTY, "/music/midnight_city.mp3", 245_000L, null, year = 2011)
    val song2 = Song(2L, "Take On Me", "a-ha", "Hunting High and Low", Uri.EMPTY, "/music/take_on_me.mp3", 225_000L, null, year = 1985)
    val song3 = Song(3L, "Bohemian Rhapsody", "Queen", "A Night at the Opera", Uri.EMPTY, "/music/bohemian_rhapsody.mp3", 355_000L, null, year = 1975)
    val songs = listOf(song1, song2, song3)

    val lyrics = listOf(
        LyricLine(0L, "Waiting in a car"),
        LyricLine(4_000L, "Waiting for a ride in the dark"),
        LyricLine(8_000L, "The night city grows"),
        LyricLine(12_000L, "Look and see her eyes, they glow"),
        LyricLine(16_000L, "She don't care where the hell I go"),
    )

    val playerState = PlayerState(
        isPlaying = true,
        currentSong = song1,
        positionMs = 9_000L,
        durationMs = song1.duration,
        lyrics = lyrics,
        displayLyrics = lyrics,
        currentLyricIndex = 2,
    )

    val playerStateNoLyrics = playerState.copy(
        lyrics = emptyList(),
        displayLyrics = emptyList(),
        currentLyricIndex = -1,
    )

    val playCounts = mapOf(1L to 42, 2L to 17, 3L to 5)

    val editorLines = listOf(
        EditorLine("Waiting in a car", 0L),
        EditorLine("Waiting for a ride in the dark", 4_000L),
        EditorLine("The night city grows", null),
        EditorLine("Look and see her eyes, they glow", null),
    )

    val lrcTracks = listOf(
        LrcTrack(1, "Midnight City", "M83", "Hurry Up, We're Dreaming", 245, "[00:00.00]Waiting in a car", "Waiting in a car"),
        LrcTrack(2, "Midnight City (Remix)", "M83", "Remixes", 250, null, "Waiting in a car"),
    )

    val musicBrainzMatches = listOf(
        MusicBrainzMatch("mb-1", "artist-1", "release-1", "Midnight City", "M83", "Hurry Up, We're Dreaming", "2011", "1"),
        MusicBrainzMatch("mb-2", "artist-1", "release-2", "Midnight City (Live)", "M83", "Live in Paris", "2013", "4"),
    )

    val tagEditorState = TagEditorState(
        title = song1.title,
        artist = song1.artist,
        album = song1.album,
        genre = "Synthpop",
        year = "2011",
        trackNumber = "1",
        language = "en",
        hasSyncedLyrics = true,
        syncedLineCount = lyrics.size,
        isLoading = false,
    )

    val translationEditorState = TranslationEditorState(
        originalLines = lyrics,
        editedLines = listOf(
            "Attendre dans une voiture",
            "Attendre une balade dans le noir",
            "La ville de nuit grandit",
            "Regarde ses yeux briller",
            "Elle se fiche de savoir où je vais",
        ),
        targetLanguage = "fr",
        songId = song1.id,
        ready = true,
    )
}
