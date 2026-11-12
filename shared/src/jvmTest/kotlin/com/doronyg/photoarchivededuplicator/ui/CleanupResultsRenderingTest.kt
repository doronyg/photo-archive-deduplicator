package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.use
import androidx.compose.ui.unit.Density
import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import com.doronyg.photoarchivededuplicator.operations.history.ArchiveIdentity
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistorySnapshot
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryStatistics
import com.doronyg.photoarchivededuplicator.recommendation.FileStatistics
import com.doronyg.photoarchivededuplicator.recommendation.InventoryFileCounts
import com.doronyg.photoarchivededuplicator.recommendation.InventoryStatistics
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsReport
import com.doronyg.photoarchivededuplicator.recommendation.StatisticsFileType
import com.doronyg.photoarchivededuplicator.recommendation.aggregateAiRecommendationStatistics
import com.doronyg.photoarchivededuplicator.recommendation.aggregateRecommendationStatistics
import com.doronyg.photoarchivededuplicator.scan.InventorySummary
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class CleanupResultsRenderingTest {
    @Test
    fun rendersPhoneWidthWithEnlargedText() = runTest {
        for (dark in listOf(false, true)) {
            renderAndCheck(fixture(populated = true), null, dark, 320, 5500, fontScale = 1.4f)
        }
    }

    @Test
    fun rendersBothThemesAndLayoutsAndClicksDetailedStatistics() = runTest {
        val data = fixture(populated = true)
        for ((layout, width, height) in layouts) {
            val backgrounds = mutableListOf<Int>()
            for (dark in listOf(false, true)) {
                backgrounds += renderAndCheck(
                    data, null, dark, width, height,
                    screenshot = "cleanup-${if (dark) "dark" else "light"}-$layout.png",
                )
            }
            assertNotEquals(backgrounds[0], backgrounds[1], "$layout must honor the selected theme")
        }
    }

    @Test
    fun rendersEmptyUnconfiguredAndPartialHistoryWithoutTextOverflow() = runTest {
        val empty = fixture(populated = false)
        val populated = fixture(populated = true)
        val states = listOf(
            empty to null,
            empty.copy(history = null) to AppStrings.cleanup.binNotConfigured,
            populated.copy(history = populated.history!!.copy(partial = true)) to null,
        )
        for ((data, message) in states) {
            for ((_, width, height) in layouts) {
                for (dark in listOf(false, true)) {
                    renderAndCheck(data, message, dark, width, height)
                }
            }
        }
    }

    private fun renderAndCheck(
        data: CleanupResultsPresentation,
        message: String?,
        dark: Boolean,
        width: Int,
        height: Int,
        screenshot: String? = null,
        fontScale: Float = 1f,
    ): Int {
        var detailsClicks = 0
        var refreshClicks = 0
        return ImageComposeScene(width, height, density = Density(1f, fontScale)) {
            InventoryTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    CleanupResultsContent(
                        data, message,
                        onDetailedStatistics = { detailsClicks++ },
                        onRefresh = { refreshClicks++ },
                    )
                }
            }
        }.use { scene ->
            repeat(3) { frame -> scene.render(frame * 16_666_667L).close() }
            val nodes = scene.semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
            val texts = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
            assertTrue(AppStrings.cleanup.title in texts, "Missing page title at $width px")
            assertTrue(AppStrings.cleanup.detailedStatistics in texts, "Details card is clipped at $width px")
            if (message != null) assertTrue(message in texts, "Missing availability status")
            for (removed in listOf("A clearer archive.", "Smarter about what stays.",
                "History is incomplete.", "Permanent deletion", "Permanently deleted",
                "not a live check", "final decision is yours")) {
                assertFalse(texts.any { removed in it }, "Unnecessary copy: $removed")
            }
            if (data.aiRegular == null && data.aiSuspected == null) {
                assertTrue(AppStrings.cleanup.aiNotConfigured in texts)
            }
            if (data.possibleDuplicateFiles == 0L) {
                assertTrue(AppStrings.cleanup.noDuplicates in texts)
            }
            for (node in nodes) {
                val bounds = node.boundsInRoot
                assertTrue(bounds.left >= -1f && bounds.right <= width + 1f,
                    "Horizontal overflow at $width px: ${node.config}")
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.forEach {
                    // Compose can reuse a paragraph wider than the final text size; inspect glyph lines.
                    for (line in 0 until it.lineCount) {
                        assertFalse(it.isLineEllipsized(line), "Ellipsized text: ${it.layoutInput.text}")
                        assertTrue(it.getLineRight(line) <= it.size.width + 1f,
                            "Horizontally clipped text at $width px: ${it.layoutInput.text}")
                        assertTrue(it.getLineBottom(line) <= it.size.height + 1f,
                            "Vertically clipped text at $width px: ${it.layoutInput.text}")
                    }
                }
            }
            val open = nodes.single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                    text.text == AppStrings.cleanup.open
                } == true && it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            val center = open.boundsInRoot.center
            assertTrue(center.x in 0f..width.toFloat() && center.y in 0f..height.toFloat())
            scene.sendPointerEvent(PointerEventType.Move, center, timeMillis = 100)
            scene.sendPointerEvent(PointerEventType.Press, center, timeMillis = 110, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, center, timeMillis = 120, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Exit, center, timeMillis = 130)
            scene.render(150_000_000L).close()
            assertEquals(1, detailsClicks, "The actual details button must dispatch navigation once")
            assertEquals(0, refreshClicks, "Details navigation must not refresh the report")
            scene.render(1_000_000_000L).close()
            scene.render(2_000_000_000L).use { image ->
                assertEquals(width, image.width)
                assertEquals(height, image.height)
                val bytes = checkNotNull(image.encodeToData()).use { it.bytes }
                val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
                val background = bitmap.getRGB(width - 1, height - 1)
                assertEquals(255, background ushr 24, "Theme surface must be opaque")
                if (screenshot != null) {
                    System.getenv("CLEANUP_SCREENSHOT_DIR")?.let { directory ->
                        val output = File(directory, screenshot)
                        output.parentFile.mkdirs()
                        output.writeBytes(bytes)
                    }
                }
                background
            }
        }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> =
        listOf(this) + children.flatMap { it.descendants() }

    private suspend fun fixture(populated: Boolean): CleanupResultsPresentation {
        val cohort = aggregateRecommendationStatistics(emptyList())
        val ai = aggregateAiRecommendationStatistics(emptyList(), emptyList(), emptyMap()).copy(
            changedGroups = 12,
            changedFiles = FileStatistics(28, 280_000_000),
            baselineRedundantCandidates = FileStatistics(70, 700_000_000),
            currentRedundantCandidates = FileStatistics(62, 620_000_000),
            addedRedundantCandidates = FileStatistics(4, 40_000_000),
            withdrawnRedundantCandidates = FileStatistics(12, 120_000_000),
        )
        val counts = if (populated) listOf(120L, 24L, 12L, 4L) else List(4) { 0L }
        val report = RecommendationStatisticsReport(
            regular = if (populated) cohort.copy(ai = ai) else cohort,
            suspected = if (populated) cohort.copy(ai = ai.copy(changedGroups = 3)) else cohort,
            classificationContext = if (populated) OllamaSettings("http://ollama.test", "model").context() else null,
            inventory = InventoryStatistics(
                all = InventoryFileCounts(if (populated) 1_000 else 0, counts.sum()),
                byFileType = StatisticsFileType.entries.mapIndexed { index, type ->
                    type to InventoryFileCounts(counts[index], counts[index])
                }.toMap(),
            ),
        )
        val history = OperationHistorySnapshot(
            ArchiveIdentity("render-archive", "render-token"), emptyList(), emptyList(),
            if (populated) OperationHistoryStatistics(
                completedMoves = 42, filesInBin = 35, logicalBytesInBin = 850_000_000,
            ) else OperationHistoryStatistics(),
        )
        return presentCleanupResults(
            CleanupResultsSnapshot(
                InventorySummary(emptyMap(), if (populated) 1_000 else 0, if (populated) 12_500_000_000 else 0),
                report,
            ),
            history,
        )
    }

    private companion object {
        val layouts = listOf(Triple("wide", 1_200, 2_200), Triple("narrow", 390, 3_400))
    }
}
