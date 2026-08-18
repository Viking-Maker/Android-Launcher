# 0007 — Icon/asset supply & verification target

- **Status:** Accepted
- **Date:** 2026-08-17
- **Ticket:** [#10 — Confirm icon/asset supply and verification target for the future MVP](https://github.com/Viking-Maker/Android-Launcher/issues/10)
- **Depends on:** [#1 — Implementation plan (map)](https://github.com/Viking-Maker/Android-Launcher/issues/1),
  [#4 — Toolchain & version policy (ADR-0001)](https://github.com/Viking-Maker/Android-Launcher/issues/4),
  [#7 — Compose UI architecture (ADR-0004)](https://github.com/Viking-Maker/Android-Launcher/issues/7) (window-fill deferral `#161623` vs `#181825`)
- **Unblocks:** the future MVP implementation + manual test checklist; closes the two *Not yet specified* (fog-of-war) items on #1.

## Context

Nornir Launcher is a minimalist, keyboard-driven home launcher. This effort is **plan-only** (per #1): no
`.kt` is committed, and no Android scaffold (Gradle module, `res/`, manifest) exists yet in the repo. Issue
#10 closes the two logistics items the plan left open so the handoff artifact is actionable:

1. **Icon/asset ownership** — who supplies the launcher's own `@mipmap` home-app icon + brand assets for
   "Nornir Launcher", and what the placeholder policy is until branded assets exist.
2. **Verification target** — how the future MVP is verified: physical device via `adb` (the blueprint's
   recommendation, lower CPU/RAM) vs emulator vs both.

Note the scope split: per-app icons shown in the app list are already owned by ADR-0003's `IconLoader`
(`LauncherApps`/`LauncherActivityInfo` → `ActivityInfo` icon resource, off-main LRU). The `@mipmap` question
in #10/this ADR is **only** the launcher's own home-app icon (what the system app drawer / settings shows for
"Nornir Launcher"), not the per-app list icons.

## Decision

### A. Icon / asset ownership — placeholder policy, maintainer-supplied branding later

- **Default policy (MVP build & verify):** ship the standard Android Studio adaptive placeholder launcher
  icon set — `mipmap-anydpi/ic_launcher` + `mipmap-anydpi/ic_launcher_round` (the generated adaptive-icon
  template). Reference it as `android:icon="@mipmap/ic_launcher"` /
  `android:roundIcon="@mipmap/ic_launcher_round"` in the manifest, exactly as the blueprint (`launcher-ideas.md`)
  already wired (only the label/namespace differ per #1: "Nornir Launcher" / `com.vm.nornir.launcher`).
- **Branded assets are a maintainer deliverable, not an engineering blocker.** Designed "Nornir Launcher"
  brand art (launcher icon foreground/background, store art, any splash/brand drawables) is owned and supplied
  by the project maintainer (Viking-Maker) at branding time. It is gated on a branding decision and is
  **out of the critical path** for the MVP build/verification — the placeholder set is sufficient to install,
  set-as-default, and verify the launcher end to end.
- **Asset hygiene:** when branded art lands, replace only the `ic_launcher` / `ic_launcher_round` adaptive-icon
  foreground+background (keep the adaptive-icon shape; do not regress to a single-density PNG). No other
  launcher-owned assets are required for the MVP.

### B. Verification target — physical device via `adb` primary; emulator secondary

- **Primary target: a physical Android device flashed at `minSdk`–`targetSdk` (API 26 → 36) via USB debugging
  (`adb`).** This follows the blueprint's explicit testing tip ("physical device via USB Debugging (`adb`)
  uses significantly less CPU and RAM than running an Android Emulator") and is the realistic home-launcher
  surface (a launcher only earns its behavior on a real HOME role).
- **Secondary / fallback: Android Studio AVD (emulator)** at a representative API level, used for CI and for
  machines without a device. It is acceptable for functional/manifest checks but is **not** the sign-off
  target for HOME-role behavior, wallpaper contrast, or jank — those require the physical device.
- **Wallpaper verification (resolves the ADR-0004 deferral):** the single window-fill constant is pinned as
  `#161623` (window) / `#252538` (cards) per ADR-0004. At MVP verification on the **physical device**, confirm
  the floating-card contrast against a *real* wallpaper; if the `#161623` window fill reads poorly on a light
  or busy wallpaper, flip only that one constant to `#181825` (the `launcher-UI.md` §1 value). This is a
  cosmetic one-line constant change, not a redesign.

## Consequences

- The MVP can be built and verified without any bespoke brand art; the placeholder adaptive icon is the stated default.
- Branded launcher icon is explicitly deferred to a maintainer-supplied asset and does not block handoff.
- Verification is unambiguous: physical device via `adb` is the sign-off target; emulator is CI/fallback only.
- ADR-0004's only open verification detail (window-fill vs real wallpaper) is now resolved as a physical-device
  confirmation with a documented one-constant fallback.
- **Does not** write app code (per #1 plan-only). **Does not** decide the `>` command-prefix system (out of scope per #1).
