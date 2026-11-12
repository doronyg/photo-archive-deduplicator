package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.UiTab
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.launch

internal expect val navigationScrollButtonsEnabled: Boolean

@Composable
internal fun AppNavigationBar(
    selectedTab: UiTab,
    enabled: Boolean,
    scrollButtonsEnabled: Boolean = navigationScrollButtonsEnabled,
    onSelect: (UiTab) -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = with(LocalDensity.current) { maxWidth.roundToPx() }
        // Compare with the whole bar, not the viewport narrowed by the arrows themselves.
        val showArrows = scrollButtonsEnabled && scroll.viewportSize > 0 &&
            scroll.maxValue != Int.MAX_VALUE && scroll.viewportSize.toLong() + scroll.maxValue > availableWidth
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showArrows) NavigationScrollButton(
                forward = false,
                enabled = scroll.canScrollBackward,
                onClick = {
                    scope.launch { scroll.animateScrollTo((scroll.value - scroll.viewportSize * 4 / 5).coerceAtLeast(0)) }
                },
            )
            // Recalculate selected-tab visibility when the controls change the viewport.
            key(showArrows) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    modifier = Modifier.weight(1f),
                    scrollState = scroll,
                    edgePadding = 0.dp,
                ) {
                    UiTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            enabled = enabled,
                            onClick = { onSelect(tab) },
                            text = { Text(tab.title, maxLines = 1) },
                        )
                    }
                }
            }
            if (showArrows) NavigationScrollButton(
                forward = true,
                enabled = scroll.canScrollForward,
                onClick = {
                    scope.launch {
                        scroll.animateScrollTo((scroll.value + scroll.viewportSize * 4 / 5).coerceAtMost(scroll.maxValue))
                    }
                },
            )
        }
    }
}

@Composable
private fun NavigationScrollButton(forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pointsRight = forward == (LocalLayoutDirection.current == LayoutDirection.Ltr)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = if (forward) AppStrings.scrollNavigationForward else AppStrings.scrollNavigationBackward
        },
    ) {
        val color = LocalContentColor.current
        Canvas(Modifier.size(20.dp)) {
            val edgeX = size.width * if (pointsRight) 0.35f else 0.65f
            val center = Offset(size.width - edgeX, size.height * 0.5f)
            drawLine(color, Offset(edgeX, size.height * 0.2f), center, 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, center, Offset(edgeX, size.height * 0.8f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
