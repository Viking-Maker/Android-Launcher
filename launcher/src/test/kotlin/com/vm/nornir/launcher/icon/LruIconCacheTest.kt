package com.vm.nornir.launcher.icon

import android.content.ComponentName
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import com.vm.nornir.launcher.model.AppItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seam tests for the icon cache (issue #16, ADR-0003 §4).
 *
 * The three acceptance assertions:
 *  1. **Off-main loading** — a call on the main thread throws [IllegalStateException]
 *     (fail fast at the boundary instead of silently janking); off-main calls are served.
 *  2. **Density-keyed cache hits** — the same `(component, user, density)` hits the LRU
 *     (one backing fetch, same instance returned); a different density is a distinct key
 *     that re-fetches; distinct users/components never collide.
 *  3. **Adaptive-icon handling** — an [AdaptiveIconDrawable] flows through the cache raw:
 *     same instance, layers intact (`foreground`/`background`), never flattened.
 *
 * These run on Robolectric because `ComponentName`/`UserHandle` need the Android runtime,
 * mirroring [com.vm.nornir.launcher.usage.NornirUsageStoreTest]. No device and no bound
 * `LauncherApps` binder are needed — the backing source is [CountingIconSource].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LruIconCacheTest {

    init {
        // Robolectric executes tests on its own JVM main thread; lift the off-main guard
        // for every test except the one that asserts the guard itself.
        RealIconLoader.ALLOW_MAIN_THREAD_FOR_TESTS = true
    }

    private val source = CountingIconSource()

    private fun cache(maxEntries: Int = 512) = LruIconCache(source, maxEntries = maxEntries)

    private val personalUser: UserHandle get() = Process.myUserHandle()

    /** A real, distinct work-profile handle (userId 10), mirroring LauncherInvokerTest. */
    private val workUser: UserHandle get() = UserHandle.getUserHandleForUid(10 * 100_000 + 10_100)

    private fun component(cls: String) = ComponentName("com.example.app", "com.example.app.$cls")

    private fun item(comp: ComponentName, user: UserHandle) = AppItem(
        component = comp,
        user = user,
        rawLabel = "Example",
        platformCategory = null,
    )

    // ------------------------------------------------------------------
    // 1. Off-main loading
    // ------------------------------------------------------------------

    @Test
    fun `get on the main thread throws - icons load strictly off-main`() {
        val cache = cache()
        RobolectricMainThread.runOnMain {
            try {
                RealIconLoader.ALLOW_MAIN_THREAD_FOR_TESTS = false
                cache.get(component("Main"), personalUser, densityDpi())
                throw AssertionError("expected IllegalStateException on the main thread")
            } catch (expected: IllegalStateException) {
                // Contract: fail fast instead of janking the frame.
            } finally {
                RealIconLoader.ALLOW_MAIN_THREAD_FOR_TESTS = true
            }
        }
    }

    @Test
    fun `get off-main serves the drawable without touching the main thread`() {
        val cache = cache()
        val comp = component("Main")
        val drawable = cache.get(comp, personalUser, densityDpi())
        assertEquals(1, source.fetchCount)
        assertTrue(drawable is ColorDrawable)
    }

    // ------------------------------------------------------------------
    // 2. Density-keyed cache hits
    // ------------------------------------------------------------------

    @Test
    fun `same identity and density hits the cache with exactly one backing fetch`() {
        val cache = cache()
        val comp = component("Mail")

        val first = cache.get(comp, personalUser, densityDpi())
        val second = cache.get(comp, personalUser, densityDpi())

        assertSame(first, second) // cached instance, not a re-minted drawable
        assertEquals(1, source.fetchCount)
    }

    @Test
    fun `different density is a different key and refetches`() {
        val cache = cache()
        val comp = component("Mail")

        val mdpi = cache.get(comp, personalUser, 160)
        val hdpi = cache.get(comp, personalUser, 240)

        assertNotEquals(mdpi, hdpi)
        assertEquals(2, source.fetchCount)

        // Both densities now live independently in the cache.
        assertSame(mdpi, cache.get(comp, personalUser, 160))
        assertSame(hdpi, cache.get(comp, personalUser, 240))
        assertEquals(2, source.fetchCount)
    }

    @Test
    fun `same component under two profiles keeps independent entries`() {
        val cache = cache()
        val comp = component("Mail")

        val personal = cache.get(comp, personalUser, densityDpi())
        val work = cache.get(comp, workUser, densityDpi())

        assertNotEquals(personal, work) // per-identity color from CountingIconSource
        assertEquals(2, source.fetchCount)
    }

    @Test
    fun `distinct components keep distinct entries`() {
        val cache = cache()
        val mail = component("Mail")
        val maps = component("Maps")

        cache.get(mail, personalUser, densityDpi())
        cache.get(maps, personalUser, densityDpi())

        assertEquals(2, source.fetchCount)
    }

    @Test
    fun `least recently used entry is evicted beyond maxEntries`() {
        val cache = cache(maxEntries = 2)
        val a = component("A")
        val b = component("B")
        val c = component("C")

        cache.get(a, personalUser, densityDpi())
        cache.get(b, personalUser, densityDpi())
        assertEquals(2, source.fetchCount)

        // Android's LruCache trims when size() > maxSize, so the 3rd put evicts the
        // oldest entry (a) immediately — not lazily on the next access.
        cache.get(c, personalUser, densityDpi())
        assertEquals(3, source.fetchCount)
        assertEquals(2, cache.entryCount)

        cache.get(a, personalUser, densityDpi()) // a was evicted -> refetch (evicts b)
        assertEquals(4, source.fetchCount)

        cache.get(b, personalUser, densityDpi()) // b was evicted above -> refetch
        assertEquals(5, source.fetchCount)
    }

    @Test
    fun `accessing an entry refreshes its recency before eviction`() {
        val cache = cache(maxEntries = 2)
        val a = component("A")
        val b = component("B")
        val c = component("C")

        val iconA = cache.get(a, personalUser, densityDpi())
        cache.get(b, personalUser, densityDpi()) // LRU order: a, b
        assertSame(iconA, cache.get(a, personalUser, densityDpi())) // touch a -> order: b, a
        assertEquals(2, source.fetchCount)

        cache.get(c, personalUser, densityDpi()) // evicts b (oldest), not a
        assertEquals(3, source.fetchCount)
        assertSame(iconA, cache.get(a, personalUser, densityDpi())) // a survives
        assertEquals(3, source.fetchCount)
    }

    @Test
    fun `trimMemory drops every cached entry`() {
        val cache = cache()
        val comp = component("Mail")

        val first = cache.get(comp, personalUser, densityDpi())
        cache.trimMemory()
        assertEquals(0, cache.entryCount)

        val afterTrim = cache.get(comp, personalUser, densityDpi())
        assertEquals(2, source.fetchCount)
        assertNotEquals(System.identityHashCode(first), System.identityHashCode(afterTrim))
    }

    // ------------------------------------------------------------------
    // 3. Adaptive-icon handling + failure contract
    // ------------------------------------------------------------------

    @Test
    fun `adaptive icon passes through the cache raw and unflattened`() {
        // Build a real AdaptiveIconDrawable (public two-layer constructor, API 26+).
        val foreground = ColorDrawable(0xFF00FF00.toInt())
        val background = ColorDrawable(0xFF0000FF.toInt())
        val adaptive = AdaptiveIconDrawable(background, foreground)
        // Inject it as the source's resolution for one identity + density.
        val comp = component("Adaptive")
        source.put(comp, personalUser, densityDpi(), adaptive)
        val cache = cache()

        val fetched = cache.get(comp, personalUser, densityDpi())

        assertSame(adaptive, fetched)
        assertTrue(fetched is AdaptiveIconDrawable)
        // Layers intact — the cache must not have flattened or wrapped it.
        assertSame(background, (fetched as AdaptiveIconDrawable).background)
        assertSame(foreground, fetched.foreground)

        // Second get: same raw adaptive instance straight from the cache.
        assertSame(adaptive, cache.get(comp, personalUser, densityDpi()))
        assertEquals(1, source.fetchCount)
    }

    @Test
    fun `unresolvable identity yields null and is not negatively cached`() {
        val cache = cache()
        val gone = component("Gone")
        source.remove(gone)

        assertNull(cache.get(gone, personalUser, densityDpi()))
        // Nulls must not poison the cache: once the app returns, it resolves normally.
        source.restore(gone)
        assertTrue(cache.get(gone, personalUser, densityDpi()) is ColorDrawable)
        assertEquals(2, source.fetchCount)
    }

    @Test
    fun `app-item overload dispatches exact identity`() {
        val cache = cache()
        val comp = component("Main")

        cache.get(item(comp, workUser), densityDpi())

        assertEquals(1, source.fetchCount)
        val fetch = source.fetches.single()
        assertEquals(comp, fetch.component)
        assertEquals(workUser, fetch.user)
        assertEquals(densityDpi(), fetch.density)
    }

    private fun densityDpi(): Int =
        ApplicationProvider.getApplicationContext<android.app.Application>()
            .resources.displayMetrics.densityDpi
}

/** Runs [block] on Robolectric's main looper thread to exercise the off-main guard. */
private object RobolectricMainThread {

    fun runOnMain(block: () -> Unit) {
        val mainLooper = Looper.getMainLooper()
        var thrown: Throwable? = null
        // Robolectric pauses the main looper between tests; post + idle runs block on it.
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(mainLooper).post {
            try {
                block()
            } catch (t: Throwable) {
                thrown = t
            } finally {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == mainLooper) {
            // Already on main (shouldn't happen in these tests) — run directly.
            block()
        } else {
            org.robolectric.Shadows.shadowOf(mainLooper).idle()
            latch.await()
        }
        thrown?.let { throw it }
    }
}
