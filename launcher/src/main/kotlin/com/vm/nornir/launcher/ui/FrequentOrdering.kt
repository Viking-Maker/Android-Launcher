package com.vm.nornir.launcher.ui

import android.content.ComponentName
import com.vm.nornir.launcher.model.AppItem
import com.vm.nornir.launcher.usage.UsageRecord

/**
 * Pure frequent-first ordering for the home grid (ADR-0006 D3/D5, issue #20's code side).
 *
 * Like [filterApps], everything here is a total function over plain values — no flows, no
 * store access — so the ranking is unit-testable in isolation; the
 * `FrequentSource` seam supplies the inputs reactively.
 */

/** D3: the grid surfaces this many "frequent" apps ahead of the alphabetical rest. */
const val FREQUENT_TOP_N = 6

/**
 * D3: pick the top-N most-launched components from the catalog.
 *
 * Rank: `launchCount DESC`, ties by `lastLaunchTimestamp DESC`. Components with no usage
 * record or a zero count never qualify. Only components actually present in [apps] can
 * qualify — stale records for uninstalled apps are ignored until reconcile/prune (#31
 * follow-ups keep them out of the grid either way).
 */
fun frequentTopN(
    apps: List<AppItem>,
    usage: Map<ComponentName, UsageRecord>,
    n: Int = FREQUENT_TOP_N,
): Set<ComponentName> =
    apps.asSequence()
        .mapNotNull { app -> usage[app.component]?.takeIf { it.hasLaunches }?.let { app.component to it } }
        .sortedWith(compareByDescending<Pair<ComponentName, UsageRecord>> { it.second.launchCount }
            .thenByDescending { it.second.lastLaunchTimestamp })
        .take(n)
        .map { it.first }
        .toSet()

/**
 * D5: order results with the frequent block first (same rank rule as [frequentTopN]),
 * then the remaining matches alphabetically. Favorites mode is excluded upstream —
 * pinned membership alone decides that slice (D5).
 */
fun orderFrequentFirst(
    apps: List<AppItem>,
    frequent: Set<ComponentName>,
    usage: Map<ComponentName, UsageRecord>,
): List<AppItem> {
    val rank = compareByDescending<AppItem> { it.component in frequent }
        .thenByDescending { usage[it.component]?.launchCount ?: 0 }
        .thenByDescending { usage[it.component]?.lastLaunchTimestamp ?: Long.MIN_VALUE }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.rawLabel }
    return apps.sortedWith(rank)
}
