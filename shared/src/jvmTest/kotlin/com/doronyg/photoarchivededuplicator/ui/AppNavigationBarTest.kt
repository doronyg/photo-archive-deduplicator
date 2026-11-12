package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.doronyg.photoarchivededuplicator.UiTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class AppNavigationBarTest {
    @Test
    fun scrollButtonsReachBothEndsWithoutChangingScreens() {
        for (width in listOf(320, 1000)) {
            val selected = mutableStateOf(UiTab.SCAN)
            ImageComposeScene(width, 300, density = Density(1f, 1.4f)) {
                InventoryTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column { AppNavigationBar(selected.value, true) { selected.value = it } }
                    }
                }
            }.use { scene ->
                val driver = Driver(scene)
                driver.settle()
                val range = driver.nodes().single {
                    it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
                }.config[SemanticsProperties.HorizontalScrollAxisRange]
                assertTrue(range.maxValue() > 0f)
                repeat(20) {
                    if (range.value() < range.maxValue()) driver.clickDescription("Scroll navigation forward")
                }
                assertEquals(range.maxValue(), range.value())
                assertEquals(UiTab.SCAN, selected.value)
                driver.settle()
                assertEquals(range.maxValue(), range.value(), "Manual scroll must not snap back to the selected tab")
                val lastTab = driver.nodes().single {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                        text.text == UiTab.entries.last().title
                    } == true
                }
                assertTrue(lastTab.boundsInRoot.width > 0f)
                driver.click(lastTab.boundsInRoot.center)
                assertEquals(UiTab.entries.last(), selected.value)
                repeat(20) {
                    if (range.value() > 0f) driver.clickDescription("Scroll navigation backward")
                }
                assertEquals(0f, range.value())
                assertEquals(UiTab.entries.last(), selected.value)
            }
        }
    }

    @Test
    fun restoredSelectionIsVisibleAndBusyNavigationIsDisabled() {
        ImageComposeScene(320, 300) {
            InventoryTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column { AppNavigationBar(UiTab.entries.last(), false) { error("Navigation is busy") } }
                }
            }
        }.use { scene ->
            val driver = Driver(scene)
            driver.settle()
            val tabs = driver.nodes().filter { it.config.getOrNull(SemanticsProperties.Selected) != null }
            assertEquals(UiTab.entries.size, tabs.size)
            assertTrue(tabs.all { it.config.contains(SemanticsProperties.Disabled) })
            val selected = tabs.single { it.config[SemanticsProperties.Selected] }
            assertTrue(selected.boundsInRoot.width > 0f)
            val range = driver.nodes().single {
                it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
            }.config[SemanticsProperties.HorizontalScrollAxisRange]
            assertEquals(range.maxValue(), range.value(), "The restored last tab must scroll fully into view")
            driver.click(selected.boundsInRoot.center)
        }
    }

    @Test
    fun arrowsDisappearWhenAllTabsFitIncludingAfterWindowResizes() {
        val width = mutableStateOf(320)
        ImageComposeScene(3000, 300) {
            InventoryTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box {
                        Column(Modifier.width(width.value.dp)) {
                            AppNavigationBar(UiTab.SCAN, true) {}
                        }
                    }
                }
            }
        }.use { scene ->
            val driver = Driver(scene)
            driver.settle()
            val scroller = driver.nodes().single {
                it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
            }
            val range = scroller.config[SemanticsProperties.HorizontalScrollAxisRange]
            val contentWidth = (scroller.boundsInRoot.width + range.maxValue()).toInt()
            assertTrue(driver.hasScrollButtons())
            for (availableWidth in listOf(contentWidth, contentWidth - 1, contentWidth + 40, 320, 3000)) {
                width.value = availableWidth
                driver.settle()
                assertEquals(availableWidth < contentWidth, driver.hasScrollButtons(),
                    "Scroll controls should only appear when tabs overflow at $availableWidth px")
            }
        }
    }

    @Test
    fun touchOnlyNavigationHasNoArrowsAndUsesTheFullWidth() {
        ImageComposeScene(320, 300) {
            InventoryTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column { AppNavigationBar(UiTab.SCAN, true, scrollButtonsEnabled = false) {} }
                }
            }
        }.use { scene ->
            val driver = Driver(scene)
            driver.settle()
            assertEquals(false, driver.hasScrollButtons())
            val scroller = driver.nodes().single {
                it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
            }
            assertEquals(320f, scroller.boundsInRoot.width)
            val range = scroller.config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(range.maxValue() > 0f)
            driver.swipe(Offset(300f, scroller.boundsInRoot.center.y), Offset(20f, scroller.boundsInRoot.center.y))
            assertTrue(range.value() > 0f)
        }
    }

    @Test
    fun touchSwipesAndHorizontalWheelStillScrollTheTabs() {
        for (width in listOf(320, 1000)) {
            ImageComposeScene(width, 300) {
                InventoryTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column { AppNavigationBar(UiTab.SCAN, true) { error("Scrolling must not select a tab") } }
                    }
                }
            }.use { scene ->
                val driver = Driver(scene)
                driver.settle()
                val scroller = driver.nodes().single {
                    it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
                }
                val range = scroller.config[SemanticsProperties.HorizontalScrollAxisRange]
                val start = Offset(scroller.boundsInRoot.right - 20f, scroller.boundsInRoot.center.y)
                driver.swipe(start, Offset(scroller.boundsInRoot.left + 20f, start.y))
                assertTrue(range.value() > 0f, "Touch swipes must scroll at $width px")
                val afterSwipe = range.value()
                driver.wheel(scroller.boundsInRoot.center)
                assertTrue(range.value() < afterSwipe, "Horizontal wheel input must scroll at $width px")
            }
        }
    }

    private class Driver(val scene: ImageComposeScene) {
        private var time = 0L

        fun settle() {
            repeat(60) {
                time += 16_666_667L
                scene.render(time).close()
            }
        }

        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }

        fun hasScrollButtons(): Boolean = nodes().any {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Scroll navigation forward") == true
        }

        fun clickDescription(description: String) {
            val node = nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
            }
            assertTrue(!node.config.contains(SemanticsProperties.Disabled))
            click(node.boundsInRoot.center)
        }

        fun click(position: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = time / 1_000_000)
            scene.sendPointerEvent(PointerEventType.Press, position, timeMillis = time / 1_000_000 + 1,
                button = PointerButton.Primary)
            time += 16_666_667L
            scene.render(time).close()
            scene.sendPointerEvent(PointerEventType.Release, position, timeMillis = time / 1_000_000 + 1,
                button = PointerButton.Primary)
            settle()
        }

        fun swipe(start: Offset, end: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, start, timeMillis = time / 1_000_000,
                type = PointerType.Touch)
            repeat(12) { index ->
                time += 16_666_667L
                val position = start + (end - start) * ((index + 1) / 12f)
                scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = time / 1_000_000,
                    type = PointerType.Touch)
                scene.render(time).close()
            }
            time += 16_666_667L
            scene.sendPointerEvent(PointerEventType.Release, end, timeMillis = time / 1_000_000,
                type = PointerType.Touch)
            settle()
        }

        fun wheel(position: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = time / 1_000_000)
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(-3f, 0f),
                timeMillis = time / 1_000_000 + 1)
            settle()
        }
    }
}

private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
