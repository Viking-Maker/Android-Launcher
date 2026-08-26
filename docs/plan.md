<!--
Nornir Launcher MVP — implementation plan / spec.
Published as GitHub issue #11 (label: ready-for-agent).
This is the deliverable plan for the wayfinder implementation map #1.
Consumes accepted ADRs #1-#7 (docs/adr/) and the UI/UX spec (launcher-UI.md).
-->

## Problem Statement

Nornir Launcher is a minimalist, keyboard-driven Android home-screen launcher (package `com.vm.nornir.launcher`). The design phase is complete: seven ADRs (toolchain/version policy, app-category model, `AppItem` data model & `AppRepository`, Compose UI architecture & state model, app-launch flow, frequent-app usage persistence, icon/asset supply) are accepted and closed, and the UI/UX is fully specified in `launcher-UI.md`.

What is missing is the **implementation spec** that an agent can pick up and build. Today there is no single, fully-specified source of truth for the MVP's modules, interfaces, and test seams — an implementer would have to reverse-engineer the ADRs. Without it, the work in #1 (the implementation-plan wayfinder map) has no concrete child ticket carrying the build decision.

## Solution

Build the Nornir Launcher MVP as a single Home `Activity` hosting a Jetpack Compose UI that:

- Enumerates every launchable app via `LauncherApps.getActivityList(null, user)` (API 30+), with a `PackageManager` `MAIN`/`LAUNCHER` `<queries>` fallback — never `QUERY_ALL_PACKAGES`.
- Presents a floating, glassmorphic modal card with a single-line auto-focused search bar, a horizontal category filter bar, and a scrollable app list.
- Filters instantly as the user types (by `AppItem` label and category tags) and by the selected `FilterMode` (`All`, `Favorites`, or a `NornirCategory` chip). Empty categories are hidden.
- Supports keyboard-first navigation: `Up`/`Down` move a mint-green focused card; `Enter` launches through `LauncherInvoker`.
- Self-tracks deliberate launches (no `UsageStatsManager`) to rank a "Favorites / frequently used" surface, persisted locally via DataStore.
- Caches app icons off the main thread, keyed by `(ComponentName, UserHandle, density)`, adaptive-icon aware.

The command-prefix `>` system is **out of scope** (excluded by the user).

## User Stories

1. As a device owner, I want Nornir to be selectable as my default home launcher, so that it replaces the stock launcher on the HOME press.
2. As a minimalist user, I want a single floating card over my wallpaper, so that the launcher feels like a shell overlay rather than a full-screen app.
3. As a keyboard user, I want the search field auto-focused when the launcher opens, so that I can start typing immediately.
4. As a fast launcher, I want typing to instantly filter the app list by name and category, so that I never press a submit button.
5. As a keyboard user, I want `Down`/`Up` to move a focused highlight through results, so that I can navigate hands-on-keyboard.
6. As a keyboard user, I want `Enter` to launch the focused app, so that I never touch the screen.
7. As a launcher, I want to list every installed launchable app including work-profile apps, so that nothing is missing.
8. As a privacy-conscious user, I want the launcher to need no special OS permissions to rank my frequent apps, so that setup is frictionless.
9. As a frequent user, I want my most-launched apps surfaced under the Favorites filter, so that my daily apps are one keystroke away.
10. As a user, I want to pin specific apps to Favorites, so that they persist regardless of launch count.
11. As a user browsing categories, I want chips for Games, Multimedia, Social, News, Productivity, Maps, and Accessibility, so that I can narrow the list by type.
12. As a user, I want empty categories to be hidden, so that I am not offered filters with no matches.
13. As an Android 12+ user, I want the Accessibility category chip only when my device supports it, so that the UI matches platform capability.
14. As a user, I want `All Applications` to return to the full unfiltered list, so that I can escape any filter.
15. As a user, I want app icons to appear crisp at my device density and load without jank, so that the list feels smooth.
16. As a user on a device with many apps, I want icons to be cached so the launcher reopens instantly, so that repeated opens are fast.
17. As a user, I want category assignments that respect a platform hint when present (e.g. a game tagged by its developer), so that obvious categories are correct.
18. As a multi-profile user, I want launching a work app to open it in its correct profile, so that work and personal stay separated.
19. As a user, I want the launcher to survive rotation and process death, so that my query and focus are restored from the repository state.
20. As a developer, I want the catalog, usage, and favorites to be delivered as testable seams, so that behavior can be verified without a device.
21. As a future maintainer, I want the project to build identically from Android Studio and from `./gradlew`, so that CI and local dev agree.
22. As a user, I want a visible "N results" footer, so that I know how many items match.

## Implementation Decisions

- **Package & namespace**: `com.vm.nornir.launcher`; app name "Nornir Launcher". One Home `Activity` (`.MainActivity`).
- **Toolchain (per ADR-0001)**: OpenJDK 21, Kotlin 2.1.x (K2, Compose via `org.jetbrains.kotlin.plugin.compose`), AGP 8.8.2, Gradle wrapper 8.11.x+ committed as source of truth, Compose BOM 2025.x baseline, `minSdk 26` / `compileSdk 36` / `targetSdk 36`. Full CLI build via `gradlew assembleRelease` / `gradlew test`.
- **Manifest (per ADR-0005)**: `.MainActivity` declares `MAIN` + `HOME` + `DEFAULT`, `launchMode="singleTask"`, `clearTaskOnLaunch="true"`, `stateNotNeeded="true"`, `windowSoftInputMode="adjustResize"`. No `LAUNCHER` category (avoids self-listing). `LauncherApps` contract usage requires the `queryActivitiesForUser`/home-launcher path — declare the `<queries>` `<intent>` `MAIN`/`LAUNCHER` block for the `PackageManager` fallback (per ADR-0003/T2).
- **Data model (per ADR-0003)**: immutable `AppItem(component: ComponentName, user: UserHandle, rawLabel: String, platformCategory: Int?, version: Long)`, exposing `category: NornirCategory` derived via a single `mapPlatformToNornir` mapping. No `Drawable`, no usage/pin state, no stored `packageName` (derivable from `component`).
- **Catalog seam — `AppRepository` (interface, ADR-0003)**: `val apps: StateFlow<List<AppItem>>` sourced from `LauncherApps.getActivityList(null, user)`; mirrors multi-user via `UserManager`; recomputes on package changes (`LauncherApps.Callback`). The `PackageManager` path is a fallback only.
- **Category model (per ADR-0002)**: `enum class NornirCategory { GAME, MULTIMEDIA, SOCIAL, NEWS, PRODUCTIVITY, MAPS, ACCESSIBILITY, OTHER }`; `MULTIMEDIA` folds `AUDIO|VIDEO|IMAGE`; `OTHER` is the `UNDEFINED`/unmapped fallback; `ACCESSIBILITY` offered only on API 31+. `FilterMode` is a separate axis (`All`, `Favorites`, `Category(NornirCategory)`). User-override persistence is reserved in the data model but the override **editing UI is out of scope**.
- **UI architecture (per ADR-0004)**: a single activity-scoped `LauncherViewModel` owns the only mutable state and exposes an immutable `uiState: StateFlow<LauncherUiState>` via `combine(repo.apps, favorites.favorites, _query, _filter, _focusedIndex) { ... }`. `results` are derived (never stored) by a pure `filterApps(apps, query, filter, favSet)`; `availableCategories = visibleCategories(apps)` hides empty categories; `focusedIndex` is clamped to the live results range. Composables stay pure and previewable.
- **Launch seam — `LauncherInvoker` (interface, ADR-0005)**: wraps `LauncherApps.startMainActivity(component, user, ActivityOptions)` for the launch animation and multi-profile correctness; declared as a side-effect seam injected into the `LauncherViewModel` so it can be faked in tests. UI records a launch event through this seam (feeds usage).
- **Usage & favorites (per ADR-0006)**: `NornirUsageStore` persists `UsageRecord(launchCount, lastLaunchTimestamp)` keyed by `(ComponentName, UserHandle)` in a Proto/DataStore — self-tracked only, no `UsageStatsManager` for MVP. `FavoritesSource` persists the pinned set in Preferences DataStore. **Favorites filter = pinned set only** (ADR-0006 D5) — the D3 top-N drives a *quiet reorder* of the `All`/category views, not a Favorites union. The `UsageStatsManager` hybrid is a future opt-in (out of scope); seams unchanged.
- **Icon supply (per ADR-0007)**: an icon cache seam that loads `Drawable`s off-main, keyed by `(component, user, density)`, adaptive-icon aware, with a defined verification target (resolution/clarity bar). No live `Drawable` on the `AppItem`.

## Testing Decisions

- **Test external behavior, not internals.** Assert on the `LauncherUiState` snapshot and observable side effects (a recorded launch intent, persisted counts), never on private fields.
- **Primary seam (one, highest):** drive `LauncherViewModel` with fakes — `FakeAppRepository` (in-memory `AppItem` list), `FakeFavoritesSource`, `FakeLauncherInvoker` (records launches) — and assert on `uiState.value`. This single seam covers filtering, category visibility, focus clamping, and launch wiring.
- **Pure-function tests:** `filterApps(...)` (label + category fuzzy match, `Favorites` union, empty-category hiding) and `mapPlatformToNornir(...)` (platform hint → `NornirCategory`, `UNDEFINED`→`OTHER`, `MULTIMEDIA` fold) — pure, no Android runtime needed.
- **Launch seam test:** assert `LauncherInvoker` receives the correct `(ComponentName, UserHandle)` and that the usage store increments on launch.
- **Persistence seam test:** `NornirUsageStore` / `FavoritesSource` faked over an in-memory DataStore assert count increments and pin-set survival.
- **Icon cache test:** assert off-main loading, density-keyed caching, and adaptive-icon handling without a device (Robolectric or a faked resource resolver).
- **Prior art:** greenfield — no existing tests. Use `Turbine` for `StateFlow` assertions and standard Compose UI tests for the focus/keyboard interaction; mirror the ADR-defined seams so tests read against the interfaces, not implementations.

## Out of Scope

- The command-prefix `>` system (run actions by typing commands) — explicitly excluded by the user.
- The category **override editing UI** (override persistence is reserved in the data model only).
- `UsageStatsManager` hybrid ranking (future opt-in).
- In-launcher settings screens / multi-window / widgets / notifications.
- App search beyond installed launchable apps (web, contacts, shortcuts).
- Theming customization beyond the `launcher-UI.md` palette.

## Further Notes

- This spec is the implementation child of the wayfinder map #1 and consumes the accepted ADRs #1–#7 as fixed decisions; it introduces no new ADRs.
- `stateNotNeeded="true"` means the UI rebuilds from `repo.apps` every show — do not rely on `savedInstanceState` for query/focus; the `StateFlow` is the source of truth.
- `clearTaskOnLaunch="true"` resets any in-launcher task launched into the launcher task; the MVP has none, so this is safe.
- Build identically from Android Studio and `./gradlew` (wrapper is SSOT) so CI and local dev agree.
