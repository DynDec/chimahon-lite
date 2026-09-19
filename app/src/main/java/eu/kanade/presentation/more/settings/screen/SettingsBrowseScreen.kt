package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Settings for the local manga directory presented by the Browse tab. */
object SettingsBrowseScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsBrowseScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val sourcePreferences = Injekt.get<SourcePreferences>()

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.browse),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(MR.strings.pref_manga_directory_info),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.allowLocalSourceHiddenFolders(),
                        title = stringResource(SYMR.strings.pref_local_source_hidden_folders),
                        subtitle = stringResource(SYMR.strings.pref_local_source_hidden_folders_summery),
                    ),
                ),
            ),
        )
    }
}
