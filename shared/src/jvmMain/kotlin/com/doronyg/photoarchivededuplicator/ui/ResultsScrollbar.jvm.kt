package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal actual fun ResultsScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style = LocalScrollbarStyle.current.copy(
            thickness = 10.dp,
            unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        ),
    )
}
