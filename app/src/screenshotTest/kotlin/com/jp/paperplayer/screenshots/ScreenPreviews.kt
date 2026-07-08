package com.jp.paperplayer.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.jp.paperplayer.ui.lyricseditor.SyncingPhasePreview
import com.jp.paperplayer.ui.party.PartyHostPreview
import com.jp.paperplayer.ui.player.FullscreenLyricsLayoutPreview
import com.jp.paperplayer.ui.player.PortraitPlayerLayoutPreview
import com.jp.paperplayer.ui.playstats.PlayStatsContentPreview
import com.jp.paperplayer.ui.queue.QueueContentPreview
import com.jp.paperplayer.ui.settings.SettingsContentPreview
import com.jp.paperplayer.ui.songlist.SongListContentPreview
import com.jp.paperplayer.ui.tageditor.TagEditorContentPreview
import com.jp.paperplayer.ui.translationeditor.TranslationEditorContentPreview

// Renders each screen's existing @Preview at a uniform phone size.
// `./gradlew updateDebugScreenshotTest` writes the PNGs used in the README.

private const val WIDTH = 360
private const val HEIGHT = 740

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun songList() = SongListContentPreview()

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun player() = PortraitPlayerLayoutPreview()

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun fullscreenLyrics() = FullscreenLyricsLayoutPreview()

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun lyricsEditor() = SyncingPhasePreview()

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun translationEditor() = TranslationEditorContentPreview()

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun tagEditor() = TagEditorContentPreview()

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun queue() = QueueContentPreview()

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun playStats() = PlayStatsContentPreview()

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun settings() = SettingsContentPreview()

@PreviewTest
@Preview(showBackground = true, widthDp = WIDTH, heightDp = HEIGHT)
@Composable
fun party() = PartyHostPreview()
