package com.vm.nornir.launcher.usage

import android.content.ComponentName
import android.os.UserHandle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * Test fake for [NornirUsageStore] (issue #15).
 *
 * The real [DataStoreNornirUsageStore] over an in-memory DataStore ([FakePersistence]) — the
 * acceptance criterion "fake implementations run over an in-memory DataStore" is met by
 * exercising the identical production code path, not by reimplementing persistence semantics.
 */
class FakeNornirUsageStore(dataStore: DataStore<Preferences>) : NornirUsageStore {
    private val delegate = DataStoreNornirUsageStore(dataStore)

    override fun recordLaunch(component: ComponentName, user: UserHandle, nowMillis: Long) =
        delegate.recordLaunch(component, user, nowMillis)

    override fun usageFor(component: ComponentName, user: UserHandle): UsageRecord =
        delegate.usageFor(component, user)

    /** Release the delegate's private IO thread. */
    fun close() = delegate.close()
}
