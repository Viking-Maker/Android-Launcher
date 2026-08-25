package com.vm.nornir.launcher.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vm.nornir.launcher.catalog.AppRepository
import com.vm.nornir.launcher.favorites.FavoritesSource
import com.vm.nornir.launcher.launch.LauncherInvoker
import com.vm.nornir.launcher.model.AppItem
import com.vm.nornir.launcher.usage.NornirUsageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The activity-scoped owner of all home-screen mutable state (ADR-0004 §1, issue #18).
 *
 * The launcher **is** the home activity, so activity scope is the correct lifecycle owner —
 * no navigation graph exists. This class owns the only mutable state (query / filter /
 * focused index) and exposes one immutable [uiState] snapshot; Composables stay pure and the
 * catalog stream + keyboard focus live off-composition.
 *
 * Launch wiring (#8/#9 seams): [LauncherEvent.Launch] delegates the start to
 * [LauncherInvoker] — a pure side effect, no state change — and records the deliberate
 * launch through [NornirUsageStore]. Per issue #18's AC the write lives here (VM-side),
 * which supersedes ADR-0006 D6's original invoker-side placement; reconciling that
 * contract (success-conditionality, KDoc ownership) is tracked in #31.
 *
 * @param repo the catalog source (#6 seam).
 * @param favorites the pin-set source (#9 seam); only its `favorites` StateFlow is read.
 * @param launcher the launch seam (#8) — side-effect only.
 * @param usage the self-tracked usage store; incremented on every launch attempt (see #31
 *   for the success-conditionality follow-up).
 */
class LauncherViewModel(
    private val repo: AppRepository,
    favorites: FavoritesSource,
    private val launcher: LauncherInvoker,
    private val usage: NornirUsageStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow<FilterMode>(FilterMode.All)
    private val _focusedIndex = MutableStateFlow(0)

    val uiState: StateFlow<LauncherUiState> =
        combine(repo.apps, favorites.favorites, _query, _filter, _focusedIndex) {
            apps, favSet, query, filter, focus ->
            val results = filterApps(apps, query, filter, favSet) // pure (Q4-a, Q8-fuzzy)
            LauncherUiState(
                query = query,
                filter = filter,
                results = results,
                availableCategories = visibleCategories(apps), // empty hidden (ADR-0002)
                focusedIndex = focus.coerceIn(0, (results.lastIndex).coerceAtLeast(0)),
                hasFavorites = favSet.isNotEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LauncherUiState())

    /** The unidirectional entry point — the only way the UI mutates state. */
    fun handle(event: LauncherEvent) = when (event) {
        is LauncherEvent.QueryChanged -> _query.value = event.text
        is LauncherEvent.FilterSelected -> _filter.value = event.filter
        is LauncherEvent.MoveFocus -> _focusedIndex.value = step(_focusedIndex.value, event.dir, uiState.value.results.size)
        is LauncherEvent.Launch -> {
            launcher.launch(event.item) // side effect — no state change
            usage.recordLaunch(event.item.component, event.item.user)
        }
    }

    /** The item under the mint highlight, or `null` when the grid is empty. */
    fun focusedItem(): AppItem? =
        uiState.value.results.getOrNull(uiState.value.focusedIndex)
}
