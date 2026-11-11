package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DuplicateFilterPanelTest {
    @Test
    fun emptyResultsAllowAllFiltersToFitWithoutScrolling() {
        assertEquals(0f, scrollRange(compact = false, height = 800))
        assertTrue(scrollRange(compact = true, height = 800) > 0f)
    }

    @Test
    fun expandedFiltersStillScrollOnSmallWindows() {
        assertTrue(scrollRange(compact = false, height = 250) > 0f)
    }

    @Test
    fun desktopScrollbarScrollsTheCompactFilterPanel() {
        assertTrue(scrollRange(compact = true, height = 800, clickScrollbar = true) > 0f)
    }

    private fun scrollRange(compact: Boolean, height: Int, clickScrollbar: Boolean = false): Float =
        ImageComposeScene(800, height) {
            InventoryTheme {
                Surface(Modifier.fillMaxSize()) {
                    DuplicateFilterPanel(compact = compact) {
                        repeat(8) { Text("Filter $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }.use { scene ->
            repeat(3) { scene.render(it * 16_666_667L).close() }
            val node = scene.semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
                .single { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
            if (clickScrollbar) {
                val position = Offset(795f, node.boundsInRoot.bottom - 4f)
                scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = 100)
                scene.sendPointerEvent(PointerEventType.Press, position, timeMillis = 110, button = PointerButton.Primary)
                scene.render(150_000_000L).close()
                scene.sendPointerEvent(PointerEventType.Release, position, timeMillis = 200, button = PointerButton.Primary)
                scene.render(250_000_000L).close()
                assertTrue(range.value() > 0f, "Clicking the scrollbar must scroll the filters")
            }
            range.maxValue()
        }

    private fun SemanticsNode.descendants(): List<SemanticsNode> =
        listOf(this) + children.flatMap { it.descendants() }
}
