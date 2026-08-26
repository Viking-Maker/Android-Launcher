package com.vm.nornir.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vm.nornir.launcher.ui.LocalNornirColors
import com.vm.nornir.launcher.model.NornirCategory

/**
 * The horizontal category filter bar (`launcher-UI.md` §4, ADR-0002 §4).
 *
 * Chip order follows the spec's left-to-right set: the two **never-hidden** filter-axis
 * chips (`Favorites`, `All`), then one chip per [availableCategories] in taxonomy order.
 * Empty categories never reach this composable — `visibleCategories()` hides them — and
 * the two filter-axis chips render unconditionally, per ADR-0002 §4.
 *
 * Selected state: solid lavender pill with dark text (spec §4); unselected: dark
 * elevated surface with muted text. Pure and previewable.
 */
@Composable
fun CategoryBar(
    availableCategories: List<NornirCategory>,
    filter: FilterMode,
    onFilterSelected: (FilterMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .testTag(TestTags.FILTER_BAR),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            label = "Favorites", // never hidden — ADR-0002 §4 (a toggle, not a category)
            selected = filter is FilterMode.Favorites,
            onClick = { onFilterSelected(FilterMode.Favorites) },
        )
        FilterChip(
            label = "All",
            selected = filter is FilterMode.All,
            onClick = { onFilterSelected(FilterMode.All) },
        )
        for (category in availableCategories) {
            FilterChip(
                label = category.displayName,
                selected = filter is FilterMode.Category && filter.category == category,
                onClick = { onFilterSelected(FilterMode.Category(category)) },
            )
        }
    }
}

/** One squircle chip (spec §4: ~36dp tall, 10–12dp radius, 6–8dp gaps). */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalNornirColors.current
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .height(36.dp)
            .then(if (selected) Modifier.background(colors.lavender, shape) else Modifier.background(colors.surface, shape).border(1.dp, colors.surface, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = chipDescription(label, selected) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.textActive else colors.textSubtitle,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

internal fun chipDescription(label: String, selected: Boolean): String =
    label + if (selected) " filter chip, selected" else " filter chip"
