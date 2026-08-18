package com.vm.nornir.launcher.model

import android.content.ComponentName
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [AppItem] as the immutable record (ADR-0003 §1) and confirms [AppItem.category]
 * is the *only* mapping site — it delegates to [mapPlatformToNornir], normalizing a
 * null [AppItem.platformCategory] to UNDEFINED (-1) and using [android.os.Build.VERSION.SDK_INT].
 *
 * Runs on the Android runtime (Robolectric) because [AppItem] holds a [UserHandle] and reads
 * [android.os.Build.VERSION.SDK_INT]; the pure mapping branches themselves are covered by
 * [CategoryMappingTest] on plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppItemTest {

    private fun item(category: Int?): AppItem = AppItem(
        component = ComponentName("com.example", "com.example.Activity"),
        user = Process.myUserHandle(),
        rawLabel = "Example",
        platformCategory = category,
    )

    @Test fun nullCategoryIsUndefinedAndFallsToOther() {
        assertEquals(NornirCategory.OTHER, item(null).category)
    }

    @Test fun nonNullZeroIsGameNotOther() {
        // CATEGORY_GAME == 0; must not collide with UNDEFINED (-1).
        assertEquals(NornirCategory.GAME, item(0).category)
    }

    @Test fun multimediaFoldsThroughAppItem() {
        assertEquals(NornirCategory.MULTIMEDIA, item(1).category)
        assertEquals(NornirCategory.MULTIMEDIA, item(2).category)
        assertEquals(NornirCategory.MULTIMEDIA, item(3).category)
    }

    @Test fun directMapsThroughAppItem() {
        assertEquals(NornirCategory.SOCIAL, item(4).category)
        assertEquals(NornirCategory.NEWS, item(5).category)
        assertEquals(NornirCategory.MAPS, item(6).category)
        assertEquals(NornirCategory.PRODUCTIVITY, item(7).category)
    }

    @Test fun accessibilityMappedOnApi36() {
        // @Config(sdk = [36]) => Build.VERSION.SDK_INT == 36 >= 31.
        assertEquals(NornirCategory.ACCESSIBILITY, item(8).category)
    }

    @Test fun dataClassIsValueBased() {
        val a = item(5)
        assertEquals(a, a.copy())
        assertEquals(a.component, a.copy(rawLabel = "Renamed").component)
        assertEquals(a.category, a.copy(rawLabel = "Renamed").category)
    }
}
