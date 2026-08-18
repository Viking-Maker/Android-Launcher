package com.vm.nornir.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Contract tests for the Nornir home-launcher module (issue #12).
 *
 * These assert the externally observable behaviour of the module as seen by
 * Android's PackageManager: Nornir is selectable as the device default home,
 * and never self-lists in the app drawer.
 *
 * Robolectric parses the merged manifest (main + module) and exposes it through
 * PackageManager, so these assertions exercise the real registration pipeline
 * rather than re-reading raw XML. The activity-element filter shape (MAIN + HOME
 * + DEFAULT, no LAUNCHER) and the <queries> MAIN/LAUNCHER block are asserted
 * directly against the source manifest in [HomeLauncherManifestTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeLauncherContractTest {

    private val appContext
        get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val packageManager: PackageManager
        get() = appContext.packageManager

    private val packageName = "com.vm.nornir.launcher"

    /** Nornir MUST be resolvable as a HOME activity (selectable default home). */
    @Test
    fun homeActivity_isResolvableAsHome() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("No HOME activity resolved for $packageName", resolve)
        assertEquals(packageName, resolve!!.activityInfo.packageName)
        assertEquals("$packageName.MainActivity", resolve.activityInfo.name)
    }

    /**
     * Nornir MUST NOT self-list in the app drawer: it declares no MAIN+LAUNCHER
     * filter, so it never appears among launcher-queryable activities.
     */
    @Test
    fun homeActivity_isNotResolvableAsLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(intent, 0)
        val self = matches.firstOrNull { it.activityInfo.packageName == packageName }
        assertEquals(
            "Nornir must not appear as a LAUNCHER app in the drawer",
            null,
            self,
        )
    }

    /** Task/window contract for a keyboard-driven home launcher. */
    @Test
    fun mainActivity_hasCorrectTaskAndWindowAttributes() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("No HOME activity resolved for $packageName", resolve)
        val info = resolve!!.activityInfo
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertTrue(
            "clearTaskOnLaunch must be true for a home launcher",
            info.flags and ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH != 0,
        )
        assertTrue(
            "stateNotNeeded must be true for a home launcher",
            info.flags and ActivityInfo.FLAG_STATE_NOT_NEEDED != 0,
        )
        assertEquals(
            "windowSoftInputMode must be adjustResize",
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        assertTrue("activity must be exported", info.exported)
    }

    /** The placeholder must render without crashing and own a content view. */
    @Test
    fun placeholder_rendersAndOwnsContentView() {
        val controller: ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        assertNotNull("Activity must set a content view", activity.window.peekDecorView())
        controller.pause().stop().destroy()
    }
}
