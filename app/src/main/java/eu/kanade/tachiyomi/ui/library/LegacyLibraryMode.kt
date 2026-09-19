package eu.kanade.tachiyomi.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Compatibility types for dormant anime and novel screens kept in the source tree during the
 * staged fork. They are not exposed by the local-first navigation.
 */
@Deprecated("Only retained by dormant anime and novel screens")
enum class LibraryViewMode(val labelRes: StringResource) {
    Manga(MR.strings.manga_singular),
    Anime(MR.strings.label_anime),
    Novels(MR.strings.label_novels),
}

@Deprecated("Only retained by dormant anime and novel screens")
@Composable
internal fun LibraryModeTitleContent(
    title: LibraryToolbarTitle,
    showModeDropdown: Boolean,
    onToggleDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    libraryMode: LibraryViewMode,
    onModeSelected: (LibraryViewMode) -> Unit,
) {
    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
    Row(
        modifier = Modifier.clickable { onToggleDropdown() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (title.numberOfManga != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Pill(
                    text = title.numberOfManga.toString(),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                    fontSize = 14.sp,
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
        )
        DropdownMenu(
            expanded = showModeDropdown,
            onDismissRequest = onDismissDropdown,
        ) {
            LibraryViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(mode.labelRes),
                            fontWeight = if (mode == libraryMode) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = { onModeSelected(mode) },
                )
            }
        }
    }
}
