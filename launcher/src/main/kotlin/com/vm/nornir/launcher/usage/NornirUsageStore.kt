package com.vm.nornir.launcher.usage

import android.content.ComponentName
import android.os.UserHandle

/**
 * Self-tracked usage persistence seam (ADR-0006, issue #15).
 *
 * Persists [UsageRecord] aggregates keyed by the catalog identity
 * `(ComponentName, UserHandle)` (ADR-0003), so records join the catalog cleanly and are
 * multi-profile-correct by construction. Self-tracked only — **no** [android.app.usage.UsageStatsManager]
 * (D1: Nornir is the HOME launcher, so its own launch seam is the dominant share of
 * deliberate launches, permission-free and fully local).
 *
 * The single write point is the launch flow: `LauncherInvoker.launchApp` calls
 * [recordLaunch] on each successful Nornir launch (D6). Reads feed the D3/D5
 * frequent-first ordering; `FrequentSource` (companion read seam) computes the top-N
 * on top of this store.
 */
interface NornirUsageStore {

    /**
     * Record one deliberate launch of [component] for [user]: increment
     * `launchCount` and set `lastLaunchTimestamp = [nowMillis]`.
     *
     * Implementations must be safe to call from the main thread (persist off-main,
     * runBlocking on the store's own IO context — see [DataStoreNornirUsageStore]).
     */
    fun recordLaunch(component: ComponentName, user: UserHandle, nowMillis: Long = System.currentTimeMillis())

    /**
     * The persisted record for [component] under [user], or [UsageRecord]`()` (zero
     * counters) if this identity has never been launched — callers never see `null`.
     */
    fun usageFor(component: ComponentName, user: UserHandle): UsageRecord
}
