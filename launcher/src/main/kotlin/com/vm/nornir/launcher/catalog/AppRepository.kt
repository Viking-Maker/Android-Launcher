package com.vm.nornir.launcher.catalog

import com.vm.nornir.launcher.model.AppItem
import kotlinx.coroutines.flow.StateFlow

/**
 * Catalog seam: the source of truth for the launchable app set (ADR-0003 §1).
 *
 * Per ADR-0003 §1 the repository is the single owner of the [AppItem] list; the UI
 * seam (#18) never reaches into `LauncherApps` directly — it subscribes to [apps] and
 * triggers [load] on a profile change. Two implementations exist:
 *
 *  - [RealAppRepository] — production; enumerates installed launchers via `LauncherApps`,
 *    normalizes each into an [AppItem] (delegating category mapping to [AppItem.category]),
 *    and keeps the flow live across install/uninstall/availability via [LauncherApps.Callback].
 *  - `FakeAppRepository` (test) — an in-memory `AppItem` list for the no-device seams.
 *
 * Only [load] can mutate the flow; consumers observe [apps] as an immutable snapshot,
 * so replace-style updates (add/remove/reload) are funneled through it on a single
 * dispatcher.
 */
interface AppRepository {

    /** The current catalog snapshot, emitted as a [StateFlow] for Compose `collectAsState`. */
    val apps: StateFlow<List<AppItem>>

    /**
     * (Re)build the catalog for the currently-visible profiles and publish it to [apps].
     *
     * Cheap + idempotent: safe to call on every profile change or on-demand refresh.
     * Implementations may perform cross-APK/binder work, so callers should not assume
     * it returns instantly; suspend keeps the off-main contract explicit.
     */
    suspend fun load()

    /** Release any registered listeners (e.g. the [android.content.pm.LauncherApps.Callback]). */
    fun close()
}
