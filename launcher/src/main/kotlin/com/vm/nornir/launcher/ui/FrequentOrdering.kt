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
        .filter { usage[it.component]?.hasLaunches == true } // zero/missing records never qualify
        .sortedWith(
            compareByDescending<AppItem> { usage[it.component]?.launchCount ?: 0 }
                .thenByDescending { usage[it.component]?.lastLaunchTimestamp ?: Long.MIN_VALUE },
        )
        .take(n)
        .map { it.component }
        .toSet()

/**
 * D5: order results with the frequent block first (same rank rule as [frequentTopN]),
 * then the remaining matches alphabetically. Favorites mode is excluded upstream —
 * pinned membership alone decides that slice (D5).
 */
private val LABEL_ORDER: java.text.Collator = java.text.Collator.getInstance()

/** Rank key for one app's usage evidence: count DESC, then recency DESC. */
private fun evidenceRank(usage: Map<ComponentName, UsageRecord>, component: ComponentName): Pair<Int, Long> =
    usage[component]?.let { it.launchCount to it.lastLaunchTimestamp } ?: (0 to Long.MIN_VALUE)

fun orderFrequentFirst(
    apps: List<AppItem>,
    frequent: Set<ComponentName>,
    usage: Map<ComponentName, UsageRecord>,
): List<AppItem> {
    val rank = compareByDescending<AppItem> { it.component in frequent }
        .thenByDescending { evidenceRank(usage, it.component).first }
        .thenByDescending { evidenceRank(usage, it.component).second }
        .then { a, b -> LABEL_ORDER.compare(a.rawLabel, b.rawLabel) } // D5: locale-aware labels
    return apps.sortedWith(rank)
}
