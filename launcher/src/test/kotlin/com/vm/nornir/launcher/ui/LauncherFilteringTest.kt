package com.vm.nornir.launcher.ui

import android.content.ComponentName
import com.vm.nornir.launcher.model.AppItem
import com.vm.nornir.launcher.model.NornirCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure unit coverage for the filter/fuzzy matcher exposed by [LauncherFiltering] (ADR-0004 §8).
 *
 * These are total functions over plain values, so they run without the ViewModel combine
 * pipeline — fast, deterministic, and the canonical guard for the search semantics
 * (substring + subsequence + bounded edit distance + accent folding). The ViewModel test
 * ([LauncherViewModelTest]) covers the same logic end-to-end through the real fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherFilteringTest {

    private fun comp(id: String) = ComponentName("com.example.$id", "com.example.$id.MainActivity")
    private fun item(id: String, label: String, platformCategory: Int?) =
        AppItem(comp(id), android.os.Process.myUserHandle(), label, platformCategory)

    @Test
    fun `empty query returns every app`() {
        val apps = listOf(item("a", "Alpha", null), item("b", "Beta", 1))
        assertEquals(apps, filterApps(apps, "", FilterMode.All, emptySet()))
    }

    @Test
    fun `non-empty query does not search package name`() {
        val apps = listOf(item("spotify", "Music", 1)) // label lacks "spotify"
        assertTrue(filterApps(apps, "spotify", FilterMode.All, emptySet()).isEmpty())
    }

    @Test
    fun `substring match is case-insensitive`() {
        val apps = listOf(item("vscode", "VS Code", 7))
        assertEquals(1, filterApps(apps, "code", FilterMode.All, emptySet()).size)
        assertEquals(1, filterApps(apps, "VS", FilterMode.All, emptySet()).size)
    }

    @Test
    fun `subsequence matches partial typing`() {
        val apps = listOf(item("brave", "Brave", null))
        assertEquals(1, filterApps(apps, "brv", FilterMode.All, emptySet()).size)
        assertEquals(1, filterApps(apps, "bve", FilterMode.All, emptySet()).size) // non-contiguous
    }

    @Test
    fun `bounded edit distance matches a single-character typo`() {
        val apps = listOf(item("spotify", "Spotify", 1))
        // "sporify" is one substitution (r<->t) away from "spotify" -> distance 1 <= MAX_EDIT.
        assertEquals(1, filterApps(apps, "sporify", FilterMode.All, emptySet()).size)
    }

    @Test
    fun `accent-insensitive normalization - cafe matches Café`() {
        val apps = listOf(item("cafe", "Café", null))
        assertEquals(1, filterApps(apps, "cafe", FilterMode.All, emptySet()).size)
        assertEquals(1, filterApps(apps, "Café", FilterMode.All, emptySet()).size)
    }

    @Test
    fun `category display name is matchable`() {
        val apps = listOf(item("steam", "Steam", 0)) // GAME -> "Games"
        assertEquals(1, filterApps(apps, "games", FilterMode.All, emptySet()).size)
        assertEquals(1, filterApps(apps, "game", FilterMode.All, emptySet()).size)
    }

    @Test
    fun `category chip selects only that category - MULTIMEDIA folds media codes`() {
        val apps = listOf(
            item("spotify", "Spotify", 1), // MULTIMEDIA (1)
            item("vscode", "VS Code", 7),  // PRODUCTIVITY
        )
        assertEquals(
            listOf("Spotify"),
            filterApps(apps, "", FilterMode.Category(NornirCategory.MULTIMEDIA), emptySet()).map { it.rawLabel },
        )
    }

    @Test
    fun `Favorites is a union over pinned components`() {
        val mail = comp("mail")
        val apps = listOf(item("mail", "Mail", null), item("news", "News", 5), item("maps", "Maps", 6))
        val out = filterApps(apps, "", FilterMode.Favorites, setOf(mail))
        assertEquals(listOf("Mail"), out.map { it.rawLabel })
    }

    @Test
    fun `query AND favorites both apply`() {
        val mail = comp("mail")
        val apps = listOf(item("mail", "Mail", null), item("maps", "Maps", 6))
        val out = filterApps(apps, "map", FilterMode.Favorites, setOf(mail))
        assertTrue(out.isEmpty()) // "map" matches Maps, but Maps is not pinned
    }

    @Test
    fun `visibleCategories drops empty categories and keeps taxonomy order`() {
        val apps = listOf(item("steam", "Steam", 0), item("misc", "Misc", null), item("vscode", "VS Code", 7))
        assertEquals(
            listOf(NornirCategory.GAME, NornirCategory.PRODUCTIVITY, NornirCategory.OTHER),
            visibleCategories(apps),
        )
    }

    @Test
    fun `step clamps downward at zero and upward at lastIndex`() {
        assertEquals(0, step(0, FocusDir.UP, 3))
        assertEquals(2, step(2, FocusDir.DOWN, 3))
        assertEquals(0, step(0, FocusDir.UP, 0)) // no rows -> stays 0
    }

    @Test
    fun `norm folds accents and casing`() {
        assertEquals(norm("red"), norm("Réd"))
        assertEquals(norm("naive"), norm("Naïve"))
    }

    @Test
    fun `levenshtein edit distance is correct`() {
        assertEquals(0, levenshtein("spotify", "spotify"))
        assertEquals(1, levenshtein("sporify", "spotify")) // one substitution
        assertEquals(2, levenshtein("spoitfy", "spotify")) // adjacent transposition
        assertEquals(3, levenshtein("kitten", "sitting"))
        assertEquals(2, levenshtein("pl", "play")) // two insertions
    }
}
