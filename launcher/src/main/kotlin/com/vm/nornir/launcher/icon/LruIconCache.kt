package com.vm.nornir.launcher.icon

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.UserHandle
import android.util.LruCache
import com.vm.nornir.launcher.model.AppItem

/**
 * In-memory LRU icon cache — the production [IconLoader] (ADR-0003 §4, issue #16).
 *
 * Wraps a backing [IconLoader] ([RealIconLoader] in the app: `LauncherActivityInfo
 * .getBadgedIcon(density)`) with an [LruCache] keyed by the triple
 * `(component, user, density)`:
 *
 *  - **density is part of the key** — a cached 160dpi drawable served at 560dpi would be
 *    blurry, so each density gets its own entry (issue #16 acceptance criterion).
 *  - **off-main enforcement** — both cache misses and hits are refused on the main thread;
 *    cross-APK resource inflation is a binder call + resource read (T2 §5.2) and even a
 *    "cheap" hit must not normalize main-thread icon access.
 *  - **no disk tier** — adaptive-icon `Drawable`s aren't cleanly serializable and refetch
 *    from the APK is cheap (ADR-0003); `trimMemory()` drops everything on
 *    `onTrimMemory(TRIM_MEMORY_*)` from the host activity.
 *  - **nulls are not cached** — an unresolvable identity (uninstalled/disabled entry)
 *    stays uncached so a reinstalled app resolves on its next `get`.
 *
 * @param source the backing loader consulted on a miss.
 * @param maxEntries LRU capacity; ~a few hundred entries per ADR-0003 (38dp icons are
 *   small — 512 entries ≈ a few MB).
 */
class LruIconCache(
    private val source: IconLoader,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : IconLoader {

    private val cache = object : LruCache<IconKey, Drawable>(maxEntries) {
        override fun create(key: IconKey): Drawable? = source.get(key.component, key.user, key.density)
    }

    init {
        check(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    override fun get(component: ComponentName, user: UserHandle, density: Int): Drawable? {
        checkNotMainThread()
        return cache.get(IconKey(component, user, density))
    }

    /** Convenience overload: fetch the icon for the exact identity carried by [app]. */
    fun get(app: AppItem, density: Int): Drawable? = get(app.component, app.user, density)

    /**
     * Drop every cached entry — wire to `onTrimMemory` in #20's activity so the launcher
     * releases icon memory under pressure. The next `get` refetches from [source].
     */
    fun trimMemory() {
        cache.evictAll()
    }

    /** Number of live entries (diagnostics/tests). */
    val entryCount: Int
        get() = cache.size()

    private fun checkNotMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper() && !RealIconLoader.ALLOW_MAIN_THREAD_FOR_TESTS) {
            throw IllegalStateException(
                "IconLoader.get must be called off the main thread (ADR-0003 §4); " +
                    "cross-APK icon inflation janks the frame.",
            )
        }
    }

    /** Identity for one cached drawable: full catalog identity + target density. */
    private data class IconKey(
        val component: ComponentName,
        val user: UserHandle,
        val density: Int,
    )

    companion object {
        /** A few hundred entries per ADR-0003; small 38dp drawables keep this cheap. */
        const val DEFAULT_MAX_ENTRIES = 512
    }
}
