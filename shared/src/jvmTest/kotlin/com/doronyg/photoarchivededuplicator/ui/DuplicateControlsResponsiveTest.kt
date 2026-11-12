package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.doronyg.photoarchivededuplicator.scan.defaultDuplicateSortRules
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DuplicateControlsResponsiveTest {
    @Test
    fun sortPrioritiesWrapAndKeepEveryDirectionAndPriorityActionVisible() {
        for (width in listOf(280, 320, 390, 600)) for (scale in listOf(1f, 1.4f)) {
            checkLayout(width, scale) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DuplicateSortControls(defaultDuplicateSortRules(), true) {}
                }
            }
        }
    }

    @Test
    fun deletionActionsWrapWithoutClippingButtonsOrCheckboxLabels() {
        for (width in listOf(280, 320, 390, 600)) for (scale in listOf(1f, 1.4f)) {
            checkLayout(width, scale) {
                Column {
                    DuplicateDeletionSelectionBar(emptyList(), true, {}, {})
                    DuplicateDeletionCheckbox(AppStrings.operations.selectDuplicateGroup, false, true) {}
                }
            }
        }
    }

    private fun checkLayout(width: Int, scale: Float, content: @Composable () -> Unit) {
        ImageComposeScene(width, 4000, density = Density(1f, scale)) {
            InventoryTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) { content() }
                }
            }
        }.use { scene ->
            repeat(3) { scene.render(it * 16_666_667L).close() }
            val nodes = scene.semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
            assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null },
                "Controls should wrap, not require sideways scrolling")
            for (node in nodes) {
                val bounds = node.boundsInRoot
                assertTrue(bounds.left >= -1f && bounds.right <= width + 1f, "Overflow at $width / $scale: ${node.config}")
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                for (layout in layouts) for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    assertTrue(layout.getLineRight(line) <= layout.size.width + 1,
                        "Clipped text at $width / $scale: ${layout.layoutInput.text}")
                    assertTrue(layout.getLineBottom(line) <= layout.size.height + 1,
                        "Clipped text at $width / $scale: ${layout.layoutInput.text}")
                }
            }
        }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
}
