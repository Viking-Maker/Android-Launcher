package com.vm.nornir.launcher.catalog

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import com.vm.nornir.launcher.MainActivity
import com.vm.nornir.launcher.model.AppItem
import com.vm.nornir.launcher.model.NornirCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowResolveInfo
import org.robolectric.util.ReflectionHelpers

/**
 * Seam tests for [RealAppRepository] (issue #17).
 *
 * These run on Robolectric because [RealAppRepository] drives `LauncherApps` /
 * `LauncherActivityInfo`, which need the Android runtime and a seeded package list. The
 * shadow `LauncherApps` exposes `addActivity(user, info)` + `notifyPackageAdded` (which
 * posts `Callback.onPackageAdded` to the registered handler), so we exercise the real
 * enumeration + live-callback path with no device.
 *
 * Acceptance assertions mirrored from the fake contract plus the production-only ones:
 *  - **enumerates** every launchable activity across all visible profiles into [AppItem]s;
 *  - **multi-profile**: a work-profile user's apps appear under their own [UserHandle];
 *  - **category** flows through [AppItem.category] (the single mapping site);
 *  - **self-exclusion**: the launcher's own home activity is never listed in the host
 *    user, while a work-profile clone of the same package survives (ADR-0003 §3);
 *  - **fallback**: an unbound `LauncherApps` falls back to a `PackageManager`
 *    `MAIN`/`LAUNCHER` query for the host user (ADR-0003 §1) instead of crashing;
 *  - **live refresh**: `notifyPackageAdded` triggers a callback that rebuilds the catalog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RealAppRepositoryTest {

    private val personalUser: UserHandle = Process.myUserHandle()
    private val workUser: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000 + 10_100)

    private lateinit var context: Context
    private lateinit var launcherApps: LauncherApps
    private lateinit var shadowLauncherApps: org.robolectric.shadows.ShadowLauncherApps

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcherApps = context.getSystemService(LauncherApps::class.java)!!
        shadowLauncherApps = shadowOf(launcherApps)
        shadowOf(context.getSystemService(android.os.UserManager::class.java))
            .addUserProfile(personalUser)
    }

    /**
     * Build a [LauncherActivityInfo] via the package-private ctor the shadow layout needs.
     *
     * `LauncherActivityInfoInternal` / `IncrementalStatesInfo` are hidden from the compile
     * stub, so we construct them reflectively at runtime (where the instrumented runtime jar
     * exposes them) and never name them at compile time.
     */
    private fun makeActivity(
        packageName: String,
        className: String,
        user: UserHandle,
        label: String,
        category: Int,
    ): LauncherActivityInfo {
        val activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = className
            this.applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.category = category
            }
            nonLocalizedLabel = label
        }
        val cl = activityInfo.javaClass.classLoader!!
        val internal = ReflectionHelpers.callConstructor(
            cl.loadClass("android.content.pm.LauncherActivityInfoInternal"),
            ReflectionHelpers.ClassParameter.from(ActivityInfo::class.java, activityInfo),
            ReflectionHelpers.ClassParameter.from(
                cl.loadClass("android.content.pm.IncrementalStatesInfo"),
                ReflectionHelpers.callConstructor(
                    cl.loadClass("android.content.pm.IncrementalStatesInfo"),
                    ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
                    ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, 0f),
                    ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType!!, 0L),
                ),
            ),
            ReflectionHelpers.ClassParameter.from(UserHandle::class.java, user),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
        )
        return ReflectionHelpers.callConstructor(
            LauncherActivityInfo::class.java,
            ReflectionHelpers.ClassParameter.from(android.content.Context::class.java, context),
            ReflectionHelpers.ClassParameter.from(cl.loadClass("android.content.pm.LauncherActivityInfoInternal"), internal),
        )
    }

    @Test
    fun `enumerates launchable activities into AppItems`() = runTest {
        val mail = makeActivity("com.example.mail", "com.example.mail.Main", personalUser, "Mail", ApplicationInfo.CATEGORY_UNDEFINED)
        val maps = makeActivity("com.example.maps", "com.example.maps.Main", personalUser, "Maps", 6)
        shadowLauncherApps.addActivity(personalUser, mail)
        shadowLauncherApps.addActivity(personalUser, maps)

        val repo = RealAppRepository(context, UnconfinedTestDispatcher())
        repo.load()
        advanceUntilIdle()

        val items = repo.apps.value
        assertEquals(2, items.size)
        assertEquals(
            setOf(
                ComponentName("com.example.mail", "com.example.mail.Main"),
                ComponentName("com.example.maps", "com.example.maps.Main"),
            ),
            items.map { it.component }.toSet(),
        )
        assertEquals(personalUser, items.first().user)
        // ApplicationInfo.category 6 (MAPS) maps to NornirCategory.MAPS.
        val mapsItem = items.first { it.component == maps.componentName }
        assertEquals(NornirCategory.MAPS, mapsItem.category)
    }

    @Test
    fun `multi-profile apps appear under their own UserHandle`() = runTest {
        shadowOf(context.getSystemService(android.os.UserManager::class.java)).addUserProfile(workUser)
        shadowLauncherApps.addActivity(
            workUser,
            makeActivity("com.example.mail", "com.example.mail.Main", workUser, "Mail", ApplicationInfo.CATEGORY_UNDEFINED),
        )

        val repo = RealAppRepository(context, UnconfinedTestDispatcher())
        repo.load()
        advanceUntilIdle()

        val items = repo.apps.value
        assertEquals(1, items.size)
        assertEquals(workUser, items.first().user)
        assertEquals(ComponentName("com.example.mail", "com.example.mail.Main"), items.first().component)
    }

    @Test
    fun `unbound LauncherApps yields an empty catalog without crashing`() = runTest {
        val nullLauncherContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.LAUNCHER_APPS_SERVICE) null else super.getSystemService(name)
        }
        val safeRepo = RealAppRepository(nullLauncherContext, UnconfinedTestDispatcher())
        safeRepo.load()
        advanceUntilIdle()

        assertTrue(safeRepo.apps.value.isEmpty())
    }

    @Test
    fun `live callback rebuilds the catalog on package added`() = runTest {
        val repo = RealAppRepository(context, UnconfinedTestDispatcher())
        repo.load()
        advanceUntilIdle()
        assertTrue(repo.apps.value.isEmpty())

        val chat = makeActivity("com.example.chat", "com.example.chat.Main", personalUser, "Chat", ApplicationInfo.CATEGORY_UNDEFINED)
        shadowLauncherApps.addActivity(personalUser, chat)
        // The shadow posts Callback.onPackageAdded to the main Looper's Handler; Robolectric
        // starts that Looper paused, so idle it to deliver the callback.
        shadowLauncherApps.notifyPackageAdded("com.example.chat")
        // The shadow posts Callback.onPackageAdded to the registered handler (the main
        // Looper here); Robolectric starts that Looper paused, so idle it to deliver.
        shadowOf(Looper.getMainLooper()).idle()

        advanceUntilIdle()
        assertEquals(1, repo.apps.value.size)
        assertEquals(chat.componentName, repo.apps.value.first().component)
    }

    @Test
    fun `launcher itself is never listed in the host user`() = runTest {
        // Seed the launcher's own home activity exactly as the OS would report it.
        val self = makeActivity(
            context.packageName,
            MainActivity::class.java.name,
            personalUser,
            "Nornir",
            ApplicationInfo.CATEGORY_UNDEFINED,
        )
        shadowLauncherApps.addActivity(personalUser, self)

        val repo = RealAppRepository(context, UnconfinedTestDispatcher())
        repo.load()
        advanceUntilIdle()

        assertTrue(repo.apps.value.isEmpty())

        // A work-profile clone of the same package must survive (ADR-0003 §3: exclusion
        // is scoped to the host user + home component, never a blanket packageName drop).
        shadowOf(context.getSystemService(android.os.UserManager::class.java)).addUserProfile(workUser)
        val workClone = makeActivity(
            context.packageName,
            MainActivity::class.java.name,
            workUser,
            "Nornir",
            ApplicationInfo.CATEGORY_UNDEFINED,
        )
        shadowLauncherApps.addActivity(workUser, workClone)
        repo.load()
        advanceUntilIdle()

        assertEquals(listOf(workUser), repo.apps.value.map { it.user })
    }

    @Test
    fun `unbound LauncherApps falls back to PackageManager MAIN-LAUNCHER query`() = runTest {
        // Seed the PM resolve table with two launchable apps; the fallback path should
        // surface both under the calling (host) user.
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val mail = ShadowResolveInfo.newResolveInfo("com.example.mail", "com.example.mail.Main", "com.example.mail.Main")
        mail.activityInfo.applicationInfo = ApplicationInfo().apply { packageName = "com.example.mail" }
        // The label lives on the ResolveInfo itself (loadLabel consults it first).
        mail.nonLocalizedLabel = "Mail"
        val maps = ShadowResolveInfo.newResolveInfo("com.example.maps", "com.example.maps.Main", "com.example.maps.Main")
        maps.activityInfo.applicationInfo = ApplicationInfo().apply {
            packageName = "com.example.maps"
            category = 6 // ApplicationInfo.CATEGORY_MAPS
        }
        maps.nonLocalizedLabel = "Maps"
        shadowOf(pm).addResolveInfoForIntent(query, listOf(mail, maps))

        val nullLauncherContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.LAUNCHER_APPS_SERVICE) null else super.getSystemService(name)
        }
        val safeRepo = RealAppRepository(nullLauncherContext, UnconfinedTestDispatcher())
        safeRepo.load()
        advanceUntilIdle()

        val items = safeRepo.apps.value
        assertEquals(2, items.size)
        assertEquals(
            setOf(
                ComponentName("com.example.mail", "com.example.mail.Main"),
                ComponentName("com.example.maps", "com.example.maps.Main"),
            ),
            items.map { it.component }.toSet(),
        )
        assertEquals(setOf(Process.myUserHandle()), items.map { it.user }.toSet())
        assertEquals(NornirCategory.MAPS, items.first { it.rawLabel == "Maps" }.category)
    }
}
