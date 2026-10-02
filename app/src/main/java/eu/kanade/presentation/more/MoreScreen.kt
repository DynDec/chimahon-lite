package eu.kanade.presentation.more

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import eu.kanade.domain.ui.model.NavTabLayout
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.R
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MoreScreen(
    incognitoMode: Boolean,
    onIncognitoModeChange: (Boolean) -> Unit,
    // SY -->
    moreTabKeys: List<String>,
    // SY <--
    onClickOcrQueue: () -> Unit,
    onClickCategories: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickSettings: () -> Unit,
    onClickAbout: () -> Unit,
    onClickHistory: () -> Unit,
    onClickLibrary: () -> Unit,
    onClickBrowse: () -> Unit,
    onClickDictionary: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    Scaffold { contentPadding ->
        ScrollbarLazyColumn(
            // KMK: use contentPadding as preferable padding for ScrollbarLazyColumn when not using stickyHeader
            contentPadding = contentPadding,
        ) {
            item {
                LogoHeader()
            }
            item {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.pref_incognito_mode),
                    subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
                    // KMK -->
                    icon = rememberAnimatedVectorPainter(
                        AnimatedImageVector.animatedVectorResource(R.drawable.anim_incognito),
                        incognitoMode,
                    ),
                    // KMK <--
                    checked = incognitoMode,
                    onCheckedChanged = onIncognitoModeChange,
                )
            }
            item { HorizontalDivider() }

            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.ocr_queue),
                    icon = Icons.Outlined.DocumentScanner,
                    onPreferenceClick = onClickOcrQueue,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.categories),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    onPreferenceClick = onClickCategories,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_data_storage),
                    icon = Icons.Outlined.Storage,
                    onPreferenceClick = onClickDataAndStorage,
                )
            }

            // SY -->
            moreTabKeys.forEach { key ->
                item {
                    when (key) {
                        NavTabLayout.KEY_LIBRARY -> TextPreferenceWidget(
                            title = stringResource(MR.strings.label_library),
                            icon = Icons.Outlined.CollectionsBookmark,
                            onPreferenceClick = onClickLibrary,
                        )
                        NavTabLayout.KEY_HISTORY -> TextPreferenceWidget(
                            title = stringResource(MR.strings.label_recent_manga),
                            icon = Icons.Outlined.History,
                            onPreferenceClick = onClickHistory,
                        )
                        NavTabLayout.KEY_BROWSE -> TextPreferenceWidget(
                            title = stringResource(MR.strings.browse),
                            icon = Icons.Outlined.Public,
                            onPreferenceClick = onClickBrowse,
                        )
                        NavTabLayout.KEY_DICTIONARY -> TextPreferenceWidget(
                            title = stringResource(MR.strings.label_dictionary),
                            icon = Icons.Outlined.Search,
                            onPreferenceClick = onClickDictionary,
                        )
                    }
                }
            }
            // SY <--

            item { HorizontalDivider() }

            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_settings),
                    icon = Icons.Outlined.Settings,
                    onPreferenceClick = onClickSettings,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.pref_category_about),
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onClickAbout,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_help),
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    onPreferenceClick = { uriHandler.openUri(Constants.URL_HELP) },
                )
            }
        }
    }
}
