package com.vm.nornir.launcher.launch

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import com.vm.nornir.launcher.model.AppItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seam tests for [LauncherInvoker] (issue #14).
 *
 * The primary assertion is the acceptance criterion: the fake records the exact
 * `(ComponentName, UserHandle)` target the UI sent — including the correct work-profile
 * [UserHandle] — and never collapses a multi-profile identity. These run on Robolectric
 * because [AppItem]/[UserHandle] need the Android runtime, mirroring [AppItemTest].
 *
 * `UserHandle.of` is a hidden SystemApi, so a work-profile handle is built through the public
 * [UserHandle.getUserHandleForUid] (userId * 100000 + a uid) to get a real, distinct handle
 * from the personal [Process.myUserHandle].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherInvokerTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val personalUser = Process.myUserHandle()

    /** Build a real, distinct work-profile handle (userId 10) via public API. */
    private val workUser: UserHandle get() = UserHandle.getUserHandleForUid(10 * 100_000 + 10_100)

    private fun item(component: ComponentName, user: UserHandle) = AppItem(
        component = component,
        user = user,
        rawLabel = "Example",
        platformCategory = null,
    )

    @Test
    fun fakeRecordsExactComponentAndPersonalUser() {
        val component = ComponentName("com.example", "com.example.Main")
        val fake = FakeLauncherInvoker()

        val started = fake.launch(component, personalUser)

        assertTrue(started) // default: the launch succeeds
        assertEquals(1, fake.launchCount)
        val record = fake.lastLaunch
        assertEquals(component, record?.component)
        assertEquals(personalUser, record?.user)
        assertNull(record?.options) // no options supplied -> null
    }

    @Test
    fun fakeReportsFailureForInjectedFailingComponent() {
        // Issue #31 Finding 1: the seam reports whether the launch actually started so the
        // caller can conditionally record usage. The fake injects failure per component.
        val component = ComponentName("com.example.stale", "com.example.stale.Main")
        val fake = FakeLauncherInvoker(failingComponents = setOf(component))

        assertFalse(fake.launch(component, personalUser))
        assertFalse(fake.launch(item(component, personalUser))) // AppItem overload too
        assertEquals(2, fake.launchCount) // attempts are still recorded
    }

    @Test
    fun fakeRecordsCorrectWorkProfileUser() {
        val component = ComponentName("com.example.work", "com.example.work.Main")
        val fake = FakeLauncherInvoker()

        fake.launch(component, workUser)

        assertEquals(1, fake.launchCount)
        assertEquals(workUser, fake.lastLaunch?.user)
        assertEquals(component, fake.lastLaunch?.component)
        // Must NOT have been recorded under the personal profile.
        assertFalse(fake.launches.any { it.user == personalUser })
    }

    @Test
    fun fakePreservesDistinctProfilesForSameComponent() {
        // Same app, two profiles — identity is (component, user), so both must be recorded.
        val component = ComponentName("com.example", "com.example.Main")
        val fake = FakeLauncherInvoker()

        fake.launch(component, personalUser)
        fake.launch(component, workUser)

        assertEquals(2, fake.launchCount)
        assertEquals(personalUser, fake.launches[0].user)
        assertEquals(workUser, fake.launches[1].user)
    }

    @Test
    fun fakeAppItemOverloadDispatchesExactIdentity() {
        val component = ComponentName("com.example", "com.example.Main")
        val app = item(component, workUser)
        val fake = FakeLauncherInvoker()

        fake.launch(app)

        assertTrue(fake.wasLaunched(app))
        assertEquals(component, fake.lastLaunch?.component)
        assertEquals(workUser, fake.lastLaunch?.user)
    }

    @Test
    fun fakeResetClearsRecordedLaunches() {
        val fake = FakeLauncherInvoker()
        fake.launch(ComponentName("a", "a.A"), personalUser)

        fake.reset()

        assertEquals(0, fake.launchCount)
        assertNull(fake.lastLaunch)
    }
}
