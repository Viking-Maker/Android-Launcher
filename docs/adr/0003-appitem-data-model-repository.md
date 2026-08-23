# 0003 — AppItem data model & app-retrieval repository

- **Status:** Accepted
- **Date:** 2026-08-16
- **Ticket:** [#6 — Design AppItem data model and app-retrieval repository](https://github.com/Viking-Maker/Android-Launcher/issues/6)
- **Depends on:** [#2 — Survey Android app-category APIs / package visibility (T2 research)](https://github.com/Viking-Maker/Android-Launcher/issues/2),
  [#4 — Toolchain & version policy (ADR-0001)](https://github.com/Viking-Maker/Android-Launcher/issues/4),
  [#5 — App-category model (ADR-0002)](https://github.com/Viking-Maker/Android-Launcher/issues/5)
- **Supersedes:** `launcher-ideas.md` `AppItem.kt` / `AppRepository.kt` — the blueprint's `Drawable`-in-record
  model, `packageName`-keyed identity, `QUERY_ALL_PACKAGES` enumeration, baked-in `isPinned`/`launchCount`
  fields, and its freedesktop-style category mapping.

## Context

Nornir Launcher is a minimalist, keyboard-driven home launcher. Issue #6 must settle the data spine the UI
(#7) and the usage/persistence overlay (#9) hang off: the `AppItem` record and the app-retrieval layer
(`AppRepository.kt`). This ADR resolves it as a **plan-only** decision: no `.kt` is committed in this effort
(per #1); the Kotlin blocks below are the domain sketch the implementer turns into code.

The four prerequisites were already settled by other tickets, so this ADR only *applies* them:

- **Enumeration (T2 §3.4 / §5.2):** primary API is `LauncherApps.getActivityList(null, user)` per
  `launcherApps.profiles`; declare a `<queries><intent>MAIN/LAUNCHER</intent></queries>` for
  `PackageManager` fallback. **Do not** use `QUERY_ALL_PACKAGES` (restricted, Play-policy-flagged, no
  launcher need). `getActivityList` is a binder call + cross-APK resource inflation ⇒ off-main-thread.
- **Category type (ADR-0002):** `NornirCategory { GAME, MULTIMEDIA, SOCIAL, NEWS, PRODUCTIVITY, MAPS,
  ACCESSIBILITY, OTHER }`, where `MULTIMEDIA` = platform `AUDIO | VIDEO | IMAGE`. Platform `category` is
  `CATEGORY_UNDEFINED` for the majority of apps ⇒ treat it as an opportunistic hint only.
- **Toolchain (ADR-0001):** Kotlin 2.1.x, coroutines, `StateFlow`, Compose all available; minSdk 26,
  targetSdk 36.
- **Blueprint corrections (T2 §1.1):** drop `QUERY_ALL_PACKAGES`; the `AppCategory` freedesktop taxonomy
  does not exist on Android.

## Decision

### 1. `AppItem` — pure, immutable catalog record

`AppItem` is a **value object of identity + label + category only**. It carries no `Drawable`, no usage/pin
state, and no display-form string. It is the unit emitted by `AppRepository.apps` and consumed by #7.

```kotlin
data class AppItem(
    val component: ComponentName,   // identity (package + class) — per-activity, not per-package
    val user: UserHandle,           // identity — multi-profile correct
    val rawLabel: String,           // original label; the DISPLAY form is computed at read time
    val platformCategory: Int?,     // raw ApplicationInfo.category (or null == UNDEFINED)
    val version: Long = 0           // optional change-detection stamp
) {
    // Single place that maps platform -> NornirCategory. Override hook for a future
    // curated layer. MULTIMEDIA folds AUDIO|VIDEO|IMAGE; UNDEFINED/unknown -> OTHER.
    val category: NornirCategory
        get() = mapPlatformToNornir(platformCategory)
}
```

Settled sub-decisions:

- **Identity = `(ComponentName, UserHandle)`.** `getActivityList` is per-activity and multi-profile, so a
  `packageName`- or `ComponentName`-only key silently collapses distinct launchable entries. A `packageName`
  is always derivable from `component` when a future feature needs it (notification grouping, settings
  filtering, batch cache-clear) — **no stored `packageName` column**.
- **No `Drawable` in the record.** Icons are the expensive part (T2 §5.2) and must be cached + off-main;
  a live `Drawable` is `Context`-bound, non-`Parcelable`, and unstable across recomposition. `AppItem` is
  therefore density-agnostic — the icon cache key `(component, user, density)` is computed by #7 at the
  **presentation boundary**, never stored on the catalog record.
- **No usage/pin state.** `isPinned` / `launchCount` live in the `NornirUsageStore` (#9). Baking them in
  would couple the data layer to the usage store and complicate the `StateFlow`.
- **`category` is a `NornirCategory` computed property** over the retained `platformCategory: Int?`. The
  filter bar filters on `category` (8 chips); the raw platform value is kept underneath as a future-proof
  override hook without complicating the model.
- **Display label computed at read**, not stored, so locale changes re-sort correctly. `rawLabel` keeps the
  original; #7 derives the display form + collation key.
- **`version`** is optional (low-stakes): a monotonically changing stamp (e.g. install/update time) for
  change detection / diffing the emitted list.

### 2. `AppRepository` — hybrid live catalog (interface)

```kotlin
interface AppRepository {
    val apps: StateFlow<List<AppItem>>   // current snapshot, emitted on Dispatchers.Main.immediate
    suspend fun load()                   // first paint; enumeration on Dispatchers.IO/Default
    // Live updates via LauncherApps.Callback:
    //   onPackageAdded / Removed / Changed, onPackagesAvailable / Unavailable,
    //   onPackageLoadingProgressChanged  +  ACTION_MANAGED_PROFILE_ADDED / _REMOVED
    //   -> recompute list (self-exclude per §3) -> emit new list.
}
```

- **Hybrid (cold `load()` + hot `StateFlow` + `LauncherApps.Callback`).** A cold-only API misses
  install/uninstall/profile changes; a hot-only API still needs a trigger. `LauncherApps.Callback` is the
  platform-correct live signal and is required because `PackageManager` does not deliver package broadcasts
  for *other* profiles (T2 §3.4, §5.2).
- **Owns its scope.** Constructor-injected `CoroutineScope` (typically `SupervisorJob +
  Dispatchers.Main.immediate`) so the `StateFlow` survives config changes; enumeration runs on
  `Dispatchers.IO`/`Default`. Implementations live behind the interface so tests/previews can inject fakes
  (a device is required to call `LauncherApps`).
- **No filtering, no usage join.** The repo is pure spine: it returns typed catalog data only (see §4–§5).

> **Implementation note (realized in #17, merged #28 `17e2723`).** The seam ships as three types in
> `com.vm.nornir.launcher.catalog`: `AppRepository` (the interface above, plus `close()` for explicit
> teardown), `RealAppRepository` (production: `context.getSystemService(LauncherApps)`; enumeration
> walks `launcherApps.profiles` → `getActivityList(null, user)` exactly as §3 sketches), and
> `FakeAppRepository` (test sourceset: a synchronous in-memory list behind the same `StateFlow`, with
> `setApps/add/remove/clear/reset` + `loadCount` recording — synchronous because a fake must stay
> deterministic under a test scheduler). Live maintenance uses a `LauncherApps.Callback` (registered on
> the main `Handler`; each event re-dispatches `load()` onto the injected `CoroutineDispatcher`) **plus**
> an `ACTION_MANAGED_PROFILE_ADDED/_REMOVED` receiver — the callback does not fire when a managed profile
> itself appears/disappears. Self-exclusion follows §3 verbatim (`(host home ComponentName,
> Process.myUserHandle())`, never a packageName drop — a work-profile clone of the launcher package stays
> listed). Category retrieval per the §3 note: `LauncherActivityInfo.getApplicationInfo()` on API 29+
> (`VERSION_CODES.Q`), `packageManager.getApplicationInfo(pkg, 0)` below. When `LauncherApps` is unbound
> the repo falls back to a `PackageManager` `MAIN`/`LAUNCHER` query for the calling user (manifest
> `<queries>` block; no `QUERY_ALL_PACKAGES`). JVM tests drive the shadow `LauncherApps`
> (`addActivity`/`notifyPackageAdded`) on Robolectric with main-Looper idling; the internal
> `LauncherActivityInfoInternal` constructor is hidden from the compile stub and is reached via
> reflection inside the sandbox.

### 3. Enumeration & self-exclusion (T2 §5.2 + #6 Q5)

```kotlin
val launcherApps = context.getSystemService(LauncherApps::class.java)!!
val density = context.resources.displayMetrics.densityDpi
val myUser = Process.myUserHandle()
val homeComponent = ComponentName(myPackage, MyHomeActivity::class.java.name) // host home activity

val items = launcherApps.profiles.flatMap { user ->
    launcherApps.getActivityList(null, user).mapNotNull { info ->
        // Self-exclusion: host home activity in the HOST user only — never a blanket
        // packageName == myPackage drop, and never across other profiles.
        if (user == myUser && info.componentName == homeComponent) return@mapNotNull null
        AppItem(
            component = info.componentName,
            user = user,
            rawLabel = info.label.toString(),
            platformCategory = platformCategoryOf(info),   // see note below
        )
    }
}
```

**Self-exclusion rule (#6 Q5):** exclude by `(ComponentName, Process.myUserHandle())` — match
`identity.userHandle == Process.myUserHandle()` *and* the specific Home `ComponentName` (equivalently: only
primary launcher activities of the host package in the host user). **Never** perform an unqualified
`item.packageName == myPackage` drop across the whole catalog, and never exclude the host's *other*
activities or anything in another profile.

**`platformCategory` retrieval note:** on API 29+ use `LauncherActivityInfo.applicationInfo.category`; on
API 26–28 fall back to `packageManager.getApplicationInfo(packageName, 0).category`. Both return the raw
`ApplicationInfo.category` `Int` (or `null`). `getBadgedIcon(density)` is fetched later by `IconLoader`, not
here.

### 4. `IconLoader` — presentation-boundary caching seam (interface)

```kotlin
interface IconLoader {
    /** Returns the raw Drawable for (component, user, density); call OFF the main thread. */
    fun get(component: ComponentName, user: UserHandle, density: Int): Drawable
}
```

- **Separate seam, not inlined into `AppRepository`.** Value = `LauncherActivityInfo.getBadgedIcon(density)`
  (profile-correct), keyed by an **in-memory LRU** on `(component, user, density)` (~a few hundred entries,
  trimmed on `onTrimMemory`). **No disk tier** — adaptive-icon `Drawable`s aren't cleanly serializable and
  refetch from the APK is cheap, so disk caching adds complexity/privacy cost for little gain.
- **Invoked strictly at the presentation boundary** (#7), never during catalog enumeration. This is why
  `AppItem` stays density-agnostic (§1).
- **Returns the raw `Drawable`.** The Compose `Painter` conversion (`toBitmap().asImageBitmap()` vs an
  image-loading library) is #7's call (T2 §5.5 leaves Compose interop to the UI ticket).

> **Implementation note (realized in #16, merged #26 `aa056e9`).** The seam ships as three types in
> `com.vm.nornir.launcher.icon`: `IconLoader` (the interface above; returns `Drawable?` — `null` on
> unresolvable identity rather than throwing, mirroring the `RealLauncherInvoker` failure contract),
> `LruIconCache` (the production `IconLoader`: an `android.util.LruCache` decorator keyed by
> `(component, user, density)`, default 512 entries, `trimMemory()` for the host activity's
> `onTrimMemory`, nulls never negatively cached), and `RealIconLoader` (the
> `LauncherActivityInfo.getBadgedIcon(density)` backing source). The off-main rule is enforced by one
> shared guard (`IconGuard.enforceOffMainThread`) throwing `IllegalStateException` on the main thread;
> JVM tests opt out via `RealIconLoader.ALLOW_MAIN_THREAD_FOR_TESTS` because Robolectric runs each test
> on its own main thread. Tests use `FakeIconLoader`/`CountingIconSource` fakes (no device); the live
> badged-icon fetch is signed off on-device per ADR-0007.

### 5. Boundaries with #7 (UI) and #9 (usage/persistence)

- **#6 stays a pure spine.** It exposes only: the `AppItem` catalog, `apps: StateFlow`, and `IconLoader`.
  It does **not** reference the `NornirUsageStore` type. The catalog `StateFlow` is **joined with
  `NornirUsageStore` inside #7's ViewModel** to produce the rendered list (pin/frequent/launchCount). This
  is the direct payoff of keeping usage state out of `AppItem` (§1) and lets #9 evolve independently.
- **Filtering is #7's.** #6 ships no filter predicates. The 8 `NornirCategory` chips + `All`/`Favorites`
  axis + search are composed in #7. "Hide empty categories" (launcher-UI.md §4) is derivable from the
  catalog's category distribution at the #7 layer; the `Favorites` chip's membership is exactly the #9 join,
  so it cannot live in #6.

### 6. Testability seam

Both `AppRepository` and `IconLoader` are **interfaces**; `LauncherApps`-backed implementations sit behind
them. This gives the plan-only handoff cheap unit tests and Compose previews (which cannot call
`LauncherApps` on a device) by injecting fakes. Consistent with the Kotlin 2.1 / coroutines stack (ADR-0001).

## Consequences

- `AppItem` is a stable, immutable catalog value with no Android-`Context`/`Drawable` baggage ⇒ safe inside a
  `StateFlow`, cheap to diff, and UI-friendly.
- Identity is correct under multi-profile and multi-launcher-activity apps; the host launcher never
  appears in its own list, and other profiles/activities are never wrongly excluded.
- Icons are fetched once, cached off-main, and converted to `Painter` only at render time ⇒ the scroll path
  stays smooth and the model is density-agnostic.
- The catalog is deliberately decoupled from usage/pin and filtering, so #9 (persistence) and #7 (UI) evolve
  without touching the data model — at the cost of a ViewModel-side join in #7.
- Implementers must respect: no `QUERY_ALL_PACKAGES`, enumeration off-main, `LauncherApps.Callback` for live
  updates, and the `(ComponentName, UserHandle)` identity / self-exclusion rule.
