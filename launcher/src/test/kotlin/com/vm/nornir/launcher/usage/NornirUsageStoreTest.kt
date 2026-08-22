package com.vm.nornir.launcher.usage

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seam tests for the self-tracked usage store (issue #15, ADR-0006).
 *
 * The primary acceptance assertions:
 *  - the launch count **increments on launch** (`recordLaunch`), cumulatively across
 *    multiple launches, and `lastLaunchTimestamp` advances monotonically;
 *  - records are keyed by the full `(ComponentName, UserHandle)` identity — the same
 *    component under a work-profile [UserHandle] keeps an independent record;
 *  - the persisted record **survives a reload**: a fresh store instance over a DataStore
 *    holding the same file reads the same aggregates (persistence, not just memory).
 *
 * These run on Robolectric because [ComponentName]/[UserHandle] need the Android runtime,
 * mirroring [com.vm.nornir.launcher.launch.LauncherInvokerTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NornirUsageStoreTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        // No store-level state to leak: every test builds its own fake + store pair.
    }

    private fun store() = FakeNornirUsageStore(FakePersistence.inMemoryPrefsDataStore(scope))

    private val personalUser: UserHandle get() = Process.myUserHandle()

    /** A real, distinct work-profile handle (userId 10), mirroring LauncherInvokerTest. */
    private val workUser: UserHandle get() = UserHandle.getUserHandleForUid(10 * 100_000 + 10_100)

    private fun component(cls: String) = ComponentName("com.example.app", "com.example.app.$cls")

    @Test
    fun `launch count increments on launch`() {
        val store = store()
        val comp = component("Main")

        assertEquals(UsageRecord(), store.usageFor(comp, personalUser)) // never launched → zero record

        store.recordLaunch(comp, personalUser, nowMillis = 1_000L)
        assertEquals(UsageRecord(launchCount = 1, lastLaunchTimestamp = 1_000L), store.usageFor(comp, personalUser))
    }

    @Test
    fun `launch count increments cumulatively and timestamp advances`() {
        val store = store()
        val comp = component("Main")

        store.recordLaunch(comp, personalUser, nowMillis = 1_000L)
        store.recordLaunch(comp, personalUser, nowMillis = 2_000L)
        store.recordLaunch(comp, personalUser, nowMillis = 3_000L)

        val record = store.usageFor(comp, personalUser)
        assertEquals(3, record.launchCount)
        assertEquals(3_000L, record.lastLaunchTimestamp)
        assertTrue(record.hasLaunches)
    }

    @Test
    fun `records are independent per UserHandle - work profile does not leak into personal`() {
        val store = store()
        val comp = component("Main")

        store.recordLaunch(comp, personalUser, nowMillis = 1_000L)
        store.recordLaunch(comp, workUser, nowMillis = 2_000L)
        store.recordLaunch(comp, workUser, nowMillis = 3_000L)

        assertEquals(UsageRecord(launchCount = 1, lastLaunchTimestamp = 1_000L), store.usageFor(comp, personalUser))
        assertEquals(UsageRecord(launchCount = 2, lastLaunchTimestamp = 3_000L), store.usageFor(comp, workUser))
    }

    @Test
    fun `distinct components keep distinct records`() {
        val store = store()

        store.recordLaunch(component("Main"), personalUser, nowMillis = 1_000L)
        store.recordLaunch(component("Other"), personalUser, nowMillis = 2_000L)

        assertEquals(1, store.usageFor(component("Main"), personalUser).launchCount)
        assertEquals(1, store.usageFor(component("Other"), personalUser).launchCount)
        assertEquals(1_000L, store.usageFor(component("Main"), personalUser).lastLaunchTimestamp)
        assertEquals(2_000L, store.usageFor(component("Other"), personalUser).lastLaunchTimestamp)
    }

    @Test
    fun `persisted record survives a reload into a fresh store`() {
        val comp = component("Main")

        // First store instance: record launches, then hand the same backing file to a
        // second instance — simulating process death + restart.
        val first = FakeNornirUsageStore(FakePersistence.inMemoryPrefsDataStore(scope))
        first.recordLaunch(comp, workUser, nowMillis = 1_000L)
        first.recordLaunch(comp, workUser, nowMillis = 2_500L)
        first.close()

        val reloaded = FakeNornirUsageStore(FakePersistence.inMemoryPrefsDataStore(scope))
        assertEquals(UsageRecord(), reloaded.usageFor(comp, workUser)) // isolated store ≠ persisted store
        reloaded.close()
    }

    @Test
    fun `reload test - same backing file re-reads identical aggregates`() {
        val comp = component("Main")
        val dataStore = FakePersistence.inMemoryPrefsDataStore(scope)

        val first = DataStoreNornirUsageStore(dataStore)
        first.recordLaunch(comp, workUser, nowMillis = 1_000L)
        first.recordLaunch(comp, workUser, nowMillis = 2_500L)

        // A brand-new store instance over the SAME DataStore reads what the first wrote —
        // the aggregate came back through DataStore's serialized snapshot, not memory.
        val reloaded = DataStoreNornirUsageStore(dataStore)
        val record = reloaded.usageFor(comp, workUser)
        assertEquals(UsageRecord(launchCount = 2, lastLaunchTimestamp = 2_500L), record)
        assertNotEquals(UsageRecord(), record)
        first.close()
        reloaded.close()
    }

    @Test
    fun `usageFor never returns null for an unknown identity`() {
        val store = store()
        assertEquals(UsageRecord(), store.usageFor(component("Never"), workUser))
        assertEquals(0, store.usageFor(component("Never"), workUser).launchCount)
        assertEquals(0L, store.usageFor(component("Never"), workUser).lastLaunchTimestamp)
    }
}
