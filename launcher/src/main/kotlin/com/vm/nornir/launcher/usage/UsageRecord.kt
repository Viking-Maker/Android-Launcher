package com.vm.nornir.launcher.usage

/**
 * Aggregate usage counters for one app identity (ADR-0006 D2).
 *
 * Exactly two scalars per `(ComponentName, UserHandle)` — every viable "frequent"
 * definition (top-N, recency, threshold) needs only these. No raw event log (D2:
 * unbounded growth + pruning is over-engineering for MVP); swapping in a Room event
 * log later behind the same seams is a non-breaking change.
 *
 * @param launchCount cumulative deliberate Nornir launches of this identity.
 * @param lastLaunchTimestamp [System.currentTimeMillis] at last launch; `0L` = never.
 */
data class UsageRecord(
    val launchCount: Int = 0,
    val lastLaunchTimestamp: Long = 0L,
) {
    /** True if this identity has at least one recorded Nornir launch. */
    val hasLaunches: Boolean get() = launchCount > 0

    /**
     * This record with [launchCount] incremented by one and [lastLaunchTimestamp]
     * set to [nowMillis] — the D2 write shape applied on each successful launch.
     */
    fun plusLaunch(nowMillis: Long): UsageRecord =
        copy(launchCount = launchCount + 1, lastLaunchTimestamp = nowMillis)
}
