package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

internal fun deletionCategoryGridColumns(width: Int, minimumCardWidth: Int, spacing: Int, cardCount: Int): Int {
    require(width >= 0 && minimumCardWidth > 0 && spacing >= 0 && cardCount >= 0)
    if (cardCount == 0) return 0
    return ((width + spacing) / (minimumCardWidth + spacing)).coerceIn(1, cardCount)
}

@Composable
internal fun DeletionCategoryGrid(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val spacing = 12.dp.roundToPx()
        val width = constraints.maxWidth
        val columns = deletionCategoryGridColumns(width, 320.dp.roundToPx(), spacing, measurables.size)
        if (columns == 0) {
            layout(width, constraints.minHeight) {}
        } else {
            val cardWidth = (width - spacing * (columns - 1)) / columns
            // Intrinsics find the tallest wrapped card before measuring every card at that height.
            val cardHeight = measurables.maxOf { it.maxIntrinsicHeight(cardWidth) }
            val placeables = measurables.map { it.measure(Constraints.fixed(cardWidth, cardHeight)) }
            val rows = (placeables.size + columns - 1) / columns
            val height = rows * cardHeight + (rows - 1) * spacing
            layout(width, constraints.constrainHeight(height)) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(
                        x = (index % columns) * (cardWidth + spacing),
                        y = (index / columns) * (cardHeight + spacing),
                    )
                }
            }
        }
    }
}
