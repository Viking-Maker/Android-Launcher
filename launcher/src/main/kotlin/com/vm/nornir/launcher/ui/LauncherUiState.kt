package com.vm.nornir.launcher.ui

import com.vm.nornir.launcher.model.AppItem
import com.vm.nornir.launcher.model.NornirCategory

/**
 * The filter-chip axis (ADR-0004 §2): `All` and `Favorites` are always offered;
 * `Category(c)` is offered only for `c` in the live [LauncherUiState.availableCategories].
 *
 * Per ADR-0002, `ALL`/`FAVORITES` are deliberately **not** [NornirCategory] members —
 * this is a separate axis from the catalog taxonomy.
 */
sealed interface FilterMode {
    data object All : FilterMode
    data object Favorites : FilterMode
    data class Category(val category: NornirCategory) : FilterMode
}

/**
 * The immutable UI snapshot (ADR-0004 §2). Everything the home screen renders derives from
 * this one value; `results` is **derived, never stored** (Q4-a) and `focusedIndex` is clamped
 * to the live [results] range so a keyboard move is always valid and survives rotation.
 *
 * @property query the current search text.
 * @property filter the active filter chip.
 * @property results the filtered catalog slice.
 * @property availableCategories categories with ≥ 1 member — empty ones hidden (ADR-0002).
 * @property focusedIndex highlight position, always inside [results] bounds (0 when empty).
 * @property hasFavorites drives Pin-chip visibility (shown iff any pin exists).
 */
data class LauncherUiState(
    val query: String = "",
    val filter: FilterMode = FilterMode.All,
    val results: List<AppItem> = emptyList(),
    val availableCategories: List<NornirCategory> = emptyList(),
    val focusedIndex: Int = 0,
    val hasFavorites: Boolean = false,
)
