package com.vm.nornir.launcher.launch

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.vm.nornir.launcher.model.AppItem

/**
 * Real [LauncherInvoker] backed by [LauncherApps.startMainActivity] (ADR-0005 §2).
 *
 * `LauncherApps` launch APIs gate only on `canAccessProfile`, *not* on default-launcher
 * status, so they are available to the running launcher. The launch goes straight from the
 * catalog identity — no `PackageManager.getLaunchIntentForPackage`, no `getLaunchIntent*`:
 *  - it never collapses an app with multiple launcher activities to an arbitrary one;
 *  - it can target a Work-profile [UserHandle];
 *  - it is redundant when the exact component is already known from enumeration.
 *
 * Failure handling follows ADR-0005 §2: a stale/disabled/uninstalled entry, an inaccessible
 * profile, or an unbound `LauncherApps` binder must do nothing visible — the launcher stays
 * foreground, no crash, no toast. `LauncherApps.Callback` (ADR-0003) self-heals the stale
 * entry on the next catalog change.
 */
class RealLauncherInvoker(
    private val context: Context,
) : LauncherInvoker {

    override fun launch(component: ComponentName, user: UserHandle, options: ActivityOptions?): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
        return try {
            // item.component / item.user come straight from LauncherActivityInfo (ADR-0003
            // identity). ActivityOptions.toBundle() carries the launch animation; null if none.
            launcherApps.startMainActivity(component, user, null, options?.toBundle())
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false // Activity disabled/uninstalled between enumeration and tap.
        } catch (_: SecurityException) {
            false // Profile/user no longer accessible.
        } catch (_: NullPointerException) {
            false // LauncherApps binder unbound.
        }
        // On any failure: do nothing visible — the launcher stays up (ADR-0005 §2) — and the
        // `false` return tells the caller not to record usage (#31 Finding 1).
    }
}
