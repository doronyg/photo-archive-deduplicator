package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.exif.ExifExtractionService
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.exif.ExifSource
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.test.runCurrent
import okio.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DuplicateScreensResponsiveTest {
    @Test
    fun duplicateScreensKeepFiltersWithinPhoneWidthAndReachableByVerticalScrolling() = concurrencyTest { f ->
        val preferences = UiPreferences(UiChoices(), backgroundScope) {}
        val scores = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val exif = ExifExtractionService(f.repository, ExifSource { error("Rendering must not read files") }, f.dispatcher)
        val queue = ExifQueueController(f.repository, scores, exif, backgroundScope, f.dispatcher)
        val paths = object : PathActions {
            override suspend fun openFile(path: Path) = Unit
            override suspend fun openFolder(path: Path) = Unit
        }
        for (suspected in listOf(false, true)) for (scale in listOf(1f, 1.4f)) {
            ImageComposeScene(320, 740, density = Density(1f, scale)) {
                InventoryTheme {
                    Surface(Modifier.fillMaxSize()) {
                        if (suspected) SuspectedDuplicatesScreen(f.repository, scores, queue, paths, preferences,
                            scanning = true, modifier = Modifier.fillMaxSize())
                        else DuplicatesScreen(f.repository, ScanTarget.ChosenFolder { null }, paths, preferences,
                            scores, exif, queue, scanRunning = true, modifier = Modifier.fillMaxSize())
                    }
                }
            }.use { scene ->
                repeat(3) { runCurrent(); scene.render(it * 16_666_667L).close() }
                val seenText = mutableSetOf<String>()
                repeat(16) { step ->
                    val nodes = scene.semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
                    for (node in nodes) {
                        val bounds = node.boundsInRoot
                        assertTrue(bounds.left >= -1f && bounds.right <= 321f,
                            "Overflow in ${if (suspected) "suspected" else "regular"} / $scale: ${node.config}")
                        if (bounds.height <= 0f || node.config.getOrNull(SemanticsProperties.EditableText) != null) continue
                        seenText += node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                        val layouts = mutableListOf<TextLayoutResult>()
                        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                        for (layout in layouts) for (line in 0 until layout.lineCount) {
                            assertFalse(layout.isLineEllipsized(line))
                            assertTrue(layout.getLineRight(line) <= layout.size.width + 1,
                                "Clipped text: ${layout.layoutInput.text}")
                        }
                    }
                    val filterScroll = nodes.firstOrNull {
                        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.let { max -> max > 0 } == true
                    }
                    filterScroll?.let {
                        val position = Offset(315f, it.boundsInRoot.bottom - 4f)
                        scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = (step + 3) * 100L)
                        scene.sendPointerEvent(PointerEventType.Press, position,
                            timeMillis = (step + 3) * 100L + 10, button = PointerButton.Primary)
                        scene.render((step + 3) * 100_000_000L + 15_000_000L).close()
                        scene.sendPointerEvent(PointerEventType.Release, position,
                            timeMillis = (step + 3) * 100L + 20, button = PointerButton.Primary)
                    }
                    scene.render((step + 3) * 100_000_000L + 30_000_000L).close()
                }
                assertTrue(seenText.any { "Sort" in it }, "Sort controls must be reachable ($suspected / $scale): $seenText")
                assertTrue(seenText.any { "Name" in it || "Filename" in it }, "Priority cards must be reachable")
            }
        }
        queue.shutdown()
        preferences.shutdown()
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
}
