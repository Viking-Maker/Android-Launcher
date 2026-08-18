# 0002 — App-category model: custom `NornirCategory` hybrid

- **Status:** Accepted
- **Date:** 2026-08-16
- **Ticket:** [#5 — Choose the app-category model: custom enum vs platform categories](https://github.com/Viking-Maker/Android-Launcher/issues/5)
- **Depends on:** [#2 — Survey Android app-category APIs](https://github.com/Viking-Maker/Android-Launcher/issues/2) (facts), [#4 — Toolchain/version policy](https://github.com/Viking-Maker/Android-Launcher/issues/4) (minSdk 26)
- **Supersedes:** `launcher-ideas.md` `AppCategory` enum (freedesktop taxonomy) — flagged as a blueprint error in T2 #2 §1.1.

## Context

`Nornir Launcher` is a minimalist, keyboard-driven home launcher. The filter bar (per `launcher-UI.md` §4)
needs an app-category model. The platform exposes `ApplicationInfo.category`, but T2 #2 established it is
`CATEGORY_UNDEFINED` for the majority of installed apps: it is set only by the *target app's own* manifest
(`android:appCategory`) or by *the installer* via `setApplicationCategoryHint()` — neither of which a
third-party launcher controls (AOSP bundles set it on 0 of 11 checked). Android's real set is 9 defined
values + UNDEFINED; the blueprint's enum (`CHAT, CODE, EDUCATION, GRAPHICS, WEB, SYSTEM`) is a
freedesktop/Linux taxonomy that does not exist on Android.

A parallel fact-find found no viable *non-platform* source: Google Play exposes no package→category API for
arbitrary apps; F-Droid's index covers only FOSS apps (a small fraction of a typical install set); peers
either bundle curated category maps (Neo "Flowerpot") or skip category filtering entirely (Lawnchair).

## Decision

Model category with **Nornir's own sealed type**, a hybrid derivation:

```kotlin
enum class NornirCategory { GAME, MULTIMEDIA, SOCIAL, NEWS, PRODUCTIVITY, MAPS, ACCESSIBILITY, OTHER }
```

- `MULTIMEDIA` groups platform `CATEGORY_AUDIO | CATEGORY_VIDEO | CATEGORY_IMAGE` into one chip.
- `OTHER` is the fallback for `CATEGORY_UNDEFINED` / unmapped.
- `ACCESSIBILITY` (`CATEGORY_ACCESSIBILITY`, API 31+) is offered only on API 31+ devices.

`AppItem.category: NornirCategory` is derived per-app in this order:
1. **User override** (persisted, data-model-only — see below).
2. **Platform hint** when `!= CATEGORY_UNDEFINED`, mapped into `NornirCategory`.
3. **`OTHER`** (the common case).

`ALL` and `FAVORITES` are a **separate `FilterMode` axis**, not enum members. `SYSTEM` is a visibility
toggle, not a category. The filter bar shows `{All, Favorites}` ∪ (every `NornirCategory` with ≥1 member);
**empty categories are hidden** (recomputed on app-list change).

User-override persistence is reserved in the data model (Preferences DataStore, reusing #3's pattern) but the
override **editing UI is out of scope** for this plan-only effort.

## Consequences

- `AppItem.category` type and the §4 filter UI are now fixed (unblocks #6, #9, #10).
- We do not depend on a mostly-empty platform field and can extend with a curated/override layer later.
- The `launcher-UI.md` §4 13-chip set (Code brackets, Graduation cap, Paintbrush, Wi-Fi, Globe…) is revised
  to the 8 real chips; the spec was built on the mistaken freedesktop taxonomy.
- `MULTIMEDIA` may keep finer `AUDIO/VIDEO/IMAGE` storage underneath (a #6 detail) but the filter bar shows
  exactly 8 chips regardless.

## Alternatives considered

- **Platform `Int` passthrough** — rejected: can't populate, group, carry FAVORITES, or accept overrides.
- **Blueprint enum** — rejected: not Android's taxonomy.
- **No categories** — rejected: discards the categories we do get and the UI spec.
