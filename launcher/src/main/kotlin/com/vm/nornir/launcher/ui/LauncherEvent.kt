package com.vm.nornir.launcher.ui

import com.vm.nornir.launcher.model.AppItem

/** Keyboard focus movement axis for [LauncherEvent.MoveFocus] (ADR-0004 §3). */
enum class FocusDir { UP, DOWN }

/**
 * The unidirectional event set (ADR-0004 §3). The UI fires; the ViewModel is the only
 * mutator. [Launch] is a pure side effect — it never changes state.
 */
sealed interface LauncherEvent {
    data class QueryChanged(val text: String) : LauncherEvent
    data class FilterSelected(val filter: FilterMode) : LauncherEvent

    /** Move the mint highlight one row [dir]; clamped to the live results range in uiState. */
    data class MoveFocus(val dir: FocusDir) : LauncherEvent

    /** Launch [item] through the #8 seam and record usage — no state change. */
    data class Launch(val item: AppItem) : LauncherEvent
}
