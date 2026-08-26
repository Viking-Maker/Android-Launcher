package com.vm.nornir.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vm.nornir.launcher.icon.IconLoader

/**
 * The floating, glassmorphic launcher card over the wallpaper (`launcher-UI.md` §1,
 * issue #19). One centered column — search bar, category filter bar, scrollable app
 * list, footer — clipped to the spec's 26dp radius on a translucent window fill.
 *
 * State flows one way (ADR-0004): [state] is rendered, every interaction is reported to
 * [onEvent]; the composables stay pure and previewable. The keyboard contract lives at
 * this level because it spans children: typing lands in [SearchBar] (auto-focused once),
 * while Up/Down/Enter are intercepted by [onPreviewKeyEvent] so they work wherever focus
 * sits. Enter launches [state.results]' focused row; Up/Down move the mint highlight.
 *
 * @param state the immutable UI snapshot from `LauncherViewModel.uiState`.
 * @param onEvent the unidirectional event sink (typically `vm::handle`).
 * @param iconLoader the icon seam (#16) handed down to app rows.
 * @param modifier applied to the outer wallpaper-filling host.
 */
@Composable
fun LauncherScreen(
    state: LauncherUiState,
    onEvent: (LauncherEvent) -> Unit,
    iconLoader: IconLoader,
    densityDpi: Int = 0,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNornirColors.current

    // Auto-focus the search field exactly once per composition (spec §3: "start typing
    // immediately"). stateNotNeeded=true means a fresh show is a fresh composition.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.window.copy(alpha = GlassAlpha))
            .padding(24.dp)
            .testTag(TestTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.window.copy(alpha = CardAlpha), RoundedCornerShape(CardRadiusDp))
                // Preview-phase key routing at the card level: Up/Down/Enter work no matter
                // which child holds focus (search field, chips, rows) — spec §7.
                .onPreviewKeyEvent { event -> handleKeyEvent(event, state, onEvent) }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SearchBar(
                query = state.query,
                onQueryChanged = { onEvent(LauncherEvent.QueryChanged(it)) },
                focusRequester = searchFocus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
            CategoryBar(
                availableCategories = state.availableCategories,
                filter = state.filter,
                hasFavorites = state.hasFavorites,
                onFilterSelected = { onEvent(LauncherEvent.FilterSelected(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            AppList(
                results = state.results,
                focusedIndex = state.focusedIndex,
                iconLoader = iconLoader,
                densityDpi = densityDpi,
                onLaunch = { onEvent(LauncherEvent.Launch(it)) },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Footer(
                resultCount = state.results.size,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** Spec §1: heavily rounded modern squircle; midpoint of the 24–28dp range. */
internal const val CardRadiusDp = 26

/**
 * Keyboard routing shared by the whole screen (spec §7):
 *  - `Down`/`Up` (and DPAD equivalents) step the mint highlight;
 *  - `Enter` launches the focused row through [LauncherEvent.Launch].
 * Unhandled keys return false and keep propagating (text input stays in the field).
 */
internal fun handleKeyEvent(event: KeyEvent, state: LauncherUiState, onEvent: (LauncherEvent) -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionDown -> { onEvent(LauncherEvent.MoveFocus(FocusDir.DOWN)); true }
        Key.DirectionUp -> { onEvent(LauncherEvent.MoveFocus(FocusDir.UP)); true }
        Key.Enter, Key.NumPadEnter -> {
            state.results.getOrNull(state.focusedIndex)?.let { onEvent(LauncherEvent.Launch(it)) }
            true
        }
        else -> false
    }
}

internal const val GlassAlpha = 0.92f // spec §1: 90–95% opacity acrylic fill
internal const val CardAlpha = 0.95f
