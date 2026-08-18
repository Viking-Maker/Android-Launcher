package com.vm.nornir.launcher.model

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserHandle

/**
 * Immutable catalog record for a single launchable activity.
 *
 * Per ADR-0003 §1 this is a value object of **identity + label + category only**:
 *  - [component] is the per-activity identity (package + class), not package-level.
 *  - [user] carries the profile so multi-user installs are correct.
 *  - [rawLabel] is the original label; the display form is computed at read time.
 *  - [platformCategory] is the raw `ApplicationInfo.category` (or `null` == UNDEFINED).
 *  - [version] is an optional change-detection stamp.
 *
 * It deliberately carries **no** `Drawable` (icons are fetched off-main via IconLoader),
 * **no** usage/pin state, and **no** stored `packageName` (identity is the [ComponentName]).
 *
 * [category] is a derived [NornirCategory] — the only mapping site is [mapPlatformToNornir].
 * A `null` [platformCategory] (UNDEFINED) is normalized to [ApplicationInfo.CATEGORY_UNDEFINED]
 * (-1) before mapping, so a non-null `0` stays an unambiguous GAME.
 */
data class AppItem(
    val component: ComponentName,            // identity (package + class) — per-activity, not per-package
    val user: UserHandle,                    // identity — multi-profile correct
    val rawLabel: String,                    // original label; display form computed at read time
    val platformCategory: Int?,              // raw ApplicationInfo.category (or null == UNDEFINED)
    val version: Long = 0,                   // optional change-detection stamp
) {
    /** The Nornir category derived from [platformCategory]; the single mapping site (ADR-0003). */
    val category: NornirCategory
        get() = mapPlatformToNornir(platformCategory ?: ApplicationInfo.CATEGORY_UNDEFINED, Build.VERSION.SDK_INT)
}
