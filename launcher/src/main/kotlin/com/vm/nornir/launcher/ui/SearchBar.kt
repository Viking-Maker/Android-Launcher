package com.vm.nornir.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vm.nornir.launcher.ui.LocalNornirColors

/**
 * The auto-focused single-line search pill (`launcher-UI.md` §3).
 *
 * Recessed dark field, 1.5dp copper outline, lavender-gray placeholder — strictly one
 * line; every keystroke reports [LauncherEvent.QueryChanged] upstream so filtering is
 * instant (spec §7). Pure: all state comes in through [query].
 *
 * @param focusRequester the screen-level requester that auto-focuses this field on show.
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val colors = LocalNornirColors.current
    val shape = RoundedCornerShape(SearchRadiusDp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(colors.searchBarBackground, shape)
            .border(1.5.dp, colors.copper, shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = TextFieldValue(query),
            onValueChange = { onQueryChanged(it.text) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Default),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = colors.textPrimary,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(colors.lavender),
            modifier = Modifier
                .testTag(TestTags.SEARCH) // the focus/text-input target is the field itself
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequesterOrDefault(focusRequester),
        ) { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    // The `>` command mode is out of scope (#11); placeholder drops it.
                    Text(
                        text = "Search entries...",
                        color = colors.textSubtitle,
                        fontSize = 14.sp,
                    )
                }
                inner()
            }
        }
    }
}

private fun Modifier.focusRequesterOrDefault(requester: FocusRequester?): Modifier =
    if (requester != null) this.focusRequester(requester) else this

/** Spec §3: pill-shaped field, 18–20dp radius midpoint. */
internal const val SearchRadiusDp = 19
