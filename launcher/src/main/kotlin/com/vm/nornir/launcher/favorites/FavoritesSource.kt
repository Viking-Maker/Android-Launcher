package com.vm.nornir.launcher.favorites

import android.content.ComponentName
import android.os.UserHandle
import kotlinx.coroutines.flow.StateFlow

/**
 * Favorites read seam — the explicit, user-owned pin set (ADR-0004 §4, issue #15).
 *
 * The Pin chip (`FilterMode.Favorites`) is shown iff `hasFavorites`; rows read favorite
 * membership from [favorites]. Favorites and frequency are two separate dimensions
 * (ADR-0006 D4): this set is **never** mutated or reordered by the usage signal.
 *
 * Consumers depend only on this contract, never on the storage internals.
 */
interface FavoritesSource {

    /**
     * The pinned components as a hot, observable state. Identity is the catalog
     * [ComponentName] per ADR-0003 (pin state is per-package-level component; the
     * multi-profile dimension stays on `AppItem.user` / usage records).
     */
    val favorites: StateFlow<Set<ComponentName>>

    /** True if at least one app is pinned (drives Pin-chip visibility). */
    val hasFavorites: Boolean get() = favorites.value.isNotEmpty()

    /**
     * Persist [component] as pinned. No-op semantics are allowed for an already-pinned
     * identity; the set must not lose other members.
     */
    suspend fun addFavorite(component: ComponentName)

    /**
     * Remove [component] from the pinned set. Removing a non-member is a no-op;
     * the rest of the set survives untouched.
     */
    suspend fun removeFavorite(component: ComponentName)
}
