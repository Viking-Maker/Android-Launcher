package com.vm.nornir.launcher.ui

import android.content.ComponentName
import android.os.Process
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.vm.nornir.launcher.icon.FakeIconLoader
import com.vm.nornir.launcher.model.AppItem
import com.vm.nornir.launcher.model.NornirCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the floating launcher card (issue #19, ADR-0004 §3/§7).
 *
 * The screen is driven exactly as production will: an immutable [LauncherUiState] in,
 * [LauncherEvent]s out through a recording sink. Per #11's testing strategy these tests
 * read **external behavior** — rendered text/semantics and the event sequence — never
 * composables' internals. Keyboard tests drive real composed KeyEvents through the screen's
 * router ([handleKeyEvent]) directly: Robolectric's window-level key injection is not
 * deterministic, so composition-level key dispatch is verified on-device (#20), while the
 * Up/Down/Enter routing contract is pinned here at unit level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // ---- fixtures -------------------------------------------------------------

    private fun app(id: String, label: String, platformCategory: Int? = null) =
        AppItem(ComponentName("com.example.$id", "com.example.$id.MainActivity"), Process.myUserHandle(), label, platformCategory)

    /** Recording event sink — the observable side-effect surface for assertions. */
    private class EventSink {
        val events = mutableListOf<LauncherEvent>()
        fun onEvent(event: LauncherEvent) { events += event }
    }

    private fun setState(state: LauncherUiState, sink: EventSink, iconLoader: FakeIconLoader = FakeIconLoader()) {
        compose.setContent {
            com.vm.nornir.launcher.NornirTheme {
                LauncherScreen(state = state, onEvent = sink::onEvent, iconLoader = iconLoader)
            }
        }
    }


    /**
     * A native KeyDown mapped from [key] through Compose's own keycode space — mirrors
     * what the framework would deliver. Driven straight through the screen's router
     * ([handleKeyEvent]); Robolectric's window-level key injection is not deterministic,
     * and the routing contract under test lives in that function.
     */
    private fun keyDown(key: Key): KeyEvent = when (key) {
        Key.DirectionDown -> nativeKeyDown(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        Key.DirectionUp -> nativeKeyDown(android.view.KeyEvent.KEYCODE_DPAD_UP)
        Key.Enter -> nativeKeyDown(android.view.KeyEvent.KEYCODE_ENTER)
        else -> error("unsupported test key $key")
    }

    private fun nativeKeyDown(keycode: Int): KeyEvent =
        KeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, keycode, 0))

    private fun pressKey(key: Key, state: LauncherUiState, sink: EventSink) {
        handleKeyEvent(keyDown(key), state, sink::onEvent)
    }

    // ---- rendering --------------------------------------------------------------

    @Test
    fun rendersSearchBar_filterChips_rows_andFooter() {
        val steam = app("steam", "Steam", 0)
        val spotify = app("spotify", "Spotify", 1)
        val vscode = app("vscode", "VS Code", 7)
        val sink = EventSink()
        setState(
            LauncherUiState(
                results = listOf(steam, spotify, vscode),
                availableCategories = listOf(NornirCategory.GAME, NornirCategory.MULTIMEDIA, NornirCategory.PRODUCTIVITY),
            ),
            sink,
        )
        compose.onNodeWithTag(TestTags.SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.SEARCH).assertExists()
        compose.onNodeWithText("All").assertExists()
        compose.onNodeWithText("Games").assertExists()      // category chip from displayName
        compose.onNodeWithContentDescription("Steam, Games, focused").assertExists() // focused row 0
        compose.onNodeWithContentDescription("Spotify, Multimedia").assertExists()
        compose.onNodeWithText("3 results").assertExists()   // footer count
    }

    @Test
    fun emptyCategoriesAreHidden_andFilterChipsNeverHidden() {
        val mail = app("mail", "Mail")
        val sink = EventSink()
        setState(LauncherUiState(results = listOf(mail), availableCategories = listOf(NornirCategory.OTHER)), sink)
        compose.onNodeWithText("Favorites").assertExists()       // never hidden — ADR-0002 §4
        compose.onNodeWithText("All").assertExists()
        compose.onNodeWithText("Games").assertDoesNotExist()     // empty category hidden upstream
        compose.onNodeWithContentDescription("Mail, Other, focused").assertExists() // row 0 is focused
        compose.onNodeWithText("Other").assertExists()
        compose.onNodeWithText("1 results").assertExists()
    }

    @Test
    fun footerShowsZero_whenResultsEmpty() {
        val sink = EventSink()
        setState(LauncherUiState(query = "zzz", results = emptyList(), availableCategories = emptyList()), sink)
        compose.onNodeWithText("0 results").assertExists()
    }

    // ---- search bar ---------------------------------------------------------------

    @Test
    fun searchBarIsAutoFocused_onFirstComposition() {
        val sink = EventSink()
        setState(LauncherUiState(results = listOf(app("mail", "Mail"))), sink)
        compose.onNodeWithTag(TestTags.SEARCH).performClick().assertIsFocused()
    }

    @Test
    fun typingReportsQueryChanged_instantly() {
        val sink = EventSink()
        setState(LauncherUiState(results = listOf(app("mail", "Mail"))), sink)
        compose.onNodeWithTag(TestTags.SEARCH).performTextInput("ma")
        compose.runOnIdle { sink.events.last() }.let { last ->
            check(last is LauncherEvent.QueryChanged && last.text == "ma") { "unexpected $last" }
        }
    }

    // ---- keyboard navigation --------------------------------------------------------

    @Test
    fun downArrow_movesFocusHighlight_toSecondRow_viaEvent() {
        val steam = app("steam", "Steam", 0)
        val spotify = app("spotify", "Spotify", 1)
        val sink = EventSink()
        val state = LauncherUiState(results = listOf(steam, spotify))
        setState(state, sink)
        pressKey(Key.DirectionDown, state, sink)
        compose.runOnIdle {
            check(sink.events.last() == LauncherEvent.MoveFocus(FocusDir.DOWN)) { "expected DOWN, got ${sink.events}" }
        }
    }

    @Test
    fun upArrow_reportsMoveFocusUp() {
        val steam = app("steam", "Steam", 0)
        val spotify = app("spotify", "Spotify", 1)
        val sink = EventSink()
        val state = LauncherUiState(results = listOf(steam, spotify), focusedIndex = 1)
        setState(state, sink)
        pressKey(Key.DirectionUp, state, sink)
        compose.runOnIdle {
            check(sink.events.last() == LauncherEvent.MoveFocus(FocusDir.UP)) { "expected UP, got ${sink.events}" }
        }
    }

    @Test
    fun focusedRow_rendersMintSemantics_focusedDescription() {
        val steam = app("steam", "Steam", 0)
        val spotify = app("spotify", "Spotify", 1)
        val sink = EventSink()
        setState(LauncherUiState(results = listOf(steam, spotify), focusedIndex = 1), sink)
        // The focused card carries ", focused" in its semantics description.
        compose.onNodeWithContentDescription("Spotify, Multimedia, focused").assertExists()
    }

    @Test
    fun unfocusedRow_hasNoFocusedSuffix_inDescription() {
        val steam = app("steam", "Steam", 0)
        val spotify = app("spotify", "Spotify", 1)
        val sink = EventSink()
        setState(LauncherUiState(results = listOf(steam, spotify), focusedIndex = 0), sink)
        compose.onNodeWithContentDescription("Steam, Games, focused").assertExists()
        compose.onNodeWithContentDescription("Spotify, Multimedia, focused").assertDoesNotExist()
    }

    // ---- enter launches -----------------------------------------------------------------

    @Test
    fun enterKey_launchesTheFocusedRow_throughEventSink() {
        val steam = app("steam", "Steam", 0)
        val spotify = app("spotify", "Spotify", 1)
        val sink = EventSink()
        val state = LauncherUiState(results = listOf(steam, spotify), focusedIndex = 1)
        setState(state, sink)
        pressKey(Key.Enter, state, sink)
        compose.runOnIdle {
            val launch = sink.events.filterIsInstance<LauncherEvent.Launch>().single()
            check(launch.item == spotify) { "expected Spotify launched, got ${launch.item}" }
        }
    }

    @Test
    fun clickingARow_launchesIt() {
        val steam = app("steam", "Steam", 0)
        val spotify = app("spotify", "Spotify", 1)
        val sink = EventSink()
        setState(LauncherUiState(results = listOf(steam, spotify), focusedIndex = 0), sink)
        compose.onNodeWithContentDescription("Spotify, Multimedia").performClick()
        compose.runOnIdle {
            val launch = sink.events.filterIsInstance<LauncherEvent.Launch>().single()
            check(launch.item == spotify) { "expected Spotify launched, got ${launch.item}" }
        }
    }

    // ---- filter chips ---------------------------------------------------------------------

    @Test
    fun selectingACategoryChip_reportsFilterSelected() {
        val sink = EventSink()
        setState(
            LauncherUiState(
                results = listOf(app("steam", "Steam", 0)),
                availableCategories = listOf(NornirCategory.GAME),
            ),
            sink,
        )
        compose.onNodeWithText("Games").performClick()
        compose.runOnIdle {
            check(sink.events.last() == LauncherEvent.FilterSelected(FilterMode.Category(NornirCategory.GAME))) {
                "got ${sink.events}"
            }
        }
    }
}
