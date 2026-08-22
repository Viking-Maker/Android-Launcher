package com.vm.nornir.launcher.icon

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dispatch test for [RealIconLoader] (issue #16, ADR-0003 §4).
 *
 * Robolectric has no bound `LauncherApps` binder and no shadowed
 * `LauncherActivityInfo`, so the real badged-icon fetch cannot produce drawables here —
 * that path is exercised on-device (ADR-0007: physical device via adb is the sign-off
 * target). What this suite *can* assert on the JVM is the wiring contract, mirroring
 * [com.vm.nornir.launcher.launch.RealLauncherInvokerTest]:
 *
 *  - the off-main guard fires before any system service is touched;
 *  - an unbound `LauncherApps` service resolves to `null`, not a crash;
 *  - a resolvable-but-unmatched identity (unknown package) also yields `null`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RealIconLoaderTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val personalUser: UserHandle get() = Process.myUserHandle()

    @Test
    fun mainThreadCallThrowsBeforeTouchingSystemServices() {
        // The other tests lift the guard globally; this one re-arms it so the off-main
        // contract is actually exercised (the only place that must fire on the JVM).
        RealIconLoader.ALLOW_MAIN_THREAD_FOR_TESTS = false
        var thrown: Throwable? = null
        val mainLooper = android.os.Looper.getMainLooper()
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(mainLooper).post {
            try {
                RealIconLoader(context).get(ComponentName("com.example", ".Main"), personalUser, 160)
            } catch (t: IllegalStateException) {
                thrown = t // expected
            } finally {
                latch.countDown()
            }
        }
        org.robolectric.Shadows.shadowOf(mainLooper).idle()
        latch.await()
        if (thrown == null) throw AssertionError("expected IllegalStateException on the main thread")
    }

    @Test
    fun unboundLauncherAppsServiceResolvesToNullWithoutCrashing() {
        // Robolectric's application context has a LauncherApps whose getActivityList
        // returns nothing bound; either way the contract is "null, never crash".
        val loader = RealIconLoader(context)
        val result = loader.get(ComponentName("com.example", "com.example.Main"), personalUser, 160)
        assertNull(result)
    }

    @Test
    fun unknownPackageResolvesToNullWithoutCrashing() {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        // If the sandbox binds a service that throws for foreign packages, the seam must
        // still swallow it into null (the catch-all failure contract).
        org.junit.Assume.assumeTrue(launcherApps != null)
        val loader = RealIconLoader(context)
        val result = loader.get(
            ComponentName("com.definitely.not.installed", "com.definitely.not.installed.Main"),
            personalUser,
            160,
        )
        assertNull(result)
    }
}
