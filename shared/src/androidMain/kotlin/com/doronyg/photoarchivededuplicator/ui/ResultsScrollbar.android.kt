package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Android keeps touch scrolling; the draggable scrollbar is a desktop control.
@Composable
internal actual fun ResultsScrollbar(state: LazyListState, modifier: Modifier) = Unit
