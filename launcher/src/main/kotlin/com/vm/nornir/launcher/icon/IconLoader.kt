package com.vm.nornir.launcher.icon

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle

/**
 * Presentation-boundary icon seam (ADR-0003 §4, issue #16).
 *
 * Returns the badged launcher [Drawable] for one catalog identity
 * `(component, user)` rendered at [density] DPI. Per ADR-0003 this seam:
 *
 *  - is **separate** from `AppRepository` and invoked strictly at the presentation
 *    boundary (#7's `AppIcon` composable), never during catalog enumeration — which is
 *    why `AppItem` stays Drawable-free;
 *  - loads **off the main thread**: cross-APK resource inflation is a binder call plus
 *    resource reads (T2 §5.2). Implementations throw [IllegalStateException] from the
 *    main thread rather than silently janking the frame;
 *  - is **adaptive-icon aware**: on API 26+ the returned drawable may be an
 *    `AdaptiveIconDrawable`; it is returned raw (layers intact, mask applied at render)
 *    and never flattened to a bitmap or persisted to disk (ADR-0003: no disk tier);
 *  - is **profile-correct**: the badge for a work-profile app comes from the same
 *    `(component, user)` identity the catalog holds.
 *
 * The Compose `Painter` conversion is #19's call (T2 §5.5 leaves interop to the UI ticket).
 */
interface IconLoader {

    /**
     * Return the raw launcher [Drawable] for ([component], [user]) at [density] DPI.
     *
     * @param component the exact per-activity identity from the catalog (ADR-0003).
     * @param user the profile owning the app — must be the handle the catalog returned,
     *   so a work-profile icon carries the work badge.
     * @param density the target density DPI (e.g. `DisplayMetrics.densityDpi`). Pass `0`
     *   for the platform default; pass the real DPI so icons render crisp (T2 §5.2).
     * @return the raw drawable, or `null` when the identity has no resolvable icon
     *   (uninstalled/disabled entry, inaccessible profile, or unbound system service).
     *   Callers render a neutral placeholder for `null` — the launcher never crashes on
     *   a stale entry.
     * @throws IllegalStateException when called on the main thread (off-main contract).
     */
    fun get(component: ComponentName, user: UserHandle, density: Int): Drawable?
}
