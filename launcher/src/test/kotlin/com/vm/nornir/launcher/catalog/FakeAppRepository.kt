package com.vm.nornir.launcher.catalog

import android.content.ComponentName
import android.os.UserHandle
import com.vm.nornir.launcher.model.AppItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Test fake for [AppRepository] (issue #17).
 *
 * An in-memory `AppItem` list behind the same [apps] [StateFlow] the real seam exposes,
 * so the UI seam (#18's `LauncherViewModel`) can be exercised without a device or a bound
 * `LauncherApps` binder. Mirrors [com.vm.nornir.launcher.launch.FakeLauncherInvoker]: it
 * records the exact calls the production contract promises (`load`, `remove`, `clear`,
 * `reset`) and serves a deterministic catalog the test controls via [setApps]/[add].
 *
 * `load()` is a recorded no-op that leaves the published snapshot untouched — the fake
 * never recomputes from the OS; the test owns the contents. All mutating helpers update
 * `_apps` **synchronously**: a fake must be fully deterministic under a test scheduler
 * (`advanceUntilIdle` drains virtual time on the test dispatcher, not `Dispatchers.Default`
 * workers), and the single-writer discipline is a production concern the fake need not
 * reenact.
 */
class FakeAppRepository(
    initialApps: List<AppItem> = emptyList(),
) : AppRepository {
    private val _apps = MutableStateFlow(initialApps.toList())
    override val apps: StateFlow<List<AppItem>> get() = _apps

    /** How many times [load] has been invoked (across resets). */
    var loadCount: Int = 0
        private set

    override suspend fun load() {
        loadCount++
    }

    /** Replace the published catalog with [items]. */
    fun setApps(items: List<AppItem>) {
        _apps.value = items.toList()
    }

    /** Append [item] without disturbing existing entries. */
    fun add(item: AppItem) {
        _apps.update { it + item }
    }

    /** Drop exactly the [AppItem] matching [component] under [user]; a no-op if absent. */
    fun remove(component: ComponentName, user: UserHandle) {
        _apps.update { list -> list.filterNot { it.component == component && it.user == user } }
    }

    /** Empty the catalog. */
    fun clear() {
        _apps.value = emptyList()
    }

    /** Return to the pristine empty state and forget the recorded [load] calls. */
    fun reset() {
        _apps.value = emptyList()
        loadCount = 0
    }

    override fun close() {
        // Nothing to release: the fake holds no OS listeners.
    }

    /** Convenience constructor: seed from a vararg of items. */
    constructor(vararg initialApps: AppItem) : this(initialApps.toList())
}
