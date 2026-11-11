package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal expect fun ResultsScrollbar(state: LazyListState, modifier: Modifier)

@Composable
internal expect fun ResultsScrollbar(state: ScrollState, modifier: Modifier)
