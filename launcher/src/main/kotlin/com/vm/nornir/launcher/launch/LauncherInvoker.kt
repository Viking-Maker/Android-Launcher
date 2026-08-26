package com.vm.nornir.launcher.launch

import android.app.ActivityOptions
import android.content.ComponentName
import android.os.UserHandle
import com.vm.nornir.launcher.model.AppItem

/**
 * Side-effect seam for launching an app (ADR-0005, issue #14).
 *
 * The real implementation wraps [android.content.pm.LauncherApps.startMainActivity],
 * which is the sole launch path (ADR-0005 §2): it launches directly from the exact
 * `(ComponentName, UserHandle)` identity the catalog already holds, so the launch is
 * multi-profile-correct (a work-profile app opens in its work profile) and carries the
 * system launch animation. No package-name re-resolution.
 *
 * The seam is injected into the UI/ViewModel so tests can swap in [FakeLauncherInvoker]
 * and assert on the exact `(ComponentName, UserHandle)` that was launched — including the
 * correct work-profile [UserHandle] (issue #14 acceptance criteria).
 */
interface LauncherInvoker {

    /**
     * Launch [component] for [user] with optional launch-animation [options].
     *
     * @param component the exact per-activity identity from the catalog (ADR-0003).
     * @param user the profile owning the app — must be the same handle the catalog
     *   returned, so a work-profile app opens in the work profile.
     * @param options optional [ActivityOptions] for the launch animation; `null` if none.
     * @return `true` when the launch was handed to the system (started), `false` when it
     *   could not be performed — unbound `LauncherApps`, or a stale/disabled/uninstalled
     *   entry / inaccessible profile caught by the implementation. Issue #31 Finding 1:
     *   this is the success signal the caller uses to decide whether to record usage, so
     *   failed launches never inflate the frequent ranking (ADR-0006 D6).
     */
    fun launch(component: ComponentName, user: UserHandle, options: ActivityOptions? = null): Boolean

    /**
     * Convenience overload: launch the exact identity carried by an [AppItem].
     * Delegates to [launch] with [AppItem.component] / [AppItem.user] (ADR-0005 §2).
     */
    fun launch(app: AppItem): Boolean = launch(app.component, app.user)
}
