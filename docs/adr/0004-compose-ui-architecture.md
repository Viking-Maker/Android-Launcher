# 0004 — Compose UI architecture & state model

- **Status:** Accepted
- **Date:** 2026-08-17
- **Ticket:** [#7 — Design Compose UI architecture and state model from launcher-UI.md](https://github.com/Viking-Maker/Android-Launcher/issues/7)
- **Depends on:** [#4 — Toolchain & version policy (ADR-0001)](https://github.com/Viking-Maker/Android-Launcher/issues/4) (Compose BOM 2025.x, minSdk 26),
  [#5 — App-category model (ADR-0002)](https://github.com/Viking-Maker/Android-Launcher/issues/5) (`NornirCategory` + `FilterMode` axis),
  [#6 — AppItem data model & app-retrieval repository (ADR-0003)](https://github.com/Viking-Maker/Android-Launcher/issues/6) (`AppRepository.apps: StateFlow<List<AppItem>>`, `IconLoader`, Drawable-free `AppItem`)
- **Unblocks:** [#8 — App-launch flow & launcher-activity semantics](https://github.com/Viking-Maker/Android-Launcher/issues/8) (UI-side launch seam `LauncherInvoker`),
  [#9 — Frequent-app usage persistence & UI surface](https://github.com/Viking-Maker/Android-Launcher/issues/9) (`FavoritesSource` read seam),
  [#10 — Asset/icon supply & verification target](https://github.com/Viking-Maker/Android-Launcher/issues/10)
- **Supersedes:** `launcher-ideas.md` `MainActivity`/`ui` sketch — the implicit "put state in the Activity / read the repo from composables" blueprint is replaced by a ViewModel-owned, unidirectional-flow screen (see Decision).

## Context

Nornir Launcher is a minimalist, keyboard-driven home launcher. Issue #7 must settle the Compose UI
architecture and state model against `launcher-UI.md` (floating squircle HUD; dark palette `#161623` / mint
`#72E5BE` / lavender `#D6A8FF`; search bar; horizontal category bar; vertical app list; footer count).
This ADR resolves it as a **plan-only** decision (per #1): no `.kt` is committed in this effort; the Kotlin
blocks below are the domain sketch the implementer turns into code in the future MVP.

The three prerequisites were already settled by other tickets, so this ADR only *applies* them:

- **Compose available (ADR-0001):** Jetpack Compose BOM 2025.x on minSdk 26 — `StateFlow` + `collectAsStateWithLifecycle`, `ViewModel`, `LazyColumn`, `Modifier.draw*` are all in scope.
- **Category model (ADR-0002):** `NornirCategory { GAME, MULTIMEDIA, SOCIAL, NEWS, PRODUCTIVITY, MAPS, ACCESSIBILITY, OTHER }` with `MULTIMEDIA = AUDIO|VIDEO|IMAGE`; the filter axis is a **separate** `FilterMode` (`All` / `Favorites`) — not a `NornirCategory`. Empty categories are hidden; `All` and `Favorites` are never hidden.
- **Data spine (ADR-0003):** `AppItem` is an immutable, Drawable-free catalog record keyed by `(ComponentName, UserHandle)`; `AppRepository.apps: StateFlow<List<AppItem>>` emits on a snapshot basis via `LauncherApps.Callback`; icons are fetched off-main via `IconLoader` and are **not** stored on `AppItem`.

Out of scope for #7 (owned elsewhere): the `>` command-prefix system (excluded by user in #1), the launch
*mechanics* and launcher-activity lifecycle (#8), and the `NornirUsageStore` implementation (#9) — #7 only
fixes the **seams** #8/#9 plug into.

## Decision

### 1. State owner — `LauncherViewModel` (Q1-a)

The screen is driven by a single activity-scoped `LauncherViewModel` (the launcher *is* the home activity, so
activity scope is the correct lifecycle owner — no navigation graph exists). It owns the only mutable state
and exposes an immutable snapshot. Composables stay pure and previewable; the catalog stream and keyboard
focus live off-composition.

```kotlin
class LauncherViewModel(
    private val repo: AppRepository,                 // ADR-0003
    private val favorites: FavoritesSource,          // #9 seam (Q6-a)
    private val launcher: LauncherInvoker,           // #8 seam (side effect)
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow<FilterMode>(FilterMode.All)
    private val _focusedIndex = MutableStateFlow(0)

    val uiState: StateFlow<LauncherUiState> =
        combine(repo.apps, favorites.favorites, _query, _filter, _focusedIndex) {
            apps, favSet, query, filter, focus ->
            val results = filterApps(apps, query, filter, favSet)   // pure (Q4-a, Q8-fuzzy)
            LauncherUiState(
                query = query,
                filter = filter,
                results = results,
                availableCategories = visibleCategories(apps),       // empty hidden (ADR-0002)
                focusedIndex = focus.coerceIn(0, (results.lastIndex).coerceAtLeast(0)),
                hasFavorites = favSet.isNotEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LauncherUiState())
}
```

`results` is **derived**, never stored (Q4-a). `focusedIndex` is clamped to the live `results` range so a
keyboard move is always valid and survives rotation.

### 2. State shape & `FilterMode` (Q2-c theming lives in §6; Q4-a)

```kotlin
sealed interface FilterMode {
    data object All
    data object Favorites
    data class Category(val category: NornirCategory) : FilterMode
}

data class LauncherUiState(
    val query: String = "",
    val filter: FilterMode = FilterMode.All,
    val results: List<AppItem> = emptyList(),
    val availableCategories: List<NornirCategory> = emptyList(),
    val focusedIndex: Int = 0,
    val hasFavorites: Boolean = false,
)
```

`FilterMode` maps 1:1 onto the spec's chip set (§4): `All` and `Favorites` are always offered;
`Category(c)` is offered only for `c in availableCategories`.

### 3. Unidirectional flow & keyboard focus (Q5-a)

```kotlin
sealed interface LauncherEvent {
    data class QueryChanged(val text: String) : LauncherEvent
    data class FilterSelected(val filter: FilterMode) : LauncherEvent
    data class MoveFocus(val dir: FocusDir) : LauncherEvent   // UP | DOWN
    data class Launch(val item: AppItem) : LauncherEvent
}

fun handle(event: LauncherEvent) = when (event) {
    is QueryChanged  -> _query.value = event.text
    is FilterSelected-> _filter.value = event.filter
    is MoveFocus      -> _focusedIndex.value = step(_focusedIndex.value, event.dir)  // clamped in uiState
    is Launch         -> launcher.launch(event.item)   // side effect — no state change
}
```

`focusedIndex` is **in** `LauncherUiState` (default 0), so keyboard Up/Down moves the mint highlight
deterministically, survives config change, and is unit-testable. `Launch` is a pure side-effect through
`LauncherInvoker` (§5) — the ViewModel never holds launch result or target intent.

### 4. Favorites read seam (Q6-a) — interface only; #9 implements

```kotlin
interface FavoritesSource {
    val favorites: StateFlow<Set<ComponentName>>   // component identity per ADR-0003
}
```

The Pin chip (spec §4.1 `FilterMode.Favorites`) is shown iff `hasFavorites`; rows read favorite membership
from `favorites`. **#9** supplies the concrete `StateFlow<Set<ComponentName>>` from `NornirUsageStore`
(DataStore + 14-day exponential decay); #7 depends only on this `StateFlow` contract, never on #9's internals.

### 5. Launch seam (Q5-a interface; mechanics in #8)

```kotlin
interface LauncherInvoker {
    fun launch(item: AppItem)   // wraps LauncherApps.startMainActivity(component, user)
}
```

The UI fires `LauncherEvent.Launch(item)`; the ViewModel delegates to `LauncherInvoker`. The actual
`LauncherApps` call, profile handling, and any "return to home" semantics are settled by ticket #8.

### 6. Theming — M3 `darkColorScheme` + named `NornirColors` tokens (Q2-c)

M3 provides typography/elevation/ripple for free; the spec's exact hex for the focus/chip/border states M3
roles mangle are exposed as named constants (palette from `launcher-UI.md` §2):

```kotlin
object NornirColors {
    val Window    = Color(0xFF161623)  // container bg (spec §1; note: map body cited #181825 — see note)
    val Surface   = Color(0xFF252538)  // card / chip bg (§3, §5)
    val Mint      = Color(0xFF72E5BE)  // focused card fill / active text inverse (§5)
    val Lavender  = Color(0xFFD6A8FF)  // selected category pill (§4)
    val Copper    = Color(0xFFB47970)  // search bar border (§3)
    val TextActive   = Color(0xFF11111B)  // text on mint/lavender (§5, §4)
    val TextPrimary  = Color(0xFFCDD6F4)  // primary inactive text
    val TextSubtitle = Color(0xFF8A8AAB)  // subtitle (§5)
    val TextFooter    = Color(0xFF6C7086)  // footer count (§6)
}
```

> **Palette note:** `launcher-UI.md` §1 cites the floating container as `#181825` (the map-addressed "card
> background") while §2's *Window Background* row is `#161623`. The two are the same Catppuccin Mocha surface
> family; the MVP should pick **one** container fill (recommend `#161623` for the window, `#252538` for cards)
> and confirm against the real wallpaper in #10. This ADR pins the tokens; the single window value is a
> #10 verification detail, not a #7 decision.

### 7. Icon painting — `AppIcon` subscribes to `IconLoader` (Q7-a)

`AppItem` holds no `Drawable` (ADR-0003). `AppCard` paints a 38dp rounded icon via a dedicated composable:

```kotlin
@Composable
fun AppIcon(item: AppItem, modifier: Modifier = Modifier) {
    val drawable by remember(item.component, item.user) {
        IconLoader.load(item.component, item.user)   // off-main, cached (ADR-0003)
    }.collectAsStateWithLifecycle(null)
    Box(modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
        .background(NornirColors.Surface)) {
        drawable?.let { Image(painter = rememberDrawablePainter(it), contentDescription = item.rawLabel) }
    }
}
```

### 8. Pure filter — `filterApps` (Q4-a + Q8-fuzzy)

```kotlin
fun filterApps(
    apps: List<AppItem>,
    query: String,
    filter: FilterMode,
    favorites: Set<ComponentName>,
): List<AppItem> {
    val q = norm(query)
    return apps.filter { item ->
        passesFilterMode(item, filter, favorites)
            && (q.isEmpty() || matchesFuzzy(q, norm(item.rawLabel))
                       || matchesFuzzy(q, norm(item.category.displayName)))
    }
}

private fun passesFilterMode(item: AppItem, filter: FilterMode, fav: Set<ComponentName>): Boolean = when (filter) {
    FilterMode.All       -> true
    FilterMode.Favorites -> item.component in fav
    is FilterMode.Category -> item.category == filter.category   // MULTIMEDIA fold via ADR-0003
}

// Fuzzy (Q8-a): accent/case-folded substring OR bounded edit distance, on BOTH label and category name.
// packageName is deliberately NOT searched (privacy + irrelevance).
fun matchesFuzzy(query: String, text: String): Boolean =
    isSubsequence(query, text) || levenshtein(query, text) <= max(1, query.length / 4)
```

`norm()` = lowercase + Unicode NFD + strip combining marks (accent-tolerant: "réd"↔"red", "É"↔"e").
`isSubsequence` catches partial typing ("vsco"→"VS Code", "brv"→"Brave"); the bounded `levenshtein`
(`tol = max(1, len/4)`) catches typos ("spoitfy"→"Spotify"). `matchesFuzzy` is pure and fully unit-testable
on the `filterApps` signature with zero Compose/Android.

### 9. Component decomposition (Q9-a) — the MVP implements exactly this

```
LauncherScreen(vm: LauncherViewModel)                 // collects LauncherUiState; column host
├─ SearchBar(query, onQueryChanged, onGridClick)      // pill field + grid utility btn (§3)
├─ CategoryBar(availableCategories, filter, onFilterSelected)  // All/Favorites always; empties hidden (§4, ADR-0002)
├─ AppList(results, focusedIndex, onMoveFocus, onLaunch, favorites)  // LazyColumn, 8dp gap (§5)
│   └─ AppCard(item, isFocused, isFavorite, icon = AppIcon(item), onClick)  // stateless leaf (§5)
└─ Footer(resultCount = results.size)                 // "N results" (§6)
+ NornirTheme / NornirColors                           // M3 darkColorScheme + §6 tokens
+ PreviewParameterProvider(card/filter matrix) + Paparazzi  // Q3-b
```

`LauncherScreen` injects: `LauncherViewModel` (activity-scoped), `AppRepository` (#6), `FavoritesSource`
(#6-seam / #9), `IconLoader` (#6), `LauncherInvoker` (#8-seam). Every leaf (`SearchBar`, `CategoryBar`,
`AppCard`, `Footer`, `AppIcon`) is **stateless** — all dynamics flow from `LauncherUiState`, so each is
independently `@Preview`-able and Paparazzi-snapshot-able.

### 10. Preview & screenshot strategy (Q3-b)

Per-component `@Preview` funs with a `PreviewParameterProvider` covering: focused vs unfocused `AppCard`,
every `FilterMode` chip state, the footer count, and the empty-results state. On top of that, **Paparazzi**
screenshot tests guard the exact palette (§6 `NornirColors`) and layout metrics (corner radii, chip dims,
8dp gaps) against regressions. Lightweight previews land with the MVP scaffold; Paparazzi follows once the
scaffold compiles.

## Consequences

- The future MVP has a single source of truth (`LauncherViewModel` + `LauncherUiState`) and a clean,
  testable seam everywhere state meets Android: `AppRepository` (#6), `FavoritesSource` (#9), `LauncherInvoker`
  (#8), `IconLoader` (#6).
- `filterApps` / `matchesFuzzy` / `passesFilterMode` are pure Kotlin and unit-testable without a device or
  Compose — the keyboard-first, fuzzy, category-aware filtering from `launcher-UI.md` §7 is verifiable in CI.
- `focusedIndex` in state makes keyboard navigation deterministic and rotation-safe; no focus loss on config
  change.
- The exact spec palette is pinned as `NornirColors`; only the single window-fill value (`#161623` vs the
  §1 `#181825`) is deferred to #10's wallpaper verification, and is a cosmetic constant flip, not a redesign.
- **Does not** write app code (per #1 plan-only). **Does not** decide launch mechanics (#8) or the usage
  store (#9) — it only fixes the interfaces those tickets implement. **Does not** add the `>` command mode
  (out of scope per #1).

> **Implementation note (realized in #18/#19, merged #30 `cf3e441`, #32 `ec92850`).** The architecture ships as
> `com.vm.nornir.launcher.ui`: `LauncherViewModel` (activity-scoped; `uiState` =
> `combine(repo.apps, favorites.favorites, query, filter, focusedIndex)` via `stateIn(WhileSubscribed(5000))`),
> the immutable `LauncherUiState` (+ `FilterMode{All, Favorites, Category}`), and pure `filterApps` /
> `visibleCategories` / `step` in `LauncherFiltering.kt`. Realized deltas from the sketches above:
> - **Fuzzy match (§8):** subsequence ‖ Levenshtein ≤ `max(1, len/4)` over accent/case-folded (NFD,
>   combining-marks-stripped) label or category `displayName`; `packageName` never searched.
>   `NornirCategory.displayName` (ADR-0002 note) lets "games" find game apps. The §8 example pair
>   `"spoitfy" → "Spotify"` is distance 2 under this rule — pinned by test as rejected; reconciliation in #31.
> - **Launch + usage:** `Launch` delegates to `LauncherInvoker` then records usage VM-side (per #18's AC),
>   superseding ADR-0006 D6's invoker-side placement until #31 reconciles success-conditionality.
> - **Keyboard routing (§3):** Up/Down/Enter are intercepted once at card level via `onPreviewKeyEvent`
>   (`handleKeyEvent`) instead of per-list focus moves, so they work wherever focus sits; `focusedIndex`
>   stays in state and is clamped both in `step()` and in the `combine`.
> - **Component tree (§9):** `LauncherScreen → SearchBar/CategoryBar/AppList(AppCard)/Footer` as decided, with
>   `AppIcon` at the presentation boundary loading through `IconLoader` via `produceState` +
>   `Dispatchers.IO` + `rememberDrawablePainter` (density-keyed; placeholder while loading/null).
> - **Wallpaper backing:** `NornirHomeTheme` (`android:windowShowWallpaper=true`, transparent system bars)
>   composites the glass card over the live wallpaper; window fill `#161623`/`#252538` per ADR-0007 pending
>   on-device contrast check (#20).
> - **Previews/tests (§10):** `@Preview`s over `LauncherUiStateProvider` fake states + Robolectric compose
>   tests (`LauncherScreenTest`) driving rendering, autofocus, key routing, Enter-launch and chip events;
>   Paparazzi not yet adopted. Compose UI tests require `ui-test-manifest` merged into **every** unit-test
>   variant (debug **and** release) — a debug-only declaration silently breaks `gradlew test`'s release leg.

