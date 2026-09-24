package eu.kanade.presentation.more.settings.screen.data

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.cache.CoverCacheCleanup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun coverCleanupPreference(onComplete: () -> Unit): Preference.PreferenceItem.TextPreference {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    fun dismiss() {
        job?.cancel()
        showDialog = false
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(stringResource(MR.strings.pref_cleanup_covers)) },
            text = {
                if (running) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(MR.strings.cover_cleanup_running))
                    }
                } else {
                    Text(message)
                }
            },
            confirmButton = {
                TextButton(onClick = ::dismiss) {
                    Text(stringResource(if (running) MR.strings.action_cancel else MR.strings.action_ok))
                }
            },
        )
    }

    return Preference.PreferenceItem.TextPreference(
        title = stringResource(MR.strings.pref_cleanup_covers),
        subtitle = stringResource(MR.strings.pref_cleanup_covers_summary),
        enabled = !running,
        onClick = {
            if (!running) {
                running = true
                showDialog = true
                job = scope.launch {
                    try {
                        val result = CoverCacheCleanup(context.applicationContext).clean()
                        message = context.stringResource(
                            MR.strings.cover_cleanup_result,
                            result.removed,
                            result.shared,
                            Formatter.formatFileSize(context, result.freedBytes),
                        )
                        if (result.skipped > 0) {
                            message += "\n\n" + context.stringResource(MR.strings.cover_cleanup_skipped, result.skipped)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Cover cleanup failed" }
                        message = context.stringResource(MR.strings.cover_cleanup_error)
                    } finally {
                        running = false
                        onComplete()
                    }
                }
            }
        },
    )
}
