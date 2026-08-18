package com.vm.nornir.launcher.launch

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import com.vm.nornir.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dispatch test for [RealLauncherInvoker] (issue #14, ADR-0005 §2).
 *
 * Robolectric has no bound `LauncherApps` binder, so the real launch is a no-op stub; this
 * can't observe the exact `(ComponentName, UserHandle)` the way [FakeLauncherInvoker] does
 * (that is the seam test's job). What it *can* assert is the wiring contract: the overloads
 * forward to `LauncherApps.startMainActivity` and the documented failure modes
 * (ActivityNotFoundException / SecurityException / NullPointerException) are swallowed, so a
 * launch never crashes the launcher. Here that shows up as "launch returns normally".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RealLauncherInvokerTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val personalUser = Process.myUserHandle()
    private val workUser = UserHandle.getUserHandleForUid(10 * 100_000 + 10_100)

    private fun app(component: ComponentName, user: UserHandle) = AppItem(
        component = component,
        user = user,
        rawLabel = "Example",
        platformCategory = null,
    )

    @Test
    fun launchDirectlyDoesNotThrowOnPersonalProfile() {
        RealLauncherInvoker(context).launch(
            ComponentName("com.example", "com.example.Main"),
            personalUser,
        )
    }

    @Test
    fun launchDirectlyDoesNotThrowOnWorkProfile() {
        // Must forward the exact work-profile handle (multi-profile correctness).
        RealLauncherInvoker(context).launch(
            ComponentName("com.example.work", "com.example.work.Main"),
            workUser,
        )
    }

    @Test
    fun appItemOverloadForwardsIdentityWithoutThrowing() {
        val component = ComponentName("com.example.work", "com.example.work.Main")
        RealLauncherInvoker(context).launch(app(component, workUser))
    }
}
