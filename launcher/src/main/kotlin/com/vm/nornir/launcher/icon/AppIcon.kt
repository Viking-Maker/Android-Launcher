package com.vm.nornir.launcher.icon

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.vm.nornir.launcher.model.AppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Presentation-boundary icon painter (ADR-0003 §4 / ADR-0004 §7, issue #19).
 *
 * `AppItem` carries no `Drawable` (ADR-0003); the icon is fetched here — and only here,
 * at the presentation boundary — through the [IconLoader] seam. The load runs **off the
 * main thread** via [produceState] + [Dispatchers.IO] (the seam's contract forbids main-
 * thread calls; cross-APK inflation is a binder call), keyed by the catalog identity plus
 * the display density so a cached entry is always crisp at this device's DPI.
 *
 * While loading (and for an unresolvable identity, `null`) a neutral surface placeholder
 * shows; the launcher never crashes or blocks on a stale catalog row.
 *
 * @param item the catalog row to paint.
 * @param loader the injected icon seam (real LRU cache in production, fake in tests).
 * @param densityDpi the target density (`DisplayMetrics.densityDpi`); `0` = platform default.
 */
@Composable
fun AppIcon(
    item: AppItem,
    loader: IconLoader,
    modifier: Modifier = Modifier,
    densityDpi: Int = 0,
) {
    val drawable by produceState<Drawable?>(initialValue = null, item.component, item.user, densityDpi) {
        value = withContext(Dispatchers.IO) { loader.get(item.component, item.user, densityDpi) }
    }
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(NornirPlaceholderSurface),
    ) {
        drawable?.let {
            Image(
                painter = rememberDrawablePainter(it),
                contentDescription = item.rawLabel,
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/** Neutral fill behind a loading/unresolvable icon (spec §5 card surface family). */
private val NornirPlaceholderSurface = Color(0xFF252538)
