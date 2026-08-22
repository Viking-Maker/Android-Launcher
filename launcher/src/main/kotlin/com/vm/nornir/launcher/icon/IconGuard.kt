package com.vm.nornir.launcher.icon

import android.os.Looper

/**
 * Off-main guard shared by every production [IconLoader] implementation (ADR-0003 §4).
 *
 * Cross-APK icon inflation is a binder call plus resource reads (T2 §5.2); calling it on
 * the main thread janks the frame, so implementations throw rather than comply. Robolectric
 * runs each test *on* a thread named "main" (its own JVM main thread with a paused
 * `Looper`), so tests opt out via [RealIconLoader.ALLOW_MAIN_THREAD_FOR_TESTS];
 * production never sets it.
 */
internal fun enforceOffMainThread() {
    if (Looper.myLooper() == Looper.getMainLooper() && !RealIconLoader.ALLOW_MAIN_THREAD_FOR_TESTS) {
        throw IllegalStateException(
            "IconLoader.get must be called off the main thread (ADR-0003 §4); " +
                "cross-APK icon inflation janks the frame.",
        )
    }
}
