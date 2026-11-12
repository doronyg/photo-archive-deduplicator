package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.use
import com.doronyg.photoarchivededuplicator.LogLevel
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class SecondaryResponsiveLayoutTest {
    @Test
    fun folderChooserStacksOnPhonesAndRetainsItsAction() {
        for (width in listOf(320, 390, 600, 1000)) {
            var clicks = 0
            render(width, fontScale = 1.4f, content = {
                ScanFolderInput("/photos", {}, enabled = true, onChoose = { clicks++ })
            }) { nodes ->
                assertFits(nodes, width)
                val field = nodes.single { it.config.getOrNull(SemanticsProperties.EditableText) != null }
                val choose = nodes.text(AppStrings.inventory.chooseFolder)
                if (width < 600) {
                    assertTrue(choose.boundsInRoot.top >= field.boundsInRoot.bottom)
                    assertTrue(field.boundsInRoot.width >= width - 1f)
                } else {
                    assertTrue(choose.boundsInRoot.left >= field.boundsInRoot.right)
                }
                assertTrue(checkNotNull(choose.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
                assertEquals(1, clicks)
            }
        }
    }

    @Test
    fun directoryMetadataDoesNotConsumeTheNameOnPhones() {
        val name = "Holiday photos"
        val details = "scanned · 12,345 files · 120 GB · last scanned November 12, 2026 · retries 0"
        for (width in listOf(320, 390, 600, 1000)) {
            render(width, fontScale = 1.4f, content = {
                BrowseDirectoryListRow("📁", name, details)
            }) { nodes ->
                assertFits(nodes, width)
                val nameNode = nodes.text(name)
                val detailsNode = nodes.text(details)
                assertTrue(nameNode.boundsInRoot.width > 100f)
                if (width < 600) assertTrue(detailsNode.boundsInRoot.top >= nameNode.boundsInRoot.bottom)
            }
        }
    }

    @Test
    fun deepBreadcrumbsHaveHorizontalScrollAndKeepNavigation() {
        var navigated = -1
        val stack = listOf(
            "/a/very/long/archive/root".toPath(),
            "/a/very/long/archive/root/holiday".toPath(),
            "/a/very/long/archive/root/holiday/day-one".toPath(),
        )
        render(320, fontScale = 1.4f, content = {
            BrowseBreadcrumbs(stack) { navigated = it }
        }) { nodes ->
            val scroller = nodes.single {
                it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
            }
            val range = checkNotNull(scroller.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange))
            assertTrue(range.maxValue() > 0f)
            assertTrue(scroller.boundsInRoot.width <= 320f)
            val root = nodes.text(AppStrings.inventory.allRoots)
            assertTrue(checkNotNull(root.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            assertEquals(0, navigated)
        }
    }

    @Test
    fun logFiltersAndActionsWrapWithoutClipping() {
        val actions = object : PathActions {
            override suspend fun openFile(path: Path) = Unit
            override suspend fun openFolder(path: Path) = Unit
        }
        for (width in listOf(320, 390, 1000)) {
            render(width, fontScale = 1.4f, content = {
                LogsScreen(actions, Modifier.fillMaxSize())
            }) { nodes ->
                assertFits(nodes, width)
                LogLevel.entries.forEach { nodes.text(AppStrings.inventory.minimumLogLevel(it.name)) }
                if (width == 320) assertTrue(
                    nodes.text(AppStrings.inventory.pauseScroll).boundsInRoot.top >
                        nodes.text(AppStrings.inventory.clearLogs).boundsInRoot.top)
                for (label in listOf(AppStrings.inventory.clearLogs, AppStrings.inventory.copyVisibleLogs, AppStrings.inventory.pauseScroll)) {
                    assertTrue(nodes.text(label).boundsInRoot.width > 0f)
                }
            }
        }
    }

    private fun render(
        width: Int,
        fontScale: Float,
        content: @Composable () -> Unit,
        check: (List<SemanticsNode>) -> Unit,
    ) {
        ImageComposeScene(width, 2400, density = Density(1f, fontScale)) {
            InventoryTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) { Column { content() } }
            }
        }.use { scene ->
            repeat(4) { scene.render(it * 16_666_667L).close() }
            check(scene.semanticsOwners.flatMap { it.rootSemanticsNode.descendants() })
        }
    }

    private fun assertFits(nodes: List<SemanticsNode>, width: Int) {
        for (node in nodes) {
            assertTrue(node.boundsInRoot.left >= -1f && node.boundsInRoot.right <= width + 1f,
                "Horizontal overflow at $width px: ${node.config}")
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) {
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line), "Ellipsized text: ${layout.layoutInput.text}")
                    assertTrue(layout.getLineRight(line) <= layout.size.width + 1f,
                        "Clipped text: ${layout.layoutInput.text}")
                    assertTrue(layout.getLineBottom(line) <= layout.size.height + 1f,
                        "Vertically clipped text: ${layout.layoutInput.text}")
                }
            }
        }
    }

    private fun List<SemanticsNode>.text(value: String): SemanticsNode = single {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> =
        listOf(this) + children.flatMap { it.descendants() }
}
