package com.jp.paperplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jp.paperplayer.ui.theme.PaperPlayerTheme

// ── Language picker dialogs ───────────────────────────────────────────────────

@Composable
fun LangSwitcherDialog(
    activeLanguage: String?,
    availableLanguages: List<String>,
    onDismiss: () -> Unit,
    onPickOriginal: () -> Unit,
    onPickSaved: (String) -> Unit,
    onAddNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics language") },
        text = {
            LazyColumn {
                item {
                    Text(
                        text = if (activeLanguage == null) "✓  Original" else "Original",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (activeLanguage == null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onPickOriginal() }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
                items(availableLanguages) { code ->
                    val isActive = code == activeLanguage
                    Text(
                        text = if (isActive) "✓  ${LANG_DISPLAY[code] ?: code.uppercase()}"
                               else LANG_DISPLAY[code] ?: code.uppercase(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onPickSaved(code) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
                item {
                    Text(
                        text = "+ Add translation...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onAddNew() }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun LangSwitcherDialogPreview() {
    PaperPlayerTheme {
        LangSwitcherDialog(
            activeLanguage = "fr",
            availableLanguages = listOf("fr", "es"),
            onDismiss = {},
            onPickOriginal = {},
            onPickSaved = {},
            onAddNew = {},
        )
    }
}

private val LANG_DISPLAY = mapOf(
    "ar" to "Arabic", "bg" to "Bulgarian", "bn" to "Bengali", "cs" to "Czech",
    "da" to "Danish", "de" to "German", "el" to "Greek", "en" to "English",
    "es" to "Spanish", "fa" to "Persian", "fi" to "Finnish", "fr" to "French",
    "gu" to "Gujarati", "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian",
    "hu" to "Hungarian", "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese",
    "kn" to "Kannada", "ko" to "Korean", "lt" to "Lithuanian", "lv" to "Latvian",
    "ml" to "Malayalam", "mr" to "Marathi", "ms" to "Malay", "nl" to "Dutch",
    "no" to "Norwegian", "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian",
    "ru" to "Russian", "sk" to "Slovak", "sv" to "Swedish", "sw" to "Swahili",
    "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tl" to "Tagalog",
    "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu", "vi" to "Vietnamese",
    "zh" to "Chinese",
)

private val SUPPORTED_LANGUAGES = listOf(
    "ar" to "Arabic",
    "bg" to "Bulgarian",
    "bn" to "Bengali",
    "cs" to "Czech",
    "da" to "Danish",
    "de" to "German",
    "el" to "Greek",
    "en" to "English",
    "es" to "Spanish",
    "fa" to "Persian",
    "fi" to "Finnish",
    "fr" to "French",
    "gu" to "Gujarati",
    "he" to "Hebrew",
    "hi" to "Hindi",
    "hr" to "Croatian",
    "hu" to "Hungarian",
    "id" to "Indonesian",
    "it" to "Italian",
    "ja" to "Japanese",
    "kn" to "Kannada",
    "ko" to "Korean",
    "lt" to "Lithuanian",
    "lv" to "Latvian",
    "ml" to "Malayalam",
    "mr" to "Marathi",
    "ms" to "Malay",
    "nl" to "Dutch",
    "no" to "Norwegian",
    "pl" to "Polish",
    "pt" to "Portuguese",
    "ro" to "Romanian",
    "ru" to "Russian",
    "sk" to "Slovak",
    "sv" to "Swedish",
    "sw" to "Swahili",
    "ta" to "Tamil",
    "te" to "Telugu",
    "th" to "Thai",
    "tl" to "Tagalog",
    "tr" to "Turkish",
    "uk" to "Ukrainian",
    "ur" to "Urdu",
    "vi" to "Vietnamese",
    "zh" to "Chinese",
)

@Composable
fun LanguagePickerDialog(
    onDismiss: () -> Unit,
    onLanguagePicked: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Translate to") },
        text = {
            LazyColumn {
                items(SUPPORTED_LANGUAGES) { (code, name) ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onLanguagePicked(code) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun LanguagePickerDialogPreview() {
    PaperPlayerTheme {
        LanguagePickerDialog(onDismiss = {}, onLanguagePicked = {})
    }
}
