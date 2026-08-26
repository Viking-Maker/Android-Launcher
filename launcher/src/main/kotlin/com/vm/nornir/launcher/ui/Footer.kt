package com.vm.nornir.launcher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.vm.nornir.launcher.ui.LocalNornirColors

/**
 * The "N results" footer (`launcher-UI.md` §6): live match count, dim slate, bottom-left.
 */
@Composable
fun Footer(
    resultCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNornirColors.current
    Text(
        text = footerText(resultCount),
        color = colors.textFooter,
        fontSize = 12.sp,
        modifier = modifier
            .testTag(TestTags.FOOTER)
            .semantics { contentDescription = "$resultCount results" },
    )
}

internal fun footerText(resultCount: Int): String = "$resultCount results"
