package com.vm.nornir.launcher.catalog

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle
import com.vm.nornir.launcher.model.AppItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seam tests for [FakeAppRepository] (issue #17).
 *
 * The acceptance criterion is "a `FakeAppRepository` (in-memory `AppItem` list) is
 * available" — these tests pin the contract the UI seam (#18's `LauncherViewModel`)
 * will lean on, mirroring [com.vm.nornir.launcher.launch.FakeLauncherInvoker]:
 *
 *  - the in-memory list is **emitted** through `apps` (the catalog StateFlow);
 *  - `setApps` replaces the snapshot and observers see the new list;
 *  - the recorded `load()` call is observable (`loadCount`) and does not disturb
 *    the published snapshot;
 *  - `remove(component, user)` drops exactly one identity — the same component
 *    under a different profile survives;
 *  - `clear()` empties the catalog;
 *  - `reset()` returns the fake to its pristine empty state.
 *
 * These run on Robolectric because [Process.myUserHandle]/[UserHandle] need the Android
 * runtime (mirroring [com.vm.nornir.launcher.usage.NornirUsageStoreTest]); the fake itself
 * stays runtime-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FakeAppRepositoryTest {

    private val personalUser: UserHandle = Process.myUserHandle()
    private val workUser: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000 + 10_100)

    private fun app(short: String, user: UserHandle = personalUser): AppItem = AppItem(
        component = ComponentName("com.example.$short", "com.example.$short.MainActivity"),
        user = user,
        rawLabel = short.replaceFirstChar { it.uppercase() },
        platformCategory = null,
    )

    @Test
    fun `starts empty`() {
        val repo = FakeAppRepository()

        assertTrue(repo.apps.value.isEmpty())
        assertEquals(0, repo.loadCount)
    }

    @Test
    fun `seeded apps are emitted through the StateFlow`() {
        val mail = app("mail")
        val maps = app("maps")
        val repo = FakeAppRepository(mail, maps)

        assertEquals(listOf(mail, maps), repo.apps.value)
    }

    @Test
    fun `setApps replaces the emitted snapshot`() {
        val repo = FakeAppRepository(app("mail"))
        assertEquals(1, repo.apps.value.size)

        val next = listOf(app("maps"), app("chat"))
        repo.setApps(next)

        assertEquals(next, repo.apps.value)
    }

    @Test
    fun `add appends an identity without disturbing existing entries`() {
        val mail = app("mail")
        val repo = FakeAppRepository(mail)

        val workMail = app("mail", user = workUser)
        repo.add(workMail)

        assertEquals(listOf(mail, workMail), repo.apps.value)
    }

    @Test
    fun `remove drops exactly one identity`() {
        val mail = app("mail")
        val maps = app("maps")
        val workMail = app("mail", user = workUser)
        val repo = FakeAppRepository(mail, maps, workMail)

        repo.remove(mail.component, mail.user)

        assertEquals(listOf(maps, workMail), repo.apps.value)
    }

    @Test
    fun `remove of an unknown identity is a stable no-op`() {
        val mail = app("mail")
        val repo = FakeAppRepository(mail)

        repo.remove(ComponentName("com.example.ghost", "com.example.ghost.Main"), personalUser)

        assertEquals(listOf(mail), repo.apps.value)
    }

    @Test
    fun `clear empties the catalog`() {
        val repo = FakeAppRepository(app("mail"), app("maps"))

        repo.clear()

        assertTrue(repo.apps.value.isEmpty())
    }

    @Test
    fun `load records the call and keeps the snapshot`() = kotlinx.coroutines.test.runTest {
        val mail = app("mail")
        val repo = FakeAppRepository(mail)

        repo.load()

        assertEquals(1, repo.loadCount)
        assertEquals(listOf(mail), repo.apps.value)
    }

    @Test
    fun `reset returns the fake to its pristine state`() = kotlinx.coroutines.test.runTest {
        val repo = FakeAppRepository(app("mail"))
        repo.setApps(listOf(app("maps")))
        repo.load()

        repo.reset()

        assertTrue(repo.apps.value.isEmpty())
        assertEquals(0, repo.loadCount)
    }
}
