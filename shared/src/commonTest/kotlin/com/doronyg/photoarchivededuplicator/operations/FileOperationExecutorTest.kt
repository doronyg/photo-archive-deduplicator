package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.operations.history.*
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationPolicy
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import okio.IOException
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class FileOperationExecutorTest {
    @Test fun protectedPolicyRequiresOneRetainedSameDirectoryHighConfidenceSnapshot() = runTest {
        for (invalid in 0..5) {
            val f = Fixture(this)
            f.initialize()
            val original = f.proposal.groups.single()
            val safe = original.copy(policy = DeletionRecommendationPolicy.SINGLE_PROTECTED_DIRECTORY,
                members = original.members.mapIndexed { index, member -> member.copy(
                    role = if (index == 0) FileRecommendationRole.REDUNDANT_CANDIDATE else FileRecommendationRole.PREFERRED,
                    classification = DirectoryClassification.PROTECTED) })
            assertTrue(safe.allowsSingleProtectedDirectory())
            val unsafe = when (invalid) {
                0 -> safe.copy(policy = DeletionRecommendationPolicy.ORDINARY)
                1 -> safe.copy(duplicateConfidence = 74)
                2 -> safe.copy(members = safe.members.map { it.copy(classification = DirectoryClassification.PREFERRED) })
                3 -> safe.copy(members = safe.members.map { it.copy(role = FileRecommendationRole.REDUNDANT_CANDIDATE) })
                4 -> safe.copy(members = safe.members.map { it.copy(decisionConfidence = 74) })
                else -> safe.copy(members = listOf(safe.members.first().copy(
                    retainedReplacementPath = safe.members.first().file.path), safe.members.last()))
            }
            assertFalse(unsafe.allowsSingleProtectedDirectory())
            f.proposal = f.proposal.copy(groups = listOf(unsafe))
            val result = f.execute()
            assertEquals(0, f.moveCount)
            assertEquals(FileMoveExecutionStatus.FAILED, result.items.single().status)
        }
    }

    @Test fun journalWindowsForceAllIntentsBeforeMovingAndAllResultsBeforeInventory() = runTest {
        val f = Fixture(this)
        f.initialize(51)
        f.moveFailure = {
            val lines = f.journal().lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(if (f.moveCount <= 50) 50 else 51, lines.count { it.contains("\"INTENT\"") })
            assertEquals(if (f.moveCount <= 50) 0 else 50, f.recorded.size)
            assertEquals(if (f.moveCount <= 50) 2 else 4, f.flushed.count { it.name == "events.jsonl" })
        }
        val progress = mutableListOf<FileExecutionProgress>()
        val result = f.execute(record = {
            assertEquals(if (f.moveCount <= 50) 50 else 51,
                f.journal().lineSequence().count { it.contains("\"COMPLETED\"") })
            assertEquals(if (f.moveCount <= 50) 3 else 5, f.flushed.count { it.name == "events.jsonl" })
            f.recorded += it
        }, progress = { progress += it })
        assertEquals(51, result.items.count { it.status == FileMoveExecutionStatus.COMPLETED })
        assertEquals(5, f.flushed.count { it.name == "events.jsonl" })
        val firstResults = progress.first { it.phase == FileExecutionPhase.SAVING_RESULTS }
        assertEquals(50, firstResults.verified)
        assertEquals(0, firstResults.completed)
        assertEquals(1, firstResults.remaining)
        assertEquals(0, progress.last().remaining)
    }

    @Test fun cooperativeCancellationFinalizesPreIntendedItemsWithoutPendingHistory() = runTest {
        val f = Fixture(this)
        f.initialize(3)
        val control = FileExecutionControl()
        f.moveFailure = { control.requestCancellation() }
        val result = f.executor().execute(f.proposal, f.binding, {}, {}, { f.recorded += it }, control = control)
        assertTrue(result.cancelled)
        assertEquals(listOf(FileMoveExecutionStatus.COMPLETED, FileMoveExecutionStatus.NOT_STARTED,
            FileMoveExecutionStatus.NOT_STARTED), result.items.map { it.status })
        assertEquals(1, f.recorded.size)
        assertTrue(f.history().items.none { it.moveStatus == OperationHistoryMoveStatus.PENDING })
        assertEquals(2, f.journal().lineSequence().count { it.contains("Not attempted: cancelled") })
    }

    @Test fun tokenCancellationBeforeOrDuringValidationNeverMoves() = runTest {
        for (preCancelled in listOf(true, false)) {
            val f = Fixture(this)
            f.initialize(2)
            val control = FileExecutionControl()
            if (preCancelled) control.requestCancellation()
            val result = f.executor().execute(f.proposal, f.binding,
                { control.requestCancellation() }, {}, {}, control = control)
            assertTrue(result.cancelled)
            assertTrue(result.items.all { it.status == FileMoveExecutionStatus.NOT_STARTED })
            assertEquals(0, f.moveCount)
            f.assertUnlocked()
        }
    }

    @Test fun cancellationDuringDirectoryPreparationReturnsNormallyAndReleasesPreparationLock() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        val control = FileExecutionControl()
        val guarded = object : ForwardingFileSystem(f.fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                if (path.name == BIN_DIRECTORY_MARKER_NAME && functionName == "sink") control.requestCancellation()
                return path
            }
        }
        val result = f.executor(guarded).execute(f.proposal, f.binding, {}, {}, {}, control = control)
        assertTrue(result.cancelled)
        assertEquals(0, f.moveCount)
        assertTrue(result.items.all { it.status == FileMoveExecutionStatus.NOT_STARTED })
        assertFalse(f.fs.exists(f.bin / "$HISTORY_DIRECTORY_NAME/.directory-preparation.lock"))
        f.assertUnlocked()
    }

    @Test fun uncertainMiddleMoveCommitsKnownSuccessAndClosesOnlyUnattemptedIntents() = runTest {
        val f = Fixture(this)
        f.initialize(4)
        f.moveFailure = {
            if (f.moveCount == 2) {
                val move = f.proposal.moves[1]
                f.fs.atomicMove(move.sourcePath, move.destinationPath!!)
                throw IOException("unknown native outcome")
            }
        }
        val result = f.execute()
        assertEquals(listOf(FileMoveExecutionStatus.COMPLETED, FileMoveExecutionStatus.UNCERTAIN,
            FileMoveExecutionStatus.NOT_STARTED, FileMoveExecutionStatus.NOT_STARTED), result.items.map { it.status })
        assertEquals(1, f.recorded.size)
        assertEquals(listOf(OperationHistoryMoveStatus.COMPLETED, OperationHistoryMoveStatus.PENDING,
            OperationHistoryMoveStatus.FAILED, OperationHistoryMoveStatus.FAILED), f.history().items.map { it.moveStatus })
    }

    @Test fun failedSecondWindowResultFlushNeverTruncatesFirstDurableWindow() = runTest {
        val f = Fixture(this)
        f.initialize(51)
        var flushes = 0
        f.flushFailure = { it.name == "events.jsonl" && ++flushes == 5 }
        val progress = mutableListOf<FileExecutionProgress>()
        val result = f.execute(progress = { progress += it })
        assertEquals(50, result.items.count { it.status == FileMoveExecutionStatus.COMPLETED })
        assertEquals(FileMoveExecutionStatus.UNCERTAIN, result.items.last().status)
        assertEquals(50, f.recorded.size)
        assertEquals(50, progress.last().completed)
        assertEquals(250L, progress.last().logicalBytesMoved)
        assertEquals(0, progress.last().remaining)
        assertEquals(50, f.history().items.count { it.confirmedInBin })
        assertEquals(OperationHistoryMoveStatus.PENDING, f.history().items.last().moveStatus)
        f.assertUnlocked()
    }

    @Test fun globalValidationFailureAfterKnownMoveFinalizesWindowThenPropagatesOriginal() = runTest {
        val f = Fixture(this)
        f.initialize(3)
        val failure = FileOperationException(
            FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED), global = true)
        val caught = assertFailsWith<FileOperationException> {
            f.execute(before = {
                if (it.sourcePath == f.proposal.moves[1].sourcePath) throw failure
            })
        }
        assertSame(failure, caught)
        assertEquals(1, f.moveCount)
        assertEquals(1, f.recorded.size)
        assertEquals(listOf(OperationHistoryMoveStatus.COMPLETED, OperationHistoryMoveStatus.FAILED,
            OperationHistoryMoveStatus.FAILED), f.history().items.map { it.moveStatus })
        f.assertUnlocked()
    }

    @Test fun progressFailureDuringResultSavingStillFlushesAndUpdatesKnownMoves() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        val failure = IllegalStateException("progress observer failed")
        val caught = assertFailsWith<IllegalStateException> {
            f.execute(progress = { if (it.phase == FileExecutionPhase.SAVING_RESULTS) throw failure })
        }
        assertEquals(failure.message, caught.message)
        assertEquals(2, f.recorded.size)
        assertEquals(2, f.history().items.count { it.confirmedInBin })
        f.assertUnlocked()
    }

    @Test fun externalCancellationDuringIntentFlushClosesEveryDurableIntentWithoutMoving() = runTest {
        val f = Fixture(this)
        f.initialize(3)
        lateinit var job: Job
        var flushes = 0
        f.flushFailure = {
            if (it.name == "events.jsonl" && ++flushes == 2) job.cancel()
            false
        }
        job = launch { f.execute() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, f.moveCount)
        assertTrue(f.history().items.all { it.moveStatus == OperationHistoryMoveStatus.FAILED })
        f.assertUnlocked()
    }

    @Test fun fileDirectoryPathConflictsRejectOnlyAffectedGroups() = runTest {
        val f = Fixture(this)
        f.initialize(3)
        f.independentGroups()
        val original = f.proposal.moves[1]
        val nested = f.proposal.moves.first().sourcePath / "child.jpg"
        val destination = operationDestinationPath(nested, f.bin)!!
        f.proposal = f.proposal.copy(
            groups = f.proposal.groups.map { group ->
                group.copy(members = group.members.map { member ->
                    if (member.file.path == original.sourcePath)
                        member.copy(file = member.file.copy(path = nested, directoryPath = nested.parent!!, name = nested.name))
                    else member
                })
            },
            moves = f.proposal.moves.map {
                if (it == original) it.copy(sourcePath = nested, relativePath = destination.relativeTo(f.bin),
                    destinationPath = destination) else it
            },
        )
        val result = f.execute()
        assertEquals(listOf(FileMoveExecutionStatus.FAILED, FileMoveExecutionStatus.FAILED,
            FileMoveExecutionStatus.COMPLETED), result.items.map { it.status })
        assertTrue(result.items.take(2).all { it.error?.contains("File/directory path collision") == true })
        assertEquals(1, f.moveCount)
        assertEquals(1L, f.store.read(f.bin).statistics.completedMoves)
        assertEquals(1, f.history().items.size)
    }

    @Test fun cachedBadGroupIsReportOnlyAndIndependentGroupsExecuteAcrossBatches() = runTest {
        val f = Fixture(this)
        f.initialize(503)
        f.independentGroups()
        f.proposal = f.proposal.copy(groups = f.proposal.groups.mapIndexed { index, group ->
            if (index == 0) group.copy(findings = listOf(FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP)))
            else group
        })
        val progress = mutableListOf<FileExecutionProgress>()
        val result = f.execute(progress = { progress += it })
        assertEquals(503, result.items.size)
        assertEquals(FileMoveExecutionStatus.FAILED, result.items.first().status)
        assertEquals(502, result.items.count { it.status == FileMoveExecutionStatus.COMPLETED })
        assertTrue(progress.any { it.failed == 1 })
        assertEquals(2510L, progress.last().logicalBytesMoved)
        assertTrue(progress.dropLast(1).none { it.finished })
        assertTrue(progress.last().finished)
        assertEquals(502, f.recorded.size)
        assertEquals(502L, f.store.read(f.bin).statistics.completedMoves)
        val manifests = f.fs.list(f.bin / "$HISTORY_DIRECTORY_NAME/operations").map {
            f.fs.read(it / "manifest.json") { readUtf8() }
        }
        assertTrue(manifests.none { it.contains("\"originalRelativePath\":\"move1.jpg\"") })
    }

    @Test fun failedGroupSkipsLaterCandidatesAcrossBatchesButContinuesIndependentGroups() = runTest {
        val f = Fixture(this)
        f.initialize(503)
        f.independentGroups()
        val first = f.proposal.groups.first()
        f.proposal = f.proposal.copy(groups = f.proposal.groups.filterIndexed { index, _ -> index != 501 }
            .mapIndexed { index, group ->
                if (index == 0) first.copy(members = first.members + f.proposal.groups[501].members.first()) else group
            })
        val attempted = mutableListOf<Path>()
        val result = f.execute(before = {
            attempted += it.sourcePath
            if (it.sourcePath == f.proposal.moves.first().sourcePath)
                throw FileOperationException(FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION))
        })
        assertEquals(FileMoveExecutionStatus.FAILED, result.items[0].status)
        assertEquals(FileMoveExecutionStatus.FAILED, result.items[501].status)
        assertTrue(f.proposal.moves[501].sourcePath !in attempted)
        assertEquals(501, f.moveCount)
        assertEquals(501L, f.store.read(f.bin).statistics.completedMoves)
    }

    @Test fun missingSourceIsFailedWithoutInventoryOrStatisticsAndDoesNotStopIndependentGroup() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        f.independentGroups()
        f.fs.delete(f.proposal.moves.first().sourcePath)
        val result = f.execute()
        assertEquals(listOf(FileMoveExecutionStatus.FAILED, FileMoveExecutionStatus.COMPLETED), result.items.map { it.status })
        assertEquals(listOf(f.proposal.moves.last().sourcePath), f.recorded.map { it.path })
        assertEquals(1L, f.store.read(f.bin).statistics.completedMoves)
        assertEquals(5L, f.store.read(f.bin).statistics.logicalBytesMoved)
    }

    @Test fun executorDoesNotProbeCandidateLocationsBeforePrimitiveMutationBoundary() = runTest {
        val f = Fixture(this)
        f.initialize(3)
        val sources = f.proposal.moves.map { it.sourcePath }.toSet()
        val guarded = object : ForwardingFileSystem(f.fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                if (path in sources && f.moveCount == 0)
                    fail("Candidate location inspected before mutation: $path ($functionName)")
                return path
            }
        }
        val result = f.executor(guarded).execute(f.proposal, f.binding, {}, {}, {})
        assertTrue(result.items.all { it.status == FileMoveExecutionStatus.COMPLETED })
    }

    @Test fun invalidOutsideAndUnportableGroupsRemainReportOnlyWhileGoodGroupMoves() = runTest {
        for (invalid in listOf("/outside/photo.jpg".toPath(), "/archive/../Backup/photo.jpg".toPath(),
            "/archive/unportable:photo.jpg".toPath())) {
            val f = Fixture(this)
            f.initialize(2)
            f.independentGroups()
            val first = f.proposal.moves.first()
            val destination = operationDestinationPath(invalid, f.bin)
            f.proposal = f.proposal.copy(
                groups = f.proposal.groups.mapIndexed { index, group ->
                    if (index == 0) group.copy(members = group.members.mapIndexed { memberIndex, member ->
                        if (memberIndex == 0) member.copy(file = member.file.copy(path = invalid)) else member
                    }) else group
                },
                moves = listOf(first.copy(sourcePath = invalid, destinationPath = destination,
                    relativePath = destination?.relativeTo(f.bin)), f.proposal.moves.last()),
            )
            val result = f.execute()
            assertEquals(listOf(FileMoveExecutionStatus.FAILED, FileMoveExecutionStatus.COMPLETED), result.items.map { it.status })
            assertNotNull(result.items.first().error)
            assertEquals(1, f.history().items.size)
            assertEquals(1, f.recorded.size)
        }
    }

    @Test fun noEffectFailureContinuesIndependentGroupButUncertainAndGlobalFailuresStop() = runTest {
        for (uncertain in listOf(false, true)) {
            val f = Fixture(this)
            f.initialize(2)
            f.independentGroups()
            f.moveFailure = {
                if (f.moveCount == 1) {
                    if (uncertain) {
                        val move = f.proposal.moves.first()
                        f.fs.atomicMove(move.sourcePath, move.destinationPath!!)
                    }
                    throw IOException("native rename failed")
                }
            }
            val result = f.execute()
            assertEquals(if (uncertain) FileMoveExecutionStatus.UNCERTAIN else FileMoveExecutionStatus.FAILED,
                result.items.first().status)
            assertEquals(if (uncertain) FileMoveExecutionStatus.NOT_STARTED else FileMoveExecutionStatus.COMPLETED,
                result.items.last().status)
            assertEquals(if (uncertain) 0 else 1, f.recorded.size)
        }
        val f = Fixture(this)
        f.initialize(2)
        f.independentGroups()
        assertFailsWith<FileOperationException> {
            f.execute(before = {
                throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED), global = true)
            })
        }
        assertEquals(0, f.moveCount)
        f.assertUnlocked()
    }

    @Test fun groupDirectoryConflictDoesNotBlockIndependentGroupPreparationOrMovement() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        f.independentGroups()
        val destination = f.proposal.moves.first().destinationPath!!
        f.fs.createDirectories(destination.parent!!)
        f.fs.write(destination) { writeUtf8("do not overwrite") }
        val result = f.execute()
        assertEquals(listOf(FileMoveExecutionStatus.FAILED, FileMoveExecutionStatus.COMPLETED), result.items.map { it.status })
        assertEquals("do not overwrite", f.fs.read(destination) { readUtf8() })
        assertEquals(1, f.recorded.size)
    }

    @Test fun conflictingSnapshotsAndSharedCandidateDependenciesRejectOnlyConnectedGroups() = runTest {
        val f = Fixture(this)
        f.initialize(3)
        f.independentGroups()
        val first = f.proposal.groups.first()
        val second = f.proposal.groups[1]
        f.proposal = f.proposal.copy(groups = listOf(
            first.copy(findings = listOf(FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP))),
            second.copy(members = second.members + first.members.first()),
            f.proposal.groups.last(),
        ))
        val result = f.execute()
        assertEquals(listOf(FileMoveExecutionStatus.FAILED, FileMoveExecutionStatus.FAILED, FileMoveExecutionStatus.COMPLETED),
            result.items.map { it.status })
        assertEquals(1, f.moveCount)

        val conflicting = Fixture(this)
        conflicting.initialize(2)
        conflicting.independentGroups()
        val original = conflicting.proposal.groups.first()
        conflicting.proposal = conflicting.proposal.copy(groups = conflicting.proposal.groups + original.copy(
            key = "conflicting-copy", members = original.members.mapIndexed { index, member ->
                if (index == 0) member.copy(file = member.file.copy(sizeBytes = 99)) else member
            }))
        val conflictResult = conflicting.execute()
        assertEquals(listOf(FileMoveExecutionStatus.FAILED, FileMoveExecutionStatus.COMPLETED), conflictResult.items.map { it.status })
        assertEquals(1, conflicting.moveCount)
    }

    @Test fun recoveredStatisticsErrorsClearButUnrecoveredEarlierBatchErrorsRemainVisible() = runTest {
        for (recover in listOf(true, false)) {
            val f = Fixture(this)
            f.initialize(501)
            var attempts = 0
            val unavailable = object : ForwardingFileSystem(f.fs) {
                override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                    if (path.name == "statistics.json" && functionName == "sink" &&
                        path.parent?.name?.endsWith("-batch-001") == true) {
                        attempts++
                        if (!recover || attempts == 1) throw IOException("first batch statistics unavailable")
                    }
                    return path
                }
            }
            val result = f.executor(unavailable).execute(f.proposal, f.binding, {}, {}, {})
            assertTrue(result.items.all { it.status == FileMoveExecutionStatus.COMPLETED })
            if (recover) assertNull(result.statisticsError) else assertNotNull(result.statisticsError)
            assertEquals(501L, f.store.read(f.bin).statistics.completedMoves)
            f.assertUnlocked()
        }
    }

    @Test fun longProposalIdsUseACompactRunIdAndKeepTheirOriginalProvenance() = runTest {
        val f = Fixture(this)
        f.initialize(501)
        f.proposal = f.proposal.copy(id = "a".repeat(128))
        val result = f.execute()
        assertEquals(f.proposal.id, result.operationId)
        val operations = f.fs.list(f.bin / "$HISTORY_DIRECTORY_NAME/operations")
        assertEquals(2, operations.size)
        val prefixes = operations.map { it.name.substringBefore("-batch-") }.toSet()
        assertEquals(1, prefixes.size)
        assertEquals(36, prefixes.single().length)
        for (directory in operations) {
            assertTrue(directory.name.length <= 128)
            val manifest = historyObject(f.fs.read(directory / "manifest.json") { readUtf8() })
            assertEquals(f.proposal.id, manifest.historyString("approvedProposalId"))
        }
        assertEquals(501L, f.store.read(f.bin).statistics.completedMoves)
        assertFailsWith<IllegalArgumentException> { f.execute() }
        f.assertUnlocked()
    }

    @Test fun twentyThousandGroupsReachOneGlobalValidationWithoutMovingPhotos() = runTest {
        val f = Fixture(this)
        f.initialize()
        val template = f.proposal.groups.single()
        val survivor = template.members.last()
        val groups = (1..20_000).map { index ->
            val file = template.members.first().file.copy(path = f.archive / "candidate$index.jpg")
            template.copy(key = "group-$index", members = listOf(template.members.first().copy(file = file), survivor))
        }
        f.proposal = f.proposal.copy(groups = groups, moves = groups.map {
            val source = it.members.first().file.path
            val destination = operationDestinationPath(source, f.bin)!!
            f.proposal.moves.single().copy(sourcePath = source, destinationPath = destination,
                relativePath = destination.relativeTo(f.bin))
        })
        var validations = 0
        val failure = assertFailsWith<IllegalStateException> {
            f.execute(validate = { validations++; error("validation reached") })
        }
        assertEquals("validation reached", failure.message)
        assertEquals(1, validations)
        assertEquals(0, f.moveCount)
        f.assertUnlocked()
    }

    @Test fun batchesExecuteUnderOneApprovalWithReadableHistoryAndGlobalProgress() = runTest {
        val f = Fixture(this)
        f.initialize(1001)
        var validations = 0
        var before = 0
        val progress = mutableListOf<FileExecutionProgress>()
        val result = f.execute(validate = { validations++ }, before = { before++ }, progress = { progress += it })
        assertEquals(f.proposal.id, result.operationId)
        assertEquals(1001, result.items.count { it.status == FileMoveExecutionStatus.COMPLETED })
        assertEquals(1, validations)
        assertEquals(1001, before)
        assertEquals(1001, f.recorded.size)
        assertEquals(1001, progress.last().completed)
        assertEquals(5005L, progress.last().logicalBytesMoved)
        assertEquals((0..1001).toList(), progress.map { it.completed }.distinct())
        val directories = f.fs.list(f.bin / "$HISTORY_DIRECTORY_NAME/operations")
        assertEquals(3, directories.size)
        val entries = directories.map { directory ->
            parseHistoryOperation(directory.name, f.binding.identity.archiveId,
                f.fs.read(directory / "manifest.json") { readUtf8() },
                f.fs.read(directory / "events.jsonl") { readUtf8() })
        }
        assertEquals(1001, entries.sumOf { it.items.count { item -> item.confirmedInBin } })
        assertTrue(entries.all { it.journalValid })
        assertEquals(5005L, directories.sumOf {
            historyObject(f.fs.read(it / "statistics.json") { readUtf8() }).historyLong("logicalBytesMoved")
        })
        assertFailsWith<IllegalArgumentException> { f.execute() }
        f.assertUnlocked()
    }

    @Test fun batchBoundaryRejectsFailedGroupAndStopsOnUncertaintyOrInventoryError() = runTest {
        for (mode in 0..2) {
            val f = Fixture(this)
            f.initialize(502)
            if (mode != 2) f.moveFailure = {
                if (f.moveCount == 501) {
                    if (mode == 1) {
                        val move = f.proposal.moves[500]
                        f.fs.atomicMove(move.sourcePath, move.destinationPath!!)
                    }
                    throw IOException("batch boundary failure")
                }
            }
            val result = f.execute(record = {
                if (mode == 2 && it.path == f.proposal.moves[500].sourcePath) error("inventory failure")
                f.recorded += it
            })
            assertEquals(if (mode == 2) 502 else 501, f.moveCount)
            assertEquals(if (mode == 2) 502 else 500,
                result.items.count { it.status == FileMoveExecutionStatus.COMPLETED })
            assertEquals(if (mode == 0) FileMoveExecutionStatus.FAILED else if (mode == 2)
                FileMoveExecutionStatus.COMPLETED else FileMoveExecutionStatus.NOT_STARTED,
                result.items.last().status)
            assertEquals(listOf(FileMoveExecutionStatus.FAILED, FileMoveExecutionStatus.UNCERTAIN,
                FileMoveExecutionStatus.COMPLETED)[mode], result.items[500].status)
            val statistics = historyObject(f.fs.read(
                f.bin / "$HISTORY_DIRECTORY_NAME/operations/${f.proposal.id}-batch-002/statistics.json") { readUtf8() })
            assertEquals(if (mode == 2) 2L else 0L, statistics.historyLong("completedMoves"))
            assertTrue(statistics.getValue("finished").jsonPrimitive.boolean)
            assertFailsWith<IllegalArgumentException> { f.execute() }
            f.assertUnlocked()
        }
    }

    @Test fun cancellationAtBatchBoundaryFinalizesStatisticsAndReservationPreventsRerun() = runTest {
        val f = Fixture(this)
        f.initialize(502)
        lateinit var job: Job
        f.moveFailure = { if (f.moveCount == 501) job.cancel() }
        job = launch { f.execute() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(501, f.moveCount)
        assertEquals(501, f.recorded.size)
        val directory = f.bin / "$HISTORY_DIRECTORY_NAME/operations/${f.proposal.id}-batch-002"
        val statistics = historyObject(f.fs.read(directory / "statistics.json") { readUtf8() })
        assertEquals(1L, statistics.historyLong("completedMoves"))
        assertTrue(statistics.getValue("finished").jsonPrimitive.boolean)
        assertFailsWith<IllegalArgumentException> { f.execute() }
        f.assertUnlocked()
    }

    @Test fun filesystemChecksOccurAtMutationBoundaryAndSelectedSurvivorGroupsAreRejected() = runTest {
        val f = Fixture(this)
        f.initialize(501)
        f.crossFilesystem = f.proposal.moves.last().sourcePath
        val result = f.execute()
        assertEquals(500, f.moveCount)
        assertEquals(FileMoveExecutionStatus.FAILED, result.items.last().status)
        f.assertUnlocked()

        val unsafe = Fixture(this)
        unsafe.initialize(501)
        unsafe.proposal = unsafe.proposal.copy(moves = unsafe.proposal.moves.mapIndexed { index, move ->
            if (index == 0) move.copy(survivorPath = unsafe.proposal.moves.last().sourcePath) else move
        })
        assertTrue(unsafe.execute().items.all { it.status == FileMoveExecutionStatus.FAILED })
        assertEquals(0, unsafe.moveCount)
    }

    @Test fun manifestBytesSplitSmallCandidateCountsAndLongIdsRemainReadable() = runTest {
        val f = Fixture(this)
        f.initialize(3)
        f.proposal = f.proposal.copy(id = "a".repeat(128), groups = f.proposal.groups.map {
            it.copy(explanation = "x".repeat(2 * 1024 * 1024))
        })
        val result = f.execute()
        assertTrue(result.items.all { it.status == FileMoveExecutionStatus.COMPLETED })
        val directories = f.fs.list(f.bin / "$HISTORY_DIRECTORY_NAME/operations")
        assertEquals(2, directories.size)
        assertEquals(3, directories.sumOf { directory ->
            assertTrue(directory.name.length <= 128)
            val manifest = f.fs.read(directory / "manifest.json") { readUtf8() }
            assertTrue(f.fs.metadata(directory / "manifest.json").size!! <= 8L * 1024 * 1024)
            val json = historyObject(manifest)
            assertEquals(f.proposal.id, json.historyString("approvedProposalId"))
            assertEquals(2, json.historyLong("batchCount").toInt())
            parseHistoryOperation(directory.name, f.binding.identity.archiveId, manifest,
                f.fs.read(directory / "events.jsonl") { readUtf8() }).items.count { it.confirmedInBin }
        })
        f.assertUnlocked()
    }

    @Test fun failedInitialValidationStillReservesRunWithoutHistoryEntries() = runTest {
        val f = Fixture(this)
        f.initialize(501)
        assertFailsWith<IllegalStateException> { f.execute(validate = { error("interrupted") }) }
        assertTrue(f.fs.list(f.bin / "$HISTORY_DIRECTORY_NAME/operations").isEmpty())
        assertFailsWith<IllegalArgumentException> { f.execute() }
        assertEquals(0, f.moveCount)
        f.assertUnlocked()
    }

    @Test fun statisticsAreSavedEveryTwentyCompletedMovesAndAtTheEnd() = runTest {
        val f = Fixture(this)
        f.initialize(43)
        val checkpoints = mutableListOf<Triple<Long, Long, Boolean>>()
        var writes = 0
        val path = f.bin / "$HISTORY_DIRECTORY_NAME/operations/${f.proposal.id}/statistics.json"
        val guarded = object : ForwardingFileSystem(f.fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                if (path.name == "statistics.json" && functionName == "sink") writes++
                return path
            }
        }
        fun observe() {
            if (!f.fs.exists(path)) return
            val saved = historyObject(f.fs.read(path) { readUtf8() })
            assertEquals(1L, saved.historyLong("formatVersion"))
            assertEquals(f.proposal.id, saved.historyString("operationId"))
            assertEquals(f.binding.identity.archiveId, saved.historyString("archiveId"))
            val value = Triple(saved.historyLong("completedMoves"), saved.historyLong("logicalBytesMoved"),
                saved.getValue("finished").jsonPrimitive.boolean)
            if (checkpoints.lastOrNull() != value) checkpoints += value
        }
        val result = f.executor(guarded).execute(f.proposal, f.binding, {}, {}, { f.recorded += it },
            onProgress = { observe() })
        observe()
        assertEquals(listOf(Triple(20L, 100L, false), Triple(40L, 200L, false), Triple(43L, 215L, true)), checkpoints)
        assertEquals(3, writes)
        assertNull(result.statisticsError)
        assertEquals(43, f.recorded.size)
    }

    @Test fun finalStatisticsIncludeOnlyConfirmedMovesEvenWhenInventoryUpdatesFail() = runTest {
        for (uncertain in listOf(false, true)) {
            val f = Fixture(this)
            f.initialize(3)
            var attempts = 0
            f.moveFailure = {
                if (++attempts == 2) {
                    if (uncertain) {
                        val move = f.proposal.moves[1]
                        f.fs.atomicMove(move.sourcePath, move.destinationPath!!)
                    }
                    throw IOException("move failed")
                }
            }
            f.execute()
            val saved = historyObject(f.fs.read(
                f.bin / "$HISTORY_DIRECTORY_NAME/operations/${f.proposal.id}/statistics.json") { readUtf8() })
            assertEquals(1L, saved.historyLong("completedMoves"))
            assertEquals(5L, saved.historyLong("logicalBytesMoved"))
            assertTrue(saved.getValue("finished").jsonPrimitive.boolean)
        }
        val f = Fixture(this)
        f.initialize(2)
        f.execute(record = { error("inventory unavailable") })
        val saved = historyObject(f.fs.read(
            f.bin / "$HISTORY_DIRECTORY_NAME/operations/${f.proposal.id}/statistics.json") { readUtf8() })
        assertEquals(2L, saved.historyLong("completedMoves"))
        assertEquals(10L, saved.historyLong("logicalBytesMoved"))
    }

    @Test fun statisticsWriteFailureDoesNotStopMovesAndRemainsVisible() = runTest {
        val f = Fixture(this)
        f.initialize(21)
        var attempts = 0
        val unavailable = object : ForwardingFileSystem(f.fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                if (path.name == "statistics.json" && functionName == "sink") {
                    attempts++
                    throw IOException("statistics unavailable")
                }
                return path
            }
        }
        val result = f.executor(unavailable).execute(f.proposal, f.binding, {}, {}, { f.recorded += it })
        assertTrue(result.items.all { it.status == FileMoveExecutionStatus.COMPLETED })
        assertEquals(21, f.recorded.size)
        assertEquals(2, attempts)
        assertTrue(assertNotNull(result.statisticsError).contains("statistics unavailable"))
        assertEquals(21, f.history().items.count { it.confirmedInBin })
        f.assertUnlocked()
    }

    private class Fixture(scope: TestScope) {
        val fs = FakeFileSystem()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val lane = IoLane("executor-test", dispatcher)
        val archive = "/archive".toPath()
        val bin = "/bin".toPath()
        val store = OperationHistoryStore(lane, fs, dispatcher)
        lateinit var binding: ArchiveBinding
        lateinit var proposal: FileOperationProposal
        val recorded = mutableListOf<FileRecord>()
        val flushed = mutableListOf<Path>()
        var moveCount = 0
        var crossFilesystem: Path? = null
        var moveFailure: (() -> Unit)? = null
        var flushFailure: ((Path) -> Boolean)? = null
        val moveIo = object : FileMoveIo {
            override fun sameFileSystem(source: Path, destinationDirectory: Path) = source != crossFilesystem
            override fun moveNoReplace(source: Path, destination: Path) {
                if (source == crossFilesystem) throw FileMoveRejectedException("Cross-filesystem move refused")
                val metadata = fs.metadataOrNull(source)
                if (metadata?.isRegularFile != true || metadata.symlinkTarget != null)
                    throw FileMoveRejectedException("Source is missing or unsafe")
                if (fs.exists(destination)) throw FileMoveRejectedException("Destination exists")
                moveCount++
                moveFailure?.invoke()
                fs.atomicMove(source, destination)
            }
            override fun flushFile(path: Path) {
                flushed += path
                if (flushFailure?.invoke(path) == true) throw IOException("flush failed")
            }
        }
        suspend fun initialize(count: Int = 1) {
            fs.createDirectory(archive)
            val paths = (1..count).map { archive / "move$it.jpg" } + (archive / "keep.jpg")
            paths.forEach { fs.write(it) { writeUtf8("photo") } }
            binding = store.register(archive, bin)
            val records = paths.map {
                val metadata = fs.metadata(it)
                FileRecord(it, archive, it.name, "jpg", 5, metadata.lastModifiedAtMillis, 0, metadata.createdAtMillis)
            }
            val members = records.map { FileOperationMember(it, FileRecommendationRole.PREFERRED, "reviewed", 100,
                archive / "keep.jpg", null, null, buildJsonObject {}) }
            proposal = FileOperationProposal("approved-operation", 1, 1, archive, bin,
                listOf(FileOperationGroup("group", "category", "Category", "reviewed duplicates", 100, members)),
                records.dropLast(1).map { record ->
                    val destination = operationDestinationPath(record.path, bin)!!
                    ProposedFileMove(record.path, destination.relativeTo(bin), destination, archive / "keep.jpg", 5, emptyList())
                }, emptyList())
        }
        fun independentGroups() {
            val template = proposal.groups.single()
            val keep = template.members.last()
            proposal = proposal.copy(groups = template.members.dropLast(1).mapIndexed { index, member ->
                template.copy(key = "group-$index", members = listOf(member, keep))
            })
        }
        fun executor(fileSystem: FileSystem = fs, filesystemLane: IoLane = lane) =
            FileOperationExecutor(filesystemLane, moveIo, fileSystem, dispatcher, clock = { 1000 })
        suspend fun execute(validate: suspend () -> Unit = {}, before: suspend (ProposedFileMove) -> Unit = {},
                            record: suspend (FileRecord) -> Unit = { recorded += it },
                            progress: (FileExecutionProgress) -> Unit = {}) =
            executor().execute(proposal, binding, validate, before, record, progress)
        fun journal() = fs.read(bin / ".deduplicator-history/operations/approved-operation/events.jsonl") { readUtf8() }
        fun history() = parseHistoryOperation(proposal.id, binding.identity.archiveId,
            fs.read(bin / ".deduplicator-history/operations/approved-operation/manifest.json") { readUtf8() }, journal())
        fun assertUnlocked() {
            assertFalse(fs.exists(archive / "$ARCHIVE_MARKER_NAME.execution.lock"))
            assertFalse(fs.exists(bin / "$HISTORY_DIRECTORY_NAME/.execution.lock"))
        }
    }

    @Test fun movesAndFlushesHistoryBeforeInventoryUpdate() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        val progress = mutableListOf<FileExecutionProgress>()
        var validated = 0
        val result = f.execute(validate = { validated++ }, record = {
            assertTrue(f.journal().contains("COMPLETED"))
            f.recorded += it
        }, progress = { progress += it })
        assertEquals(1, validated)
        assertTrue(result.items.all { it.status == FileMoveExecutionStatus.COMPLETED })
        assertEquals(2, f.recorded.size)
        f.proposal.moves.forEach { assertFalse(f.fs.exists(it.sourcePath)); assertTrue(f.fs.exists(it.destinationPath!!)) }
        assertTrue(f.fs.exists(f.archive / "keep.jpg"))
        assertEquals(2, f.history().items.count { it.confirmedInBin })
        assertTrue(progress.any { it.phase == FileExecutionPhase.SAVING_INTENTS })
        assertTrue(progress.any { it.phase == FileExecutionPhase.SAVING_RESULTS })
        assertTrue(progress.last().finished)
        f.assertUnlocked()
        f.fs.checkNoOpenFiles()
    }

    @Test fun crossFilesystemRejectionHappensInsidePrimitiveWithoutPreflightingOtherCandidates() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        f.crossFilesystem = f.proposal.moves.last().sourcePath
        val result = f.execute()
        assertEquals(listOf(FileMoveExecutionStatus.COMPLETED, FileMoveExecutionStatus.FAILED), result.items.map { it.status })
        assertEquals(1, f.moveCount)
        f.assertUnlocked()
    }

    @Test fun checksSurvivorsAndDestinationsButOnlyInspectsCandidateMetadataAfterRename() = runTest {
        for (change in 0..2) {
            val f = Fixture(this)
            f.initialize()
            when (change) {
                0 -> f.fs.write(f.proposal.moves.single().sourcePath) { writeUtf8("changed") }
                1 -> f.fs.delete(f.archive / "keep.jpg")
                else -> {
                    val destination = f.proposal.moves.single().destinationPath!!
                    f.fs.createDirectories(destination.parent!!)
                    f.fs.write(destination) { writeUtf8("existing") }
                }
            }
            val result = f.execute()
            assertEquals(if (change == 0) FileMoveExecutionStatus.UNCERTAIN else FileMoveExecutionStatus.FAILED,
                result.items.single().status)
            assertEquals(if (change == 0) 1 else 0, f.moveCount)
            assertTrue(f.recorded.isEmpty())
            f.assertUnlocked()
        }
    }

    @Test fun beforeMoveProtectionFailureStopsWithoutMoving() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        val result = f.execute(before = { error("protected") })
        assertEquals(FileMoveExecutionStatus.FAILED, result.items.first().status)
        assertEquals(FileMoveExecutionStatus.FAILED, result.items.last().status)
        assertEquals(0, f.moveCount)
        f.assertUnlocked()
    }

    @Test fun throwAfterRenameIsUncertainAndNeverRecordedAsCompleted() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        f.moveFailure = {
            val move = f.proposal.moves.first()
            f.fs.atomicMove(move.sourcePath, move.destinationPath!!)
            throw IOException("connection lost after rename")
        }
        val result = f.execute()
        assertEquals(FileMoveExecutionStatus.UNCERTAIN, result.items.first().status)
        assertEquals(FileMoveExecutionStatus.NOT_STARTED, result.items.last().status)
        assertTrue(f.recorded.isEmpty())
        assertEquals(OperationHistoryMoveStatus.PENDING, f.history().items.first().moveStatus)
        assertFailsWith<IllegalArgumentException> { f.execute() }
        assertEquals(1, f.moveCount)
        f.assertUnlocked()
    }

    @Test fun noEffectMoveFailureIsJournaledAsFailed() = runTest {
        val f = Fixture(this)
        f.initialize()
        f.moveFailure = { throw IOException("rename refused") }
        assertEquals(FileMoveExecutionStatus.FAILED, f.execute().items.single().status)
        assertEquals(OperationHistoryMoveStatus.FAILED, f.history().items.single().moveStatus)
        assertTrue(f.recorded.isEmpty())
    }

    @Test fun failedIntentFlushNeverMovesAndFailedCompletionFlushIsUncertain() = runTest {
        for (failAt in listOf(2, 3)) {
            val f = Fixture(this)
            f.initialize()
            var journalFlushes = 0
            f.flushFailure = { path -> path.name == "events.jsonl" && ++journalFlushes == failAt }
            val result = f.execute()
            assertEquals(if (failAt == 2) FileMoveExecutionStatus.FAILED else FileMoveExecutionStatus.UNCERTAIN,
                result.items.single().status)
            assertEquals(if (failAt == 2) 0 else 1, f.moveCount)
            assertTrue(f.recorded.isEmpty())
            assertEquals(OperationHistoryMoveStatus.PENDING, f.history().items.single().moveStatus)
            f.assertUnlocked()
        }
    }

    @Test fun inventoryFailureRemainsCompletedAndStopsBatch() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        val result = f.execute(record = { error("database disconnected") })
        assertEquals(FileMoveExecutionStatus.COMPLETED, result.items.first().status)
        assertEquals("database disconnected", result.items.first().postProcessingError)
        assertEquals(FileMoveExecutionStatus.COMPLETED, result.items.last().status)
        assertEquals(2, f.moveCount)
        assertTrue(f.history().items.first().confirmedInBin)
    }

    @Test fun inventoryFailureCountsEntireDurableWindowAndAttemptsAllItsCleanupBeforeStoppingMoves() = runTest {
        val f = Fixture(this)
        f.initialize(51)
        var updates = 0
        val progress = mutableListOf<FileExecutionProgress>()
        val result = f.execute(record = {
            updates++
            if (updates == 1) error("first inventory update failed")
            f.recorded += it
        }, progress = { progress += it })
        assertEquals(50, f.moveCount)
        assertEquals(50, updates)
        assertEquals(49, f.recorded.size)
        assertEquals(50, result.items.count { it.status == FileMoveExecutionStatus.COMPLETED })
        assertEquals("first inventory update failed", result.items.first().postProcessingError)
        assertEquals(FileMoveExecutionStatus.NOT_STARTED, result.items.last().status)
        assertEquals(50, f.history().items.count { it.confirmedInBin })
        assertEquals(OperationHistoryMoveStatus.NOT_STARTED, f.history().items.last().moveStatus)
        assertEquals(50, progress.last().completed)
        assertEquals(250L, progress.last().logicalBytesMoved)
        assertEquals(1, progress.last().remaining)
        f.assertUnlocked()
    }

    @Test fun duplicateExecutionAndExistingLocksAreNeverTakenOver() = runTest {
        val f = Fixture(this)
        f.initialize()
        val lock = f.bin / "$HISTORY_DIRECTORY_NAME/.execution.lock"
        f.fs.createDirectory(lock)
        assertFailsWith<IllegalArgumentException> { f.execute() }
        assertTrue(f.fs.exists(lock))
        assertFalse(f.fs.exists(f.archive / "$ARCHIVE_MARKER_NAME.execution.lock"))
        f.fs.delete(lock)
        f.execute()
        assertFailsWith<IllegalArgumentException> { f.execute() }
        assertEquals(1, f.moveCount)
        f.assertUnlocked()
    }

    @Test fun cancellationDuringValidationPropagatesAndReleasesLocks() = runTest {
        val f = Fixture(this)
        f.initialize()
        assertFailsWith<CancellationException> { f.execute(validate = { throw CancellationException("cancelled") }) }
        assertEquals(0, f.moveCount)
        f.assertUnlocked()
    }

    @Test fun cancellationDuringAnIndividualMoveFinishesItsJournalAndInventoryButNeverStartsTheNext() = runTest {
        val f = Fixture(this)
        f.initialize(2)
        lateinit var job: Job
        f.moveFailure = { job.cancel() }
        job = launch { f.execute() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, f.moveCount)
        assertEquals(1, f.recorded.size)
        assertEquals(OperationHistoryMoveStatus.COMPLETED, f.history().items.first().moveStatus)
        assertEquals(OperationHistoryMoveStatus.FAILED, f.history().items.last().moveStatus)
        val statistics = historyObject(f.fs.read(
            f.bin / "$HISTORY_DIRECTORY_NAME/operations/${f.proposal.id}/statistics.json") { readUtf8() })
        assertEquals(1L, statistics.historyLong("completedMoves"))
        assertEquals(5L, statistics.historyLong("logicalBytesMoved"))
        assertTrue(statistics.getValue("finished").jsonPrimitive.boolean)
        f.assertUnlocked()
    }

    @Test fun metadataAndPhotoFilesystemCallsStayOnFilesystemLane() = runTest {
        val f = Fixture(this)
        f.initialize()
        val ioDispatcher = TrackingIoDispatcher(f.dispatcher)
        val lane = IoLane("guarded-fs", ioDispatcher)
        val guarded = object : ForwardingFileSystem(f.fs) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                assertTrue(ioDispatcher.isRunningTask, "Filesystem IO outside its lane: $functionName")
                if (functionName in setOf("source", "sink", "openReadOnly", "openReadWrite"))
                    assertFalse(path.name.endsWith(".jpg"), "Photo contents must never be opened")
                return path
            }
        }
        val result = f.executor(guarded, lane).execute(f.proposal, f.binding, {}, {}, {})
        assertEquals(FileMoveExecutionStatus.COMPLETED, result.items.single().status)
        f.assertUnlocked()
    }

    @Test fun survivorChangeAfterPreparationAndSourceSymlinksNeverMovePhotos() = runTest {
        val f = Fixture(this)
        f.initialize()
        val result = f.execute(before = {
            f.fs.write(f.archive / "keep.jpg") { writeUtf8("changed survivor") }
        })
        assertNotEquals(FileMoveExecutionStatus.COMPLETED, result.items.single().status)
        assertEquals(0, f.moveCount)
        assertTrue(f.journal().contains("INTENT"))
        assertEquals(OperationHistoryMoveStatus.FAILED, f.history().items.single().moveStatus)
        f.assertUnlocked()

        val linked = Fixture(this)
        linked.initialize()
        val source = linked.proposal.moves.single().sourcePath
        linked.fs.allowSymlinks = true
        linked.fs.delete(source)
        linked.fs.createSymlink(source, linked.archive / "keep.jpg")
        assertEquals(FileMoveExecutionStatus.FAILED, linked.execute().items.single().status)
        assertEquals(0, linked.moveCount)
        linked.assertUnlocked()
    }

    @Test fun manifestFlushFailureLeavesInspectablePlanAndNoPhotoMovement() = runTest {
        val f = Fixture(this)
        f.initialize()
        f.flushFailure = { it.name == "manifest.json" }
        assertFailsWith<IOException> { f.execute() }
        assertEquals(0, f.moveCount)
        assertFailsWith<IllegalArgumentException> { f.execute() }
        f.assertUnlocked()
    }

    @Test fun refusesUnsafeHistoryAncestorsBeforeMetadataWrites() = runTest {
        val f = Fixture(this)
        f.initialize()
        f.fs.allowSymlinks = true
        f.fs.createDirectory("/outside".toPath())
        f.fs.delete(f.bin / "$HISTORY_DIRECTORY_NAME/operations")
        f.fs.createSymlink(f.bin / "$HISTORY_DIRECTORY_NAME/operations", "/outside".toPath())
        assertFailsWith<IllegalArgumentException> { f.execute() }
        assertEquals(0, f.moveCount)
        assertTrue(f.fs.list("/outside".toPath()).isEmpty())
        assertFalse(f.fs.exists(f.bin / BIN_DIRECTORY_MARKER_NAME))
        f.assertUnlocked()
    }

    @Test fun archiveLockSerializesExecutorsEvenWhenTheyUseDifferentBins() = runTest {
        val f = Fixture(this)
        f.initialize()
        val otherBinding = f.store.register(f.archive, "/other-bin".toPath())
        val otherMoves = f.proposal.moves.map {
            val destination = operationDestinationPath(it.sourcePath, otherBinding.binRoot)!!
            it.copy(destinationPath = destination, relativePath = destination.relativeTo(otherBinding.binRoot))
        }
        val otherProposal = f.proposal.copy(id = "other-operation", binRoot = otherBinding.binRoot, moves = otherMoves)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            f.execute(validate = { entered.complete(Unit); release.await() })
        }
        entered.await()
        assertFailsWith<IllegalArgumentException> {
            f.executor().execute(otherProposal, otherBinding, {}, {}, {})
        }
        assertTrue(f.fs.exists(f.archive / "$ARCHIVE_MARKER_NAME.execution.lock"))
        assertEquals(0, f.moveCount)
        release.complete(Unit)
        first.join()
        assertEquals(1, f.moveCount)
        f.assertUnlocked()
    }

    @Test fun manifestLimitRejectsOversizedGroupBeforeCreatingItsMetadataOrMovingAnything() = runTest {
        val f = Fixture(this)
        f.initialize()
        f.proposal = f.proposal.copy(groups = f.proposal.groups.map {
            it.copy(explanation = "x".repeat(8 * 1024 * 1024))
        })
        val result = f.execute()
        assertEquals(FileMoveExecutionStatus.FAILED, result.items.single().status)
        assertEquals(0, f.moveCount)
        assertFalse(f.fs.exists(f.bin / BIN_DIRECTORY_MARKER_NAME))
        assertFalse(f.fs.exists(f.bin / "$HISTORY_DIRECTORY_NAME/operations/${f.proposal.id}"))
        f.assertUnlocked()
    }

    @Test fun manifestPreservesEveryReviewedCategoryAndGroupForSharedCandidates() = runTest {
        val f = Fixture(this)
        f.initialize()
        val original = f.proposal.groups.single()
        f.proposal = f.proposal.copy(groups = listOf(original,
            original.copy(key = "other-group", categoryId = "other-category", categoryName = "Other category",
                explanation = "Additional reviewed evidence")))
        f.execute()
        val manifest = historyObject(f.fs.read(
            f.bin / "$HISTORY_DIRECTORY_NAME/operations/${f.proposal.id}/manifest.json") { readUtf8() })
        val groups = manifest.getValue("groups").jsonArray.map { it.jsonObject }
        assertEquals(listOf("group", "other-group"), groups.map { it.historyString("key") })
        assertEquals(listOf("category", "other-category"), groups.map { it.historyString("categoryId") })
        assertEquals("Additional reviewed evidence", groups.last().historyString("explanation"))
        val item = manifest.getValue("items").jsonArray.single().jsonObject
        assertEquals(listOf("group", "other-group"),
            item.getValue("groupKeys").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(f.history().items.single().confirmedInBin)
    }
}
