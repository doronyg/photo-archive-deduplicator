package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

@Composable
internal actual fun ResultsScrollbar(state: LazyListState, modifier: Modifier) {
    val style = LocalScrollbarStyle.current.copy(
        thickness = 10.dp,
        unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
    val minimumThumbHeight = with(LocalDensity.current) { style.minimalHeight.toPx() }
    BoxWithConstraints(modifier.width(style.thickness)) {
        // Compose 1.11 can calculate a negative drag range when the thumb exceeds its track.
        if (resultsScrollbarHasDragSpace(constraints, minimumThumbHeight)) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(state),
                modifier = Modifier.fillMaxHeight(),
                style = style,
            )
        }
    }
}

internal fun resultsScrollbarHasDragSpace(constraints: Constraints, minimumThumbHeightPx: Float): Boolean =
    constraints.hasBoundedHeight && constraints.maxHeight > minimumThumbHeightPx
