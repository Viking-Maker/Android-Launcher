package com.vm.nornir.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vm.nornir.launcher.icon.IconLoader
import com.vm.nornir.launcher.model.AppItem

/**
 * The scrollable app list (`launcher-UI.md` §5).
 *
 * A `LazyColumn` with the spec's 8dp gap; each row is an [AppCard], mint-filled when it
 * sits at [focusedIndex]. Row interaction: click launches that row — the keyboard path
 * (Up/Down/Enter) is intercepted screen-wide in [LauncherScreen], so mouse and keyboard
 * both converge on [LauncherEvent.Launch] / [LauncherEvent.MoveFocus].
 *
 * Pure: rows render from [results]; every action reports upstream, never applied locally.
 */
@Composable
fun AppList(
    results: List<AppItem>,
    focusedIndex: Int,
    iconLoader: IconLoader,
    onLaunch: (AppItem) -> Unit,
    modifier: Modifier = Modifier,
    densityDpi: Int = 0,
) {
    LazyColumn(
        modifier = modifier.testTag(TestTags.APP_LIST),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(
            results,
            key = { _, item -> item.component.flattenToString() + "#" + item.user },
        ) { index, item ->
            AppCard(
                item = item,
                isFocused = index == focusedIndex,
                iconLoader = iconLoader,
                densityDpi = densityDpi,
                onClick = { onLaunch(item) },
            )
        }
    }
}
