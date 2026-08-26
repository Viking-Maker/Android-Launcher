package com.vm.nornir.launcher.ui

import android.content.ComponentName
import com.vm.nornir.launcher.model.AppItem
import com.vm.nornir.launcher.usage.UsageRecord
import com.vm.nornir.launcher.model.NornirCategory
import java.text.Normalizer
import kotlin.math.max

/**
 * Pure filtering + fuzzy matching for the home grid (ADR-0004 §8, issue #18).
 *
 * Everything here is a total function over plain values — no ViewModel, no Compose, no
 * flows — so the whole matcher is unit-testable on the [filterApps] signature alone.
 */

/**
 * Filter [apps] to the slice the home grid shows for ([query], [filter]).
 *
 * A row survives when it passes the chip test ([passesFilterMode]) **and** either the query
 * is empty or it fuzzily matches the label or the category display name (Q8-fuzzy).
 * `packageName` is deliberately NOT searched (privacy + irrelevance).
 *
 * Ordering: outside Favorites mode the surviving rows are reordered frequent-first
 * (ADR-0006 D5) via [orderFrequentFirst]; Favorites keeps pinned-membership order rules —
 * its results are NOT frequency-reordered (D5 explicitly excludes that mode). The new
 * parameters default to "nothing frequent" so existing call sites and tests stay valid.
 */
fun filterApps(
    apps: List<AppItem>,
    query: String,
    filter: FilterMode,
    favorites: Set<ComponentName>,
    frequent: Set<ComponentName> = emptySet(),
    usage: Map<ComponentName, UsageRecord> = emptyMap(),
): List<AppItem> {
    val q = norm(query)
    val results = apps.filter { item ->
        passesFilterMode(item, filter, favorites) &&
            (q.isEmpty() ||
                matchesFuzzy(q, norm(item.rawLabel)) ||
                matchesFuzzy(q, norm(item.category.displayName)))
    }
    return if (filter == FilterMode.Favorites) {
        results
    } else {
        orderFrequentFirst(results, frequent, usage)
    }
}

/** The chip test half of [filterApps]: Favorites is a union over pinned components. */
private fun passesFilterMode(
    item: AppItem,
    filter: FilterMode,
    favorites: Set<ComponentName>,
): Boolean = when (filter) {
    FilterMode.All -> true
    FilterMode.Favorites -> item.component in favorites
    is FilterMode.Category -> item.category == filter.category // fold already applied in AppItem.category (ADR-0003)
}

/**
 * Categories with at least one member, in taxonomy order — empty chips are hidden and the
 * bar recomputes whenever the app list changes (ADR-0002 §4).
 */
fun visibleCategories(apps: List<AppItem>): List<NornirCategory> =
    NornirCategory.entries.filter { c -> apps.any { it.category == c } }

/**
 * Step a focus index one row in [dir]; the raw value may leave `[0, size)` — it is clamped
 * to the live results range again in the uiState combine (ADR-0004 §1), so this only needs
 * to move sensibly.
 */
fun step(index: Int, dir: FocusDir, size: Int): Int = when (dir) {
    FocusDir.UP -> index - 1
    FocusDir.DOWN -> index + 1
}.coerceIn(0, (size - 1).coerceAtLeast(0))

/**
 * Accent/case-folded fuzzy match (Q8-a): [isSubsequence] catches partial typing ("vsco" →
 * "VS Code", "brv" → "Brave" — and any contiguous substring as its special case); bounded
 * [levenshtein] catches single-character typos ("sporify" → "Spotify", `tol = max(1, len/4)`).
 */
fun matchesFuzzy(query: String, text: String): Boolean =
    isSubsequence(query, text) || levenshtein(query, text) <= max(1, query.length / 4)

/**
 * Lowercase + Unicode NFD + strip combining marks — accent-tolerant ("réd" ↔ "red", "É" ↔ "e").
 */
internal fun norm(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")

private val COMBINING_MARKS = Regex("\\p{M}+")

/** True when every char of [query] appears in [text] in order (not necessarily contiguously). */
internal fun isSubsequence(query: String, text: String): Boolean {
    if (query.isEmpty()) return true
    var i = 0
    for (c in text) {
        if (c == query[i]) if (++i == query.length) return true
    }
    return false
}

/** Classic DP edit distance; both inputs are expected pre-[norm]ed by callers. */
internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            curr[j] = minOf(
                prev[j] + 1, // deletion
                curr[j - 1] + 1, // insertion
                prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, // substitution
            )
        }
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[b.length]
}
