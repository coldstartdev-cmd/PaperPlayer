package com.jp.paperplayer.ui.preview

import android.net.Uri
import com.jp.paperplayer.model.data.DiscoveredParty
import com.jp.paperplayer.model.data.LrcTrack
import com.jp.paperplayer.model.data.LyricLine
import com.jp.paperplayer.model.data.MusicFolder
import com.jp.paperplayer.model.data.PartyMember
import com.jp.paperplayer.model.data.PartyMemberStatus
import com.jp.paperplayer.model.data.PartyMemberSyncStats
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

    val discoveredParties = listOf(
        DiscoveredParty("Pixel 7's party", "192.168.1.23", 40331),
        DiscoveredParty("Living room", "192.168.1.40", 40118),
    )

    val partyMembers = listOf(
        PartyMember("m1", "Galaxy S23", PartyMemberStatus.READY),
        PartyMember("m2", "Redmi Note 12", PartyMemberStatus.SYNCING),
        PartyMember(
            "m3", "Pixel 9 Pro XL", PartyMemberStatus.PLAYING,
            stats = PartyMemberSyncStats(
                deviceAtMs = 100_000L, hostAtMs = 100_000L - 12L,
                expectedPositionMs = 63_400L, actualPositionMs = 63_403L, rttMs = 18L, playbackSpeed = 1f,
                seekCorrections = 1, nudgeCorrections = 6, latencyTrimMs = -70L,
                driftHistory = List(20) { (it % 5) - 2L },
            ),
        ),
        PartyMember(
            "m4", "S8 tablet", PartyMemberStatus.PLAYING,
            stats = PartyMemberSyncStats(
                deviceAtMs = 100_000L, hostAtMs = 100_000L + 8L,
                expectedPositionMs = 63_400L, actualPositionMs = 63_434L, rttMs = 22L, playbackSpeed = 0.99f,
                seekCorrections = 0, nudgeCorrections = 14, latencyTrimMs = -70L,
                driftHistory = List(20) { 30L + (it % 5) },
            ),
        ),
        PartyMember(
            "m5", "Old tablet", PartyMemberStatus.PLAYING,
            stats = PartyMemberSyncStats(
                deviceAtMs = 100_000L, hostAtMs = 100_000L + 4L,
                expectedPositionMs = 63_400L, actualPositionMs = 63_457L, rttMs = 40L, playbackSpeed = 1.025f,
                seekCorrections = 3, nudgeCorrections = 9, latencyTrimMs = 0L,
                driftHistory = List(20) { i -> if (i < 10) 5L + i else 30L + (i - 10) * 3L },
                lastSampleGapMs = 1_800L, networkGapCount = 2,
                hostVerifiedExpectedPositionMs = 63_400L - 25L, hostResyncCount = 1,
            ),
        ),
    )

    val musicFolders = listOf(
        MusicFolder("/storage/emulated/0/Music", "Music", 24, excluded = false),
        MusicFolder("/storage/emulated/0/Download", "Download", 3, excluded = false),
        MusicFolder("/storage/emulated/0/WhatsApp/Media/WhatsApp Audio", "WhatsApp Audio", 11, excluded = true),
    )

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
