package com.vm.nornir.launcher.icon

import android.content.ComponentName
import android.graphics.drawable.ColorDrawable
import android.os.UserHandle

/**
 * Test fake for [IconLoader] (issue #16).
 *
 * Records every `(component, user, density)` request and serves deterministic
 * [ColorDrawable]s — no device, no bound `LauncherApps` binder, no cross-APK inflation.
 * Mirrors [com.vm.nornir.launcher.launch.FakeLauncherInvoker]: the fake records the exact
 * identity the caller asked for so tests can assert the presentation boundary requests
 * the right `(component, user)` at the right density.
 *
 * Unlike [CountingIconSource] (which backs [LruIconCache] cache-miss tests), this fake
 * mints a **fresh** drawable per call — it has no cache of its own — so a test that sees
 * the same instance twice knows a real cache layer served it.
 */
class FakeIconLoader : IconLoader {

    /** Immutable record of one icon request observed by the fake. */
    data class Request(val component: ComponentName, val user: UserHandle, val density: Int)

    /** Every request observed, in order. Empty until the first [get]. */
    val requests: List<Request> get() = _requests.toList()

    private val _requests = mutableListOf<Request>()

    /** Components whose icon "does not resolve" — `get` returns `null` for these. */
    private val missing = mutableSetOf<ComponentName>()

    /** Explicit overrides for tests that need a specific drawable back. */
    private val overrides = mutableMapOf<Triple<ComponentName, UserHandle, Int>, ColorDrawable>()

    override fun get(component: ComponentName, user: UserHandle, density: Int): ColorDrawable? {
        _requests.add(Request(component, user, density))
        overrides[Triple(component, user, density)]?.let { return it }
        if (component in missing) return null
        return ColorDrawable(CountingIconSource.identityColor(component, user, density))
    }

    /** Number of requests observed so far. */
    val requestCount: Int get() = _requests.size

    /** The most recent request, or `null` if none has happened yet. */
    val lastRequest: Request? get() = _requests.lastOrNull()

    /** True if [component] under [user] at [density] was requested at least once. */
    fun wasRequested(component: ComponentName, user: UserHandle, density: Int): Boolean =
        _requests.any { it.component == component && it.user == user && it.density == density }

    /** Mark [component] as unresolvable; subsequent `get`s return `null`. */
    fun remove(component: ComponentName) {
        missing.add(component)
    }

    /** Force [drawable] to be served for this exact identity + density. */
    fun put(component: ComponentName, user: UserHandle, density: Int, drawable: ColorDrawable) {
        overrides[Triple(component, user, density)] = drawable
    }

    /** Reset recorded requests (not overrides/missing). */
    fun reset() {
        _requests.clear()
    }
}
