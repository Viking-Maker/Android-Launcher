package com.vm.nornir.launcher.icon

import android.content.ComponentName
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle

/**
 * Counting backing source for [LruIconCache] tests (issue #16).
 *
 * A minimal [IconLoader] that mints a deterministic [ColorDrawable] per
 * `(component, user, density)` and counts every fetch — so tests can assert exactly how
 * many times the cache went to the backing source (cache misses) versus served memory
 * (cache hits). Distinct densities mint distinct colors, which is what makes
 * density-keying observable.
 */
class CountingIconSource : IconLoader {

    /** Immutable record of one backing-source fetch. */
    data class Fetch(val component: ComponentName, val user: UserHandle, val density: Int)

    /** Every fetch observed, in order. */
    val fetches: List<Fetch> get() = _fetches.toList()

    private val _fetches = mutableListOf<Fetch>()

    /** Components whose icon "does not resolve" — `get` returns `null` for these. */
    private val missing = mutableSetOf<ComponentName>()

    override fun get(component: ComponentName, user: UserHandle, density: Int): Drawable? {
        _fetches.add(Fetch(component, user, density))
        if (component in missing) return null
        overrides[Triple(component, user, density)]?.let { return it }
        // Deterministic per-identity color: hue from component + user + density, so two
        // identities/densities are visually and referentially distinguishable.
        return ColorDrawable(identityColor(component, user, density))
    }

    /** Number of backing-source fetches (= LRU misses) so far. */
    val fetchCount: Int get() = _fetches.size

    /** Mark [component] as unresolvable; subsequent `get`s return `null`. */
    fun remove(component: ComponentName) {
        missing.add(component)
    }

    /** "Reinstall" a previously removed component so it resolves again. */
    fun restore(component: ComponentName) {
        missing.remove(component)
    }

    /** Force [drawable] to be served for this exact identity + density (e.g. adaptive). */
    fun put(component: ComponentName, user: UserHandle, density: Int, drawable: Drawable) {
        overrides[Triple(component, user, density)] = drawable
    }

    /** Reset recorded fetches (not the missing set). */
    fun reset() {
        _fetches.clear()
    }

    private val overrides = mutableMapOf<Triple<ComponentName, UserHandle, Int>, Drawable>()

    companion object {
        /** Stable pseudo-color for an identity, mixing component/user/density bits. */
        fun identityColor(component: ComponentName, user: UserHandle, density: Int): Int =
            0xFF000000.toInt() or (component.hashCode() * 31 + user.hashCode() * 7 + density) and 0x00FFFFFF
    }
}
