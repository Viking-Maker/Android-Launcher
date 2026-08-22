package com.vm.nornir.launcher.icon

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seam tests for [FakeIconLoader] (issue #16).
 *
 * The acceptance criterion "a fake/test implementation is available for unit tests (no
 * device needed)" — this suite runs the fake on Robolectric with no `LauncherApps`
 * binder, asserting it records exact identities and serves deterministic drawables.
 * The fake is what #18/#19's ViewModel/UI tests will inject.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FakeIconLoaderTest {

    private val fake = FakeIconLoader()

    private val personalUser: UserHandle get() = Process.myUserHandle()

    /** A real, distinct work-profile handle (userId 10), mirroring LauncherInvokerTest. */
    private val workUser: UserHandle get() = UserHandle.getUserHandleForUid(10 * 100_000 + 10_100)

    private fun component(cls: String) = ComponentName("com.example.app", "com.example.app.$cls")

    @Test
    fun `fake records the exact component user and density requested`() {
        val comp = component("Main")

        val icon = fake.get(comp, workUser, 420)

        assertNotNull(icon)
        assertEquals(1, fake.requestCount)
        val request = fake.lastRequest
        assertEquals(comp, request?.component)
        assertEquals(workUser, request?.user)
        assertEquals(420, request?.density)
    }

    @Test
    fun `fake keeps distinct profiles and densities apart`() {
        val comp = component("Main")

        fake.get(comp, personalUser, 160)
        fake.get(comp, workUser, 160)
        fake.get(comp, personalUser, 560)

        assertEquals(3, fake.requestCount)
        assertTrue(fake.wasRequested(comp, personalUser, 160))
        assertTrue(fake.wasRequested(comp, workUser, 160))
        assertTrue(fake.wasRequested(comp, personalUser, 560))
        assertFalse(fake.wasRequested(component("Other"), personalUser, 160))
    }

    @Test
    fun `fake serves a fresh drawable per call - same instance implies a real cache layer`() {
        val comp = component("Mail")
        val density = 480

        val first = fake.get(comp, personalUser, density)
        val second = fake.get(comp, personalUser, density)

        assertNotNull(first)
        assertNotNull(second)
        // The fake itself has NO cache: two calls mint two instances. A test observing
        // referential equality across gets knows LruIconCache served the second one.
        assertFalse(first === second)
    }

    @Test
    fun `fake override returns the forced drawable`() {
        val comp = component("Pinned")
        val drawable = android.graphics.drawable.ColorDrawable(0xFF112233.toInt())

        fake.put(comp, workUser, 320, drawable)

        assertSame(drawable, fake.get(comp, workUser, 320))
    }

    @Test
    fun `fake removed identity resolves to null like a stale entry`() {
        val gone = component("Gone")
        fake.remove(gone)

        assertNull(fake.get(gone, personalUser, 160))
        assertNotNull(fake.get(component("Alive"), personalUser, 160))
    }

    @Test
    fun `fake reset clears recorded requests only`() {
        val comp = component("Main")
        fake.get(comp, personalUser, 160)

        fake.reset()

        assertEquals(0, fake.requestCount)
        assertNull(fake.lastRequest)
        // Overrides survive reset (they are configuration, not history).
        val drawable = android.graphics.drawable.ColorDrawable(0xFF445566.toInt())
        fake.put(comp, personalUser, 160, drawable)
        assertSame(drawable, fake.get(comp, personalUser, 160))
    }
}
