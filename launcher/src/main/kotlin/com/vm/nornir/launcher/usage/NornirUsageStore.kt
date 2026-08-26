package com.vm.nornir.launcher.usage

import android.content.ComponentName
import android.os.UserHandle
import kotlinx.coroutines.flow.Flow

/**
 * Self-tracked usage persistence seam (ADR-0006, issue #15).
 *
 * Persists [UsageRecord] aggregates keyed by the catalog identity
 * `(ComponentName, UserHandle)` (ADR-0003), so records join the catalog cleanly and are
 * multi-profile-correct by construction. Self-tracked only — **no** [android.app.usage.UsageStatsManager]
 * (D1: Nornir is the HOME launcher, so its own launch seam is the dominant share of
 * deliberate launches, permission-free and fully local).
 *
 * Write point: `LauncherViewModel` calls [recordLaunch] on each launch that
 * [com.vm.nornir.launcher.launch.LauncherInvoker] reports as started — VM-side per issue
 * #18's AC, success-conditional per D6 (resolved by #31; a failed launch leaves no trace).
 * Reads feed the D3/D5 frequent-first ordering; `FrequentSource` (companion read seam)
 * computes the top-N on top of this store.
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

    /**
     * Cold flow of all stored records keyed by component (profiles folded together by
     * max count / latest timestamp — the grid ranks components, not identities; the
     * ADR-0006 D3/D5 read path). Re-emits on every successful-launch write, which is what
     * keeps frequent-first ordering live (#20).
     */
    fun records(): Flow<Map<ComponentName, UsageRecord>>
}
