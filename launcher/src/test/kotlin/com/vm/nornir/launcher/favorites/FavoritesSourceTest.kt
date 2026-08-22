package com.vm.nornir.launcher.favorites

import android.content.ComponentName
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.vm.nornir.launcher.usage.FakePersistence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seam tests for the favorites pin set (issue #15, ADR-0004 §4 / ADR-0006 D4).
 *
 * The primary acceptance assertion: **the pin set survives a reload** — a fresh source
 * instance over the same persisted DataStore reads back exactly the pins the previous
 * instance wrote. Also covered: add/remove round-trips, `hasFavorites` driving Pin-chip
 * visibility, no-op re-add / remove-non-member semantics, and distinct-component identity.
 *
 * These run on Robolectric because [ComponentName] needs the Android runtime, mirroring
 * the usage-store seam tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FavoritesSourceTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private fun newDataStore(): DataStore<Preferences> = FakePersistence.inMemoryPrefsDataStore(scope)

    private fun comp(short: String) = ComponentName("com.example.app", "com.example.app.$short")

    @Test
    fun `pin survives a reload into a fresh FavoritesSource`() = runTest {
        val dataStore = newDataStore()
        val mail = comp("Mail")
        val maps = comp("Maps")

        // First instance: pin two apps.
        val first = DataStoreFavoritesSource(dataStore)
        assertTrue(first.favorites.value.isEmpty())
        first.addFavorite(mail)
        first.addFavorite(maps)
        assertEquals(setOf(mail, maps), first.favorites.value)
        assertTrue(first.hasFavorites)

        // A brand-new instance over the SAME DataStore — simulating process restart —
        // must read the persisted set, not start empty.
        val reloaded = DataStoreFavoritesSource(dataStore)
        assertEquals(setOf(mail, maps), reloaded.favorites.value)
        assertTrue(reloaded.hasFavorites)
    }

    @Test
    fun `unpin removes only the target and survives a reload`() = runTest {
        val dataStore = newDataStore()
        val mail = comp("Mail")
        val maps = comp("Maps")

        val first = DataStoreFavoritesSource(dataStore)
        first.addFavorite(mail)
        first.addFavorite(maps)
        first.removeFavorite(maps)

        assertEquals(setOf(mail), first.favorites.value)

        val reloaded = DataStoreFavoritesSource(dataStore)
        assertEquals(setOf(mail), reloaded.favorites.value)
        assertFalse(reloaded.favorites.value.contains(maps))
    }

    @Test
    fun `re-pinning an already pinned component is a stable no-op`() = runTest {
        val dataStore = newDataStore()
        val mail = comp("Mail")

        val source = DataStoreFavoritesSource(dataStore)
        source.addFavorite(mail)
        source.addFavorite(mail) // second write must not duplicate or throw

        assertEquals(setOf(mail), source.favorites.value)
    }

    @Test
    fun `removing a non-member leaves the rest of the set intact`() = runTest {
        val dataStore = newDataStore()
        val mail = comp("Mail")
        val never = comp("Never")

        val source = DataStoreFavoritesSource(dataStore)
        source.addFavorite(mail)
        source.removeFavorite(never) // not pinned — nothing to remove

        assertEquals(setOf(mail), source.favorites.value)
    }

    @Test
    fun `hasFavorites tracks emptiness of the pin set`() = runTest {
        val source = DataStoreFavoritesSource(newDataStore())
        val mail = comp("Mail")

        assertFalse(source.hasFavorites)
        source.addFavorite(mail)
        assertTrue(source.hasFavorites)
        source.removeFavorite(mail)
        assertFalse(source.hasFavorites)
    }

    @Test
    fun `distinct components are distinct pins`() = runTest {
        val source = DataStoreFavoritesSource(newDataStore())
        val mail = comp("Mail")
        val mailAlt = comp("MailAlt") // same package, different activity class

        source.addFavorite(mail)
        source.addFavorite(mailAlt)

        assertEquals(setOf(mail, mailAlt), source.favorites.value)
    }
}
