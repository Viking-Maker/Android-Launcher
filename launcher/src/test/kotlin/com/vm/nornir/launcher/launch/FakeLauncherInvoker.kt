package com.vm.nornir.launcher.launch

import android.app.ActivityOptions
import android.content.ComponentName
import android.os.UserHandle
import com.vm.nornir.launcher.model.AppItem

/**
 * Test fake for [LauncherInvoker] (issue #14).
 *
 * Records every `(ComponentName, UserHandle)` launch so tests can assert on the exact target
 * the UI sent — including the correct work-profile [UserHandle] — without a device or a bound
 * `LauncherApps` binder. Mirrors the in-memory fake seams used by the catalog/persistence tests
 * (issue #11 primary-seam strategy).
 */
class FakeLauncherInvoker : LauncherInvoker {

    /** Immutable record of a single launch the UI asked the seam to perform. */
    data class LaunchRecord(
        val component: ComponentName,
        val user: UserHandle,
        val options: ActivityOptions?,
    )

    /** All launches observed, in order. Empty until the first [launch]. */
    val launches: List<LaunchRecord> get() = _launches.toList()

    private val _launches = mutableListOf<LaunchRecord>()

    override fun launch(component: ComponentName, user: UserHandle, options: ActivityOptions?) {
        _launches.add(LaunchRecord(component, user, options))
    }

    /** Convenience: the most recent launch, or `null` if none has happened yet. */
    val lastLaunch: LaunchRecord? get() = _launches.lastOrNull()

    /** Number of launches observed so far. */
    val launchCount: Int get() = _launches.size

    /** True if [app] (its exact component + user) has been launched at least once. */
    fun wasLaunched(app: AppItem): Boolean =
        _launches.any { it.component == app.component && it.user == app.user }

    /** Reset recorded launches (call from a `@Before` if reusing one instance). */
    fun reset() = _launches.clear()
}
