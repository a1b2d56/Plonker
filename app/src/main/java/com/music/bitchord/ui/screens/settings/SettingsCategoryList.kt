package com.music.bitchord.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** The main page's list of categories: a glyph and a name each, and a chevron into it. */
@Composable
fun SettingsCategoryList(onOpen: (SettingsCategory) -> Unit) {
    SettingsGroup {
        SettingsCategory.entries.forEachIndexed { index, category ->
            if (index > 0) RowDivider()
            SettingsRow(
                icon = category.icon,
                title = stringResource(category.title),
                onClick = { onOpen(category) },
            )
        }
    }
}
