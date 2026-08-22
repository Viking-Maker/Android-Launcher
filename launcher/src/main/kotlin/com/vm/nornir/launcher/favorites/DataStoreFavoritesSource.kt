package com.vm.nornir.launcher.favorites

import android.content.ComponentName
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Real [FavoritesSource] over Preferences [DataStore] (ADR-0004 §4 + ADR-0006 D4, issue #15).
 *
 * Storage shape: one boolean preference per pinned component, named
 * `"fav/" + component.flattenToString()`. Booleans (not a joined string) so the pin set
 * never needs delimiter escaping and removal is a single-key delete.
 *
 * [favorites] is seeded from the persisted set once at construction, then served from
 * memory — reads after that are lock-free, writes update memory first and persist on the
 * caller's coroutine. A write failure surfaces to the caller as the DataStore exception;
 * the in-memory state still reflects the user's intent for this session.
 */
class DataStoreFavoritesSource(
    private val dataStore: DataStore<Preferences>,
) : FavoritesSource {

    private val _favorites = MutableStateFlow<Set<ComponentName>>(emptySet())

    override val favorites: StateFlow<Set<ComponentName>> = _favorites.asStateFlow()

    init {
        // Seed the hot state from disk before any consumer observes it: the ViewModel
        // combines favorites into its UI state, which must not flash empty on process start.
        _favorites.value = readPersisted()
    }

    override val hasFavorites: Boolean
        get() = _favorites.value.isNotEmpty()

    override suspend fun addFavorite(component: ComponentName) {
        val updated = _favorites.value + component
        if (updated == _favorites.value) return // already pinned — skip the write
        dataStore.edit { prefs -> prefs[prefKey(component)] = true }
        _favorites.value = updated
    }

    override suspend fun removeFavorite(component: ComponentName) {
        val updated = _favorites.value - component
        if (updated == _favorites.value) return // not pinned — nothing to persist
        dataStore.edit { prefs -> prefs.remove(prefKey(component)) }
        _favorites.value = updated
    }

    private fun prefKey(component: ComponentName) =
        booleanPreferencesKey(prefKeyName(component))

    private fun readPersisted(): Set<ComponentName> =
        runBlocking { dataStore.data.first() }
            .asMap()
            .keys.filterIsInstance<Preferences.Key<Boolean>>()
            .filter { it.name.startsWith(PREFIX) && it.name.length > PREFIX.length }
            .map { ComponentName.unflattenFromString(it.name.substring(PREFIX.length)) }
            .filterNotNull()
            .toSet()

    companion object {
        /** Key prefix namespaces favorite entries away from other DataStore users. */
        const val PREFIX = "fav/"

        /**
         * The storage key *name* for one pinned component. Public so fakes and tests
         * construct identical keys (and future reconcile/prune can reuse it).
         */
        fun prefKeyName(component: ComponentName) = "$PREFIX${component.flattenToString()}"
    }
}
