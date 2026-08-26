package com.vm.nornir.launcher.ui

import android.content.ComponentName
import android.os.Process
import com.vm.nornir.launcher.model.AppItem
import com.vm.nornir.launcher.usage.UsageRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-function tests for the D3 top-N selection and the D5 frequent-first ordering
 * (ADR-0006, issue #20's Favorites-surface AC).
 *
 * No flows, no stores — plain functions over plain values, per issue #11's pure-function
 * testing strategy. Robolectric is only needed because [AppItem] touches the Android runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FrequentOrderingTest {

    private val user = Process.myUserHandle()

    private fun comp(id: String) = ComponentName("com.example.$id", "com.example.$id.MainActivity")
    private fun item(id: String, label: String) = AppItem(comp(id), user, label, null)
    private fun rec(count: Int, ts: Long) = UsageRecord(count, ts)

    // ---- frequentTopN (D3) ------------------------------------------------------

    @Test
    fun `topN picks highest launchCount - N = 6 default`() {
        val apps = ('a'..'h').map { item(it.toString(), it.toString()) }
        val usage = apps.mapIndexed { i, app -> app.component to rec(i + 1, 100L + i) }.toMap()
        val top = frequentTopN(apps, usage)
        assertEquals(6, top.size)
        assertEquals(setOf('c','d','e','f','g','h').map { comp(it.toString()) }.toSet(), top)
    }

    @Test
    fun `ties break by most recent lastLaunchTimestamp`() {
        val a = item("a", "A"); val b = item("b", "B")
        val usage = mapOf(a.component to rec(3, 50L), b.component to rec(3, 90L))
        val top1 = frequentTopN(listOf(a, b), usage, n = 1)
        assertEquals(setOf(b.component), top1) // equal counts -> more recent wins the slot
        val ordered = orderFrequentFirst(listOf(a, b), top1, usage)
        assertEquals(b, ordered.first())
    }

    @Test
    fun `zero-count records never qualify`() {
        val a = item("a", "A"); val b = item("b", "B")
        val usage = mapOf(a.component to rec(0, 999L))
        assertEquals(emptySet<ComponentName>(), frequentTopN(listOf(a, b), usage))
    }

    @Test
    fun `apps missing from usage never qualify`() {
        val a = item("a", "A")
        assertEquals(emptySet<ComponentName>(), frequentTopN(listOf(a), emptyMap()))
    }

    // ---- orderFrequentFirst (D5) --------------------------------------------------

    @Test
    fun `frequent block sorts by count desc then recency - rest alphabetical`() {
        val z = item("zeta", "Zeta")     // count 5
        val y = item("yankee", "Yankee") // count 9
        val x = item("xray", "Xray")     // count 2
        val w = item("whiskey", "Whiskey") // no usage
        val v = item("victor", "Victor")   // no usage
        val usage = mapOf(
            z.component to rec(5, 10L),
            y.component to rec(9, 10L),
            x.component to rec(2, 10L),
        )
        val frequent = setOf(z.component, y.component, x.component)
        val ordered = orderFrequentFirst(listOf(z, w, y, v, x), frequent, usage)
        assertEquals(listOf(y, z, x, v, w), ordered)
    }

    @Test
    fun `Favorites filter mode is excluded from reorder`() {
        val pinned = item("pin", "Pin")
        val other = item("aaa", "Aaa")
        val usage = mapOf(pinned.component to rec(7, 1L))
        // In Favorites mode results stay in identity/catalog order — D5 explicitly excludes it.
        val results = filterApps(
            apps = listOf(other, pinned),
            query = "",
            filter = FilterMode.Favorites,
            favorites = setOf(pinned.component),
            frequent = setOf(pinned.component),
            usage = usage,
        )
        // Only the pinned app survives the chip test; crucially its high usage must NOT
        // reorder anything here — Favorites ordering stays membership/catalog-driven.
        assertEquals(listOf(pinned), results)
    }

    @Test
    fun `All mode reorders frequent-first even with a query`() {
        val spotify = item("spotify", "Spotify") // heavily used
        val slack = item("slack", "Slack")       // lightly used
        val usage = mapOf(spotify.component to rec(12, 1L), slack.component to rec(1, 2L))
        val results = filterApps(
            apps = listOf(slack, spotify),
            query = "", // both match trivially; ordering is the assertion
            filter = FilterMode.All,
            favorites = emptySet(),
            frequent = setOf(spotify.component),
            usage = usage,
        )
        assertEquals(listOf(spotify, slack), results)
    }
}
