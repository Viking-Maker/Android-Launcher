package com.vm.nornir.launcher.usage

import android.content.ComponentName
import com.vm.nornir.launcher.catalog.AppRepository
import com.vm.nornir.launcher.ui.frequentTopN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Companion read seam for the frequent-first grid (ADR-0006 D6, issue #20's code side).
 *
 * Mirrors [com.vm.nornir.launcher.favorites.FavoritesSource]: a hot state the ViewModel
 * combines, backed by persistence — here a **derived view** over [NornirUsageStore]'s
 * records. The set is recomputed reactively whenever the catalog changes (package
 * install/remove) or a launch writes new usage, so no manual refresh triggers exist;
 * "recomputed on package changes and on each recordLaunch" falls out of flow re-emission.
 */
interface FrequentSource {

    /**
     * The current D3 top-N components ([FREQUENT_TOP_N], rank `launchCount DESC` then
     * `lastLaunchTimestamp DESC`, zero-count entries excluded). Seeded empty until the
     * first catalog emission resolves it.
     */
    val frequent: StateFlow<Set<ComponentName>>
}

/**
 * Real [FrequentSource]: folds [NornirUsageStore.records] with the live catalog from
 * [AppRepository.apps] and applies the pure D3 selection ([frequentTopN]).
 *
 * Read-only by construction — it never calls `edit`, so it cannot break the usage
 * store's write discipline (#31 Finding 1: only successful launches write).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageBackedFrequentSource(
    apps: AppRepository,
    usage: NornirUsageStore,
    scope: CoroutineScope,
) : FrequentSource {

    override val frequent: StateFlow<Set<ComponentName>> =
        combine(apps.apps, usage.records()) { list, records ->
            frequentTopN(list, records)
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())
}
