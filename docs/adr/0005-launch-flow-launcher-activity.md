# 0005 — App-launch flow & launcher-activity semantics

- **Status:** Accepted
- **Date:** 2026-08-17
- **Ticket:** [#8 — Determine app-launch flow and launcher-activity semantics](https://github.com/Viking-Maker/Android-Launcher/issues/8)
- **Depends on:** [#2 — Survey Android app-category APIs / package visibility (T2 research)](https://github.com/Viking-Maker/Android-Launcher/issues/2),
  [#6 — AppItem data model & app-retrieval repository (ADR-0003)](https://github.com/Viking-Maker/Android-Launcher/issues/6)
- **Supersedes:** `launcher-ideas.md` `AppRepository.launchApp(packageName)` — the blueprint's
  `getLaunchIntentForPackage(packageName)` launch path and its implied `PackageManager` launch fallback.

## Context

Nornir Launcher is a minimalist, keyboard-driven home launcher. Issue #8 settles two coupled questions: the
`AndroidManifest` declaration of the home activity (intent filters + task attributes), and the runtime
behavior of launching an app, the HOME key, and BACK. This is **plan-only** (per #1): no `.kt` is committed;
the Kotlin blocks below are the domain sketch the implementer turns into code.

The prerequisites were already settled by other tickets, so this ADR *applies* them:

- **Enumeration + identity (T2 §3.4 / §5.2, ADR-0003):** apps come from
  `LauncherApps.getActivityList(null, user)`, surfaced as `LauncherActivityInfo` carrying a
  `ComponentName` and `UserHandle`. App identity is `(ComponentName, UserHandle)` — per-activity and
  multi-profile correct. `LauncherApps` launch APIs gate only on `canAccessProfile`, *not* on being the
  default launcher, so they work for the current user and visible managed profiles without extra status.
- **Reconciled manifest block (T2 §3):** the home activity already has the correct intent filter and task
  attributes; the blueprint's three task-attribute choices were confirmed correct against Launcher3.

## Decision

### 1. Home-activity manifest declaration

Keep the reconciled T2 §3 block verbatim. The home `Activity` (`.MainActivity`) declares:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:clearTaskOnLaunch="true"
    android:stateNotNeeded="true"
    android:windowSoftInputMode="adjustResize">
    <!-- HOME role required-component signature, per AOSP roles.xml -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Settled sub-decisions:

- **Intent filter `MAIN` + `HOME` + `DEFAULT`, and nothing more.** `MAIN`+`HOME` is the HOME role's
  required-component signature in AOSP `roles.xml`; `DEFAULT` matches Launcher3 and makes Nornir
  default-able. Do **not** add `LAUNCHER` — that would make Nornir appear as a launchable entry in its own
  app list. (The blueprint omitted `MAIN`; T2 added it. Confirmed.)
- **`launchMode="singleTask"`** — persistent single surface; a launched app runs in its own task stacked
  above the launcher task, and HOME always returns to the *same* launcher instance. (Confirmed.)
- **`clearTaskOnLaunch="true"`** — any activities stacked on the launcher task are cleared when the launcher
  task is re-launched from HOME. Matches Launcher3. **Caveat for #7:** if future in-launcher navigation
  (e.g. a settings screen) is launched *into the launcher task*, it is discarded on the next HOME press.
  The UI architecture ticket must launch such screens as separate tasks or accept this reset. (Confirmed.)
- **`stateNotNeeded="true"`** — the activity may be killed and recreated without saved instance state with
  no UX harm, because the UI rebuilds from the repository `StateFlow` each time it is shown. Matches
  Launcher3. (Confirmed.)

### 2. Launch by explicit `ComponentName` + `UserHandle`

Tapping an app item launches the app **directly from the `LauncherActivityInfo` identity the repository
already holds** — never by re-resolving a package name. `LauncherApps.startMainActivity` is the sole launch
path.

```kotlin
fun launchApp(item: AppItem) {
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
    try {
        // item.component / item.user come straight from LauncherActivityInfo
        // (ADR-0003 identity). No PackageManager re-resolution.
        launcherApps.startMainActivity(item.component, item.user, null)
    } catch (e: ActivityNotFoundException) {
        // Activity disabled/uninstalled between enumeration and tap.
    } catch (e: SecurityException) {
        // Profile/user no longer accessible.
    } catch (e: NullPointerException) {
        // LauncherApps binder unbound.
    }
    // On any failure: do nothing visible. The launcher stays foreground, no crash,
    // no toast. LauncherApps.Callback (ADR-0003) self-heals the stale entry on change.
}
```

Settled sub-decisions:

- **No `getLaunchIntentForPackage(packageName)`.** The blueprint's `AppRepository.launchApp(packageName)`
  re-resolves by package, which (a) collapses an app that exposes multiple launcher activities to an
  arbitrary one, (b) cannot target a Work-profile `UserHandle`, and (c) is redundant when the exact
  component is already known from enumeration. Superseded.
- **No `PackageManager` *launch* fallback.** `PackageManager` remains only as the enumeration-visibility
  fallback already specified in T2 (`<queries>` MAIN/LAUNCHER). It is **not** needed for launching:
  `LauncherApps` launch APIs gate only on `canAccessProfile`, not on default-launcher status, so they are
  available to the running launcher without re-resolution.
- **Failure handling (MVP):** wrap `startMainActivity` in try/catch guarding `ActivityNotFoundException`,
  `SecurityException`, and `NullPointerException`. On failure, do nothing visible — launcher stays up, no
  crash, no toast. The hot `LauncherApps.Callback` refreshes the catalog, so a stale/disabled entry
  self-heals on the next change.

### 3. HOME and BACK semantics

- **HOME press.** Because Nornir is the default HOME handler and its activity is `singleTask`, pressing
  HOME at any time — from the launcher itself or from a foreground app — routes the HOME intent to the
  existing launcher task and brings it to front. The launcher re-shows on every HOME press. (Confirmed.)
- **BACK from a launched app.** Standard platform back stack: BACK navigates the app's own stack; at the
  app's root, BACK returns to Nornir (the launcher task beneath it). Nornir performs **no custom
  interception** of in-app BACK. (Confirmed.)
- **BACK while Nornir is foreground.** Primarily a #7 UI concern. BACK first collapses any open
  search/filter overlay and returns to the base app list; when already at the base home state, BACK is
  **inert** — it must never minimize or "exit" the launcher, since the launcher *is* home. (Decision: Q8-a.)
- **Ephemeral UI state across HOME re-show (MVP).** On re-show (which may recreate the activity under
  `stateNotNeeded="true"`), reset ephemeral UI state: clear the typed search query and reset the category
  filter to default. Any persistence of search/filter state is deferred to #7. (Confirmed.)

## Consequences

- **Correctness:** launch is multi-profile correct and handles apps with multiple launcher activities,
  addressing the two failure modes of the blueprint's `getLaunchIntentForPackage` approach.
- **Robustness:** launch failures never crash or disturb the launcher; the live catalog self-heals via the
  `LauncherApps.Callback` already specified in ADR-0003.
- **Consistency:** the manifest matches the HOME-role signature and Launcher3's own activity attributes, so
  Nornir qualifies as a default launcher and behaves like a reference launcher.
- **Out of scope / hand-offs:**
  - #7 owns the exact BACK/overlay-collapse UX, the base-home definition, and any search/filter
    persistence — it must respect the `clearTaskOnLaunch` caveat (separate tasks for in-launcher nav).
  - The enumeration-side `<queries>` MAIN/LAUNCHER block and `LauncherApps.Callback` wiring live in ADR-0003
    and T2; this ADR only consumes them.
