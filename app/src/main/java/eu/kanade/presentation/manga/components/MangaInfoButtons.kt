package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MangaInfoButtons(
    showRecommendsButton: Boolean,
    onRecommendClicked: () -> Unit,
) {
    if (showRecommendsButton) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedButtonWithArrow(
                text = stringResource(SYMR.strings.az_recommends),
                onClick = onRecommendClicked,
            )
        }
    }
}
