package com.vm.nornir.launcher.catalog

import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import com.vm.nornir.launcher.MainActivity
import com.vm.nornir.launcher.model.AppItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Production [AppRepository] backed by `LauncherApps` (ADR-0003 §1).
 *
 * Catalog build path (single writer, same [dispatcher] the fake uses):
 *  1. `launcherApps.profiles` — every visible profile (personal + work/private), so a
 *     multi-profile device shows one unified list keyed by [UserHandle] (ADR-0003 §1).
 *  2. `getActivityList(null, user)` per profile — the documented "all launchable
 *     activities in this profile" call (`null` package ⇒ no package filter).
 *  3. Each [android.content.pm.LauncherActivityInfo] normalizes into an [AppItem];
 *     the category is derived by [AppItem.category] (single mapping site, ADR-0003 §1).
 *  4. The launcher's own home activity is dropped **only in the host user** (ADR-0003 §3):
 *     a work-profile clone of the package stays listed, and the exclusion never blanket-
 *     drops by package name.
 *  5. The assembled list replaces [apps] in one shot — no partial/diffing churn.
 *
 * If `LauncherApps` is unbound (rare managed-profile edge cases), [load] falls back to a
 * `PackageManager` `MAIN`/`LAUNCHER` query for the calling user only (ADR-0003 §1) — the
 * manifest declares the matching `<queries>` block, never `QUERY_ALL_PACKAGES`.
 *
 * Live maintenance: a registered [LauncherApps.Callback] re-runs [load] on package
 * add/removed/changed/availability, so the catalog self-heals after installs and after
 * a profile's apps become visible — the described "the launcher self-heals" contract.
 *
 * Off-main: [load] only touches `LauncherApps`/`PackageManager` (binder + parceled lists);
 * it is `suspend` and runs on [dispatcher] (`Dispatchers.Default` by default), keeping the
 * main thread clear for the UI seam (#18).
 *
 * @param context used to reach `LauncherApps`, `PackageManager`, and the main `Looper`
 *   for callback dispatch.
 * @param dispatcher the single-writer dispatcher for catalog rebuilds; defaults to
 *   `Dispatchers.Default`.
 */
class RealAppRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : AppRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _apps = MutableStateFlow<List<AppItem>>(emptyList())
    override val apps: StateFlow<List<AppItem>> get() = _apps

    private val launcherApps: LauncherApps?
        get() = context.getSystemService(LauncherApps::class.java)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            scope.launch { load() }
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            scope.launch { load() }
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            scope.launch { load() }
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            scope.launch { load() }
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            scope.launch { load() }
        }
    }

    override suspend fun load() {
        val launcherApps = launcherApps
        val items = if (launcherApps != null) {
            buildList {
                for (user in launcherApps.profiles) {
                    for (info in launcherApps.getActivityList(null, user)) {
                        add(
                            AppItem(
                                component = info.componentName,
                                user = info.user,
                                rawLabel = info.label?.toString().orEmpty(),
                                // ADR-0003 §3: `LauncherActivityInfo.getApplicationInfo()` is
                                // only reliable on API 29+; below that read the category from
                                // PackageManager instead.
                                platformCategory =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    info.applicationInfo?.category
                                } else {
                                    categoryPreApi29(info.componentName.packageName)
                                },
                            ),
                        )
                    }
                }
            }.filterNot { it.isHostHomeActivity() }
        } else {
            fallbackItems()
        }
        _apps.value = items
    }

    /** API 26–28 category source per ADR-0003 §3; `null` if the package vanished. */
    private fun categoryPreApi29(packageName: String): Int? =
        context.packageManager?.getApplicationInfo(packageName, 0)?.category

    /**
     * ADR-0003 §3 self-exclusion: drop the launcher's own home activity, but only when it
     * belongs to the host user — a work-profile clone of this very package stays listed,
     * and no blanket packageName filtering happens.
     */
    private fun AppItem.isHostHomeActivity(): Boolean =
        user == Process.myUserHandle() &&
            component == ComponentName(context.packageName, MainActivity::class.java.name)

    /**
     * `PackageManager` fallback path (ADR-0003 §1): used only when `LauncherApps` is
     * unbound. Queries the documented `MAIN`/`LAUNCHER` intent (covered by the manifest's
     * `<queries>` block — never `QUERY_ALL_PACKAGES`) and maps matches to [AppItem]s for
     * the calling user. The category source is the resolve info's `ApplicationInfo`.
     */
    private fun fallbackItems(): List<AppItem> {
        val pm = context.packageManager ?: return emptyList()
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves = pm.queryIntentActivities(query, 0)
        return resolves.map { resolve ->
            AppItem(
                component = ComponentName(resolve.activityInfo.packageName, resolve.activityInfo.name),
                user = Process.myUserHandle(),
                rawLabel = resolve.loadLabel(pm)?.toString().orEmpty(),
                platformCategory = resolve.activityInfo.applicationInfo?.category,
            )
        }
    }

    /**
     * Profile add/remove broadcasts (ADR-0003 §1): `LauncherApps.Callback` does not fire
     * when a managed profile itself appears or disappears — only package-level events do —
     * so the catalog listens for these and recomputes. Registered on the main handler;
     * like the callback, each event just re-dispatches [load].
     */
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch { load() }
        }
    }

    private val profileIntentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
        addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
    }

    init {
        // Register on the main Looper so Android can post callback events; the callback
        // immediately re-dispatches [load] onto [dispatcher], so the OS thread is released
        // instantly and the (potentially heavy) rebuild never runs on it.
        launcherApps?.registerCallback(callback, android.os.Handler(context.mainLooper))
        context.registerReceiver(profileReceiver, profileIntentFilter)
    }

    override fun close() {
        launcherApps?.unregisterCallback(callback)
        context.unregisterReceiver(profileReceiver)
        // Cancel in-flight callback rebuilds so nothing publishes to [apps] after close.
        scope.cancel()
    }
}
