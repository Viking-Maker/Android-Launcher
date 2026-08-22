package com.vm.nornir.launcher.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.UserHandle

/**
 * Real [IconLoader] backed by `LauncherActivityInfo.getBadgedIcon(density)` (ADR-0003 §4).
 *
 * Resolution path for `(component, user, density)`:
 *  1. `context.getSystemService(LauncherApps)` — on an unbound binder (Robolectric, or a
 *     non-launcher process) returns `null` → this seam returns `null`, mirroring the
 *     [com.vm.nornir.launcher.launch.RealLauncherInvoker] failure contract: a stale icon
 *     must never crash the launcher.
 *  2. `launcherApps.getActivityList(component.packageName, user)` — scoped to the one
 *     package, so the cross-APK cost stays proportional to a single app, and profile-
 *     correct via the catalog's own [UserHandle].
 *  3. The entry whose `componentName` matches gets `getBadgedIcon(density)` — the
 *     documented per-activity, badge-correct fetch (T2 §5.2). An adaptive-icon app
 *     yields its raw `AdaptiveIconDrawable` (layers intact; mask applied at render).
 *
 * A missing/uninstalled/disabled entry resolves to no match in step 3 → `null`. The
 * launcher renders a neutral placeholder and self-heals via the catalog's
 * `LauncherApps.Callback` (ADR-0003).
 */
class RealIconLoader(
    private val context: Context,
) : IconLoader {

    override fun get(component: ComponentName, user: UserHandle, density: Int): Drawable? {
        enforceOffMainThread()
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        return try {
            launcherApps.getActivityList(component.packageName, user)
                .firstOrNull { it.componentName == component }
                ?.getBadgedIcon(density)
        } catch (expected: RuntimeException) {
            // SecurityException (inaccessible profile) / dead-object / Stub!-class failures:
            // the documented "do nothing visible" path — null placeholder, no crash.
            null
        }
    }


    internal companion object {
        /**
         * Robolectric runs each test *on* the thread named "main" (its own JVM main
         * thread with a paused `Looper`), so a hard guard would make this seam
         * untestable on the JVM. Tests opt in via this flag; production never sets it.
         */
        @JvmField
        internal var ALLOW_MAIN_THREAD_FOR_TESTS: Boolean = false
    }
}
