package eu.kanade.presentation.more.settings.screen

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import chimahon.dictionary.RecommendedDictionaryDownloader
import chimahon.dictionary.RecommendedDictionaryKind
import chimahon.dictionary.RecommendedDictionaryStage
import chimahon.dictionary.recommendedDictionariesForLanguage
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit

@Composable
internal fun recommendedDictionariesPreference(
    busy: MutableStateFlow<Boolean>,
    progress: MutableStateFlow<String?>,
    onImported: () -> Unit,
    importArchive: suspend (File, chimahon.anki.AnkiProfile) -> Pair<String, Boolean>,
): Preference.PreferenceItem.TextPreference {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Injekt.get<DictionaryPreferences>() }
    val rawProfiles by prefs.rawProfiles().collectAsState()
    val activeId by prefs.rawActiveProfileId().collectAsState()
    val profile = remember(rawProfiles, activeId) { prefs.profileStore.getActiveProfile() }
    val dictionaries = remember(profile.languageCode) { recommendedDictionariesForLanguage(profile.languageCode) }
    val isBusy by busy.collectAsState()
    var showCatalog by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    val downloader = remember {
        RecommendedDictionaryDownloader(
            Injekt.get<NetworkHelper>().client.newBuilder()
                .callTimeout(0, TimeUnit.SECONDS)
                .build(),
        )
    }

    if (showCatalog) {
        AlertDialog(
            onDismissRequest = { showCatalog = false },
            title = { Text(stringResource(KMR.strings.dictionary_download_recommended)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(KMR.strings.dictionary_download_prompt))
                    if (dictionaries.isEmpty()) {
                        Text(stringResource(KMR.strings.dictionary_download_no_recommended))
                    }
                    dictionaries.forEach { dictionary ->
                        val kind = stringResource(
                            when (dictionary.kind) {
                                RecommendedDictionaryKind.Term -> KMR.strings.dictionary_recommended_term
                                RecommendedDictionaryKind.Names -> KMR.strings.dictionary_recommended_names
                                RecommendedDictionaryKind.Frequency -> KMR.strings.dictionary_recommended_frequency
                                RecommendedDictionaryKind.Encyclopedia -> KMR.strings.dictionary_recommended_encyclopedia
                            },
                        )
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !isBusy) {
                                    if (busy.value) return@clickable
                                    showCatalog = false
                                    busy.value = true
                                    progress.value = context.stringResource(KMR.strings.dictionary_download_fetching, dictionary.name)
                                    // Keep the target profile stable for the entire download/import.
                                    val targetProfile = prefs.profileStore.getActiveProfile()
                                    scope.launch {
                                        try {
                                            val result = downloader.downloadAndImport(
                                                dictionary = dictionary,
                                                cacheDir = context.cacheDir,
                                                onProgress = { stage ->
                                                    progress.value = context.stringResource(
                                                        when (stage) {
                                                            RecommendedDictionaryStage.Fetching -> KMR.strings.dictionary_download_fetching
                                                            RecommendedDictionaryStage.Downloading -> KMR.strings.dictionary_download_downloading
                                                            RecommendedDictionaryStage.Importing -> KMR.strings.dictionary_download_importing
                                                        },
                                                        dictionary.name,
                                                    )
                                                },
                                                importArchive = { importArchive(it, targetProfile) },
                                            )
                                            report = if (result.second) {
                                                context.stringResource(KMR.strings.dictionary_download_success, dictionary.name)
                                            } else {
                                                context.stringResource(KMR.strings.dictionary_download_failed, dictionary.name)
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.e("DictionaryDownload", "Failed to import ${dictionary.name}", e)
                                            report = context.stringResource(KMR.strings.dictionary_download_failed, dictionary.name)
                                        } finally {
                                            progress.value = null
                                            busy.value = false
                                            onImported()
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(dictionary.name, style = MaterialTheme.typography.bodyMedium)
                            Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCatalog = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
    report?.let { message ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text(stringResource(KMR.strings.dictionary_download_recommended)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { report = null }) { Text(stringResource(MR.strings.action_ok)) }
            },
        )
    }
    return Preference.PreferenceItem.TextPreference(
        title = stringResource(KMR.strings.dictionary_download_recommended),
        subtitle = stringResource(KMR.strings.dictionary_download_summary),
        enabled = !isBusy,
        onClick = { showCatalog = true },
    )
}
