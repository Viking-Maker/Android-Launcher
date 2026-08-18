# 0006 — Frequent-app usage persistence & UI surface

- **Status:** Accepted
- **Date:** 2026-08-17
- **Ticket:** [#9 — Define frequent-app usage-persistence behavior and UI surface](https://github.com/Viking-Maker/Android-Launcher/issues/9)
- **Depends on:** [#2 — Survey Android app-category APIs (T2 research)](https://github.com/Viking-Maker/Android-Launcher/issues/2) (package-visibility facts; enumeration),
  [#6 — AppItem data model & app-retrieval repository (ADR-0003)](https://github.com/Viking-Maker/Android-Launcher/issues/6) (`AppItem` identity = `(ComponentName, UserHandle)`; `DataStore` override-persistence pattern; `LauncherApps.Callback` reconcile),
  [#7 — Compose UI architecture & state model (ADR-0004)](https://github.com/Viking-Maker/Android-Launcher/issues/7) (`LauncherViewModel`, `FavoritesSource` seam, `filterApps`),
  [#8 — App-launch flow & launcher-activity semantics (ADR-0005)](https://github.com/Viking-Maker/Android-Launcher/issues/8) (`LauncherInvoker` launch seam)
- **Unblocks:** MVP implementation of the usage overlay (#9 plan-only effort).
- **Supersedes:** `launcher-ideas.md` §3 `AppItem.kt` `isPinned`/`launchCount` fields and § "Frequent App Usage Persistence" (Room/DataStore sketch) — the blueprint baked usage state into `AppItem` and proposed a reactive `launchCount DESC` sort. Both are replaced by this ADR (usage state lives in `NornirUsageStore`, separate from the catalog record per ADR-0003; Favorites and Frequent are distinct dimensions).

## Context

Nornir is a minimalist, keyboard-driven, privacy-respecting home launcher (Kotlin + Jetpack Compose, `com.vm.nornir.launcher`). Issue #9 must settle, plan-only (per #1), the behavior of usage persistence: what "frequent" means, where frequent apps surface, how `Favorites` relates to frequency, what metrics are tracked, and the privacy posture (self-tracked launches vs `UsageStatsManager`).

The prerequisites were already settled by other tickets, so this ADR *applies* them and fills the one open gap (T2 §7 did not cover a usage mechanism):

- **Identity = `(ComponentName, UserHandle)`** (ADR-0003) — usage records key on exactly this identity, so they join the catalog cleanly and are multi-profile-correct by construction.
- **`AppItem` carries no usage/pin state** (ADR-0003) — `launchCount`/`isPinned` belong in a `NornirUsageStore`, never on the catalog record, or the `StateFlow` couples to the usage store.
- **`FavoritesSource` is an explicit, user-owned pin set** (ADR-0004): `interface FavoritesSource { val favorites: StateFlow<Set<ComponentName>> }`. The Pin chip (`FilterMode.Favorites`) is shown iff `hasFavorites`; membership is read from `favorites`.
- **`launcher-UI.md` §4 labels the Pin chip "Favorites / Frequently Used"** — this conflates two concepts the design below deliberately separates.
- **Nornir is the HOME launcher** (ADR-0005) — deliberate launches route through `LauncherInvoker.launchApp(item)`, giving a single, permission-free instrumentation point.
- **A `DataStore` override-persistence pattern already exists** (ADR-0003 user-override layer) — usage persistence should reuse it, not introduce a second mechanism.

## Decision

Six sub-decisions (grilled and confirmed with the issue owner):

### D1 — Privacy posture: self-tracked (no `UsageStatsManager` for MVP)

Record a launch event each time Nornir itself launches an app through `LauncherInvoker` (ADR-0005). **Do not** use `UsageStatsManager` (`queryUsageStats`/`queryEvents`) as the default.

- **Why self-tracked:** Nornir is the HOME launcher, so the dominant share of *deliberate* launches (tap / keyboard-enter) flow through its launch seam — a strong, representable signal. It needs **no OS permission** and keeps all data fully local (the launcher's stated minimalist/private posture). `UsageStatsManager` would require the `PACKAGE_USAGE_STATS` **special permission** (granted in *Settings › Special app access › Usage access*; on some OEM/enterprise builds it is hidden), a meaningful setup and privacy-story cost, for a richer signal we don't need at MVP.
- **Limitation (accepted):** launches from recents / notifications / other entry points aren't counted, and counts reset with app data. Both are acceptable for a frequency *signal* that only reorders the default list.
- **Future opt-in (out of scope):** a hybrid that enriches self-tracked counts with `UsageStatsManager` when the user explicitly grants the permission. The identity key and read/write seams are unchanged, so this is a drop-in later.

### D2 — Metrics: aggregate counters (no raw event log)

Per `(ComponentName, UserHandle)` store exactly:

```kotlin
data class UsageRecord(
    val launchCount: Int = 0,              // cumulative deliberate Nornir launches
    val lastLaunchTimestamp: Long = 0L,    // System.currentTimeMillis() at last launch
)
```

- **Why aggregates:** every viable "frequent" definition (top-N, last-used recency, threshold) needs only these two scalars. Storing a raw event log (`(component, user, timestamp)` per launch) would enable true time-windows/decay but at the cost of unbounded growth and pruning — over-engineering for MVP.
- **Upgrade path:** swapping the store for a Room event log later (preserving the same identity key and `FrequentSource`/`NornirUsageStore` interfaces) is a non-breaking change.

### D3 — Definition of "frequent": top-N by cumulative `launchCount`

- Rank apps by **cumulative `launchCount` descending**; the highest **N** are "frequent".
- **Tiebreaker:** equal counts are ordered by `lastLaunchTimestamp` descending (most-recently-used first).
- **Default `N = 6`** — a single named constant (`FREQUENT_TOP_N = 6`) the implementer may tune.

```kotlin
const val FREQUENT_TOP_N = 6

fun frequentTopN(
    apps: List<AppItem>,
    usage: Map<ComponentName, UsageRecord>,
    n: Int = FREQUENT_TOP_N,
): Set<ComponentName> =
    apps.mapNotNull { app ->
        usage[app.component]?.let { rec -> app to rec }
    }.filter { (_, rec) -> rec.launchCount > 0 }
     .sortedWith(compareByDescending<Pair<AppItem, UsageRecord>> { it.second.launchCount }
                    .thenByDescending { it.second.lastLaunchTimestamp })
     .take(n)
     .map { it.first.component }
     .toSet()
```

- **Why this definition:** deterministic and predictable for the user (no opaque decay formula producing "why did this disappear?" confusion). Rejects recency-weighted live scoring (D3-alt) as needless complexity for MVP.

### D4 — Favorites and frequency are two separate dimensions

- **`Favorites`** remains the explicit, user-owned manual pin set defined by ADR-0004's `FavoritesSource`. It is **never** mutated or reordered by the frequency signal.
- **`Frequent`** is a separate *computed* read (D6, `FrequentSource`) that only influences default ordering (D5). The two must not be merged into one "Favorites / Frequently Used" set.

This resolves the `launcher-UI.md` §4 labeling ambiguity: the Pin chip = manual Favorites only; "frequent" is expressed by ordering, not by membership in that chip.

### D5 — UI surface: quiet reorder within `All` (and category filters)

- In `FilterMode.All` (and each `FilterMode.Category`), results are ordered **frequent-first**, then by label:
  - frequent apps (the D3 top-N set) sort to the top by `launchCount DESC` / `lastLaunchTimestamp DESC`;
  - the remaining apps fall through by `rawLabel` (locale-aware).
- **`FilterMode.Favorites` is explicitly excluded** from the frequency reorder — the user's manual order/predictability is preserved. (Favorites are simply `item.component in fav`, in insertion/identity order.)
- **No new UI element:** no dedicated frequent row, no new filter chip. The filter bar stays at the settled `{All, Favorites}` ∪ `NornirCategory` layout (ADR-0002). This honors the "reordering the list" option and the minimalist brief.

```kotlin
// ADR-0004 §8 filterApps, extended (shape only — implementer threads the UsageRecord lookup):
fun filterApps(
    apps: List<AppItem>,
    query: String,
    filter: FilterMode,
    favorites: Set<ComponentName>,
    frequent: Set<ComponentName>,   // from FrequentSource (D6)
): List<AppItem> {
    val q = norm(query)
    val matched = apps.filter { item ->
        passesFilterMode(item, filter, favorites)
            && (q.isEmpty() || matchesFuzzy(q, norm(item.rawLabel))
                       || matchesFuzzy(q, norm(item.category.displayName)))
    }
    // Frequency reorder applies to every mode except the manual Favorites set.
    return if (filter == FilterMode.Favorites) matched
           else orderFrequentFirst(matched, frequent)
}

private fun orderFrequentFirst(
    apps: List<AppItem>,
    frequent: Set<ComponentName>,
): List<AppItem> {
    val freq = apps.filter { it.component in frequent }
                 .sortedWith(compareByDescending<AppItem> { /* launchCount */ 0 }
                                .thenByDescending { /* lastLaunchTimestamp */ 0 })
    val rest  = apps.filter { it.component !in frequent }
                 .sortedBy { it.rawLabel }
    return freq + rest
}
```

> The `launchCount`/`lastLaunchTimestamp` ordering inside the frequent block is sourced from `NornirUsageStore` (D6); the `0` placeholders above are where the implementer threads the record lookup. The *shape* of the reorder is the decision.

`LauncherViewModel` (ADR-0004) combines `repo.apps`, `favorites.favorites`, and the new `frequent.frequent` (a `StateFlow<Set<ComponentName>>` from `FrequentSource`) into `results` via the extended `filterApps`. `frequent` membership is otherwise invisible to the UI.

### D6 — Storage: `DataStore` map, reconciled on package removal

- **Store:** reuse ADR-0003's `DataStore` pattern. A `[component#user -> UsageRecord]` map (serialized as a `Map<String, UsageRecord>` preference; the key string is `component.flattenToString() + "#" + user.serialize()`), including `UserHandle` so multi-profile is correct by construction.
- **Write path:** `NornirUsageStore.recordLaunch(component, user)` is invoked from `LauncherInvoker.launchApp(item)` (ADR-0005) on each successful Nornir launch — increment `launchCount`, set `lastLaunchTimestamp = now`. This is the single instrumentation point (D1).
- **Read path:** `interface FrequentSource { val frequent: StateFlow<Set<ComponentName>> }` computes the D3 top-N from `NornirUsageStore` (recomputed on `repo.apps` change and on each `recordLaunch`). This is the companion seam to ADR-0004's `FavoritesSource`.
- **Reconcile / prune:** on `LauncherApps.Callback` `onPackageRemoved` / `onPackagesUnavailable` (already wired in ADR-0003 for the catalog `StateFlow`), delete the matching key(s) from the usage map; ignore any stored key whose identity no longer appears in `repo.apps` (defensive on read). This prevents orphan counters and keeps usage in lockstep with the live catalog.

```kotlin
interface NornirUsageStore {
    fun recordLaunch(component: ComponentName, user: UserHandle)   // write (D1/D6)
    fun usageFor(component: ComponentName, user: UserHandle): UsageRecord
}

interface FrequentSource {                                  // read seam (D6), companion to ADR-0004 FavoritesSource
    val frequent: StateFlow<Set<ComponentName>>             // top-N by D3
}
```

## Consequences

- The usage overlay now has a complete, coherent plan: self-tracked (private, permission-free), aggregate counters keyed on catalog identity, top-N-by-count definition, Favorites kept separate, quiet reorder in `All`/category filters only, and a `DataStore` store reconciled on package removal.
- **ADR-0004 honored & slightly extended:** `FavoritesSource` is untouched; `filterApps` gains a `frequent` parameter and a `orderFrequentFirst` step that excludes `FilterMode.Favorites`. No new UI surface, no filter-bar change.
- **ADR-0003 honored:** usage state stays off `AppItem`; the existing `DataStore` override-persistence pattern is reused; `LauncherApps.Callback` already drives both catalog and usage reconcile.
- **ADR-0005 honored:** the launch seam (`LauncherInvoker`) is the single write point; no `UsageStatsManager` dependency is added.
- `launcher-ideas.md`'s `isPinned`/`launchCount` fields and Room/DataStore "sort by `launchCount DESC`" sketch are superseded (kept as historical blueprint only).
- Establishes two seams the implementer turns into code in the MVP: `NornirUsageStore` + `FrequentSource`.

## Alternatives considered

- **D1 `UsageStatsManager` default** — rejected: needs `PACKAGE_USAGE_STATS` special permission (friction + hidden on some OEM/enterprise builds) for a signal Nornir already captures via its HOME launch seam. Deferred to an explicit opt-in.
- **D2 raw event log** — rejected: unbounded growth + pruning, over-engineering for MVP. Aggregate counters cover all needed definitions.
- **D3 recency-weighted live score** — rejected: opaque to the user ("why did this drop off?"), needs a pinned decay formula. Top-N-by-count is deterministic.
- **D4 merged Favorites ∪ Frequent set** — rejected: silently redefines the accepted `FavoritesSource` contract (ADR-0004) and the manual pins would be reordered by an opaque signal.
- **D5 dedicated frequent row / new `Frequent` filter chip** — rejected: adds UI not in spec; a new chip re-conflates with Favorites and grows the settled 8+2 filter bar. Quiet reorder is the most minimalist expression.
- **D6 `Room` table** — rejected as MVP default: introduces a second persistence mechanism alongside the ADR-0003 `DataStore` pattern for just two scalar fields. Upgrade path preserved.
