package com.vm.nornir.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vm.nornir.launcher.icon.AppIcon
import com.vm.nornir.launcher.icon.IconLoader
import com.vm.nornir.launcher.model.AppItem

/**
 * One app row (`launcher-UI.md` §5): 38dp rounded icon, bold label, muted category
 * subtitle. The focused row inverts to the mint fill with dark text (§5.B).
 *
 * Stateless leaf: everything arrives via parameters; the click reports upstream.
 */
@Composable
fun AppCard(
    item: AppItem,
    isFocused: Boolean,
    iconLoader: IconLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    densityDpi: Int = 0,
) {
    val colors = LocalNornirColors.current
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isFocused) colors.mint else colors.surface, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            // The row speaks with one voice: its own description. Children (icon label,
            // texts, chip-like descendants) are silenced so text matchers stay unambiguous.
            .clearAndSetSemantics { contentDescription = cardDescription(item, isFocused) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(item = item, loader = iconLoader, densityDpi = densityDpi)
        Column {
            Text(
                text = item.rawLabel,
                color = if (isFocused) colors.textActive else colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = item.category.displayName,
                color = if (isFocused) colors.textActive.copy(alpha = SubtitleOnMintAlpha) else colors.textSubtitle,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

internal const val SubtitleOnMintAlpha = 0.7f

internal fun cardDescription(item: AppItem, isFocused: Boolean): String =
    "${item.rawLabel}, ${item.category.displayName}" + if (isFocused) ", focused" else ""
