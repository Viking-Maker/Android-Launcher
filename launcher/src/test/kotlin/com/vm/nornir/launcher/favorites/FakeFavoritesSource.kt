package com.vm.nornir.launcher.favorites

import android.content.ComponentName
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.vm.nornir.launcher.usage.FakePersistence

/**
 * Test fake for [FavoritesSource] (issue #15).
 *
 * The real [DataStoreFavoritesSource] over an in-memory DataStore ([FakePersistence]) — the
 * acceptance criterion "fake implementations run over an in-memory DataStore" is met by
 * exercising the identical production code path, not by reimplementing persistence semantics.
 */
class FakeFavoritesSource(dataStore: DataStore<Preferences>) : FavoritesSource {
    private val delegate = DataStoreFavoritesSource(dataStore)

    override val favorites = delegate.favorites
    override val hasFavorites: Boolean get() = delegate.hasFavorites
    override suspend fun addFavorite(component: ComponentName) = delegate.addFavorite(component)
    override suspend fun removeFavorite(component: ComponentName) = delegate.removeFavorite(component)
}
