package com.doronyg.photoarchivededuplicator.operations

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationSource
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.serialization.json.*
import okio.Path
import okio.Path.Companion.toPath

internal class FileOperationRepository(private val database: FilesDatabase, private val lane: IoLane) {
    suspend fun save(proposal: FileOperationProposal) = lane.run("save file operation proposal") {
        database.fileOperationProposalQueries.insertProposal(
            id = proposal.id,
            createdAt = proposal.createdAt,
            validatedAt = proposal.validatedAt,
            archiveRoot = proposal.archiveRoot?.toString(),
            binRoot = proposal.binRoot.toString(),
            validationStatus = proposal.validationStatus.name,
            groupCount = proposal.groups.size.toLong(),
            candidateCount = proposal.moves.size.toLong(),
            candidateBytes = proposal.candidateBytes,
            snapshotJson = encodeProposalSnapshot(proposal),
        )
    }

    suspend fun recent(): List<FileOperationSummary> = lane.run("recent file operation proposals") {
        database.fileOperationProposalQueries.selectRecent(50).awaitAsList().map {
            FileOperationSummary(it.id, it.createdAt, it.archiveRoot?.toPath(), it.binRoot.toPath(),
                FileOperationValidationStatus.valueOf(it.validationStatus), it.groupCount, it.candidateCount, it.candidateBytes)
        }
    }

    suspend fun load(id: String): FileOperationProposal = lane.run("load file operation proposal") {
        val row = database.fileOperationProposalQueries.selectById(id).awaitAsOne()
        val payload = Json.parseToJsonElement(row.snapshotJson).jsonObject
        require(payload.getValue("version").jsonPrimitive.int == 1) { "Unsupported proposal snapshot version" }
        val proposal = FileOperationProposal(
            row.id, row.createdAt, row.validatedAt, row.archiveRoot?.toPath(), row.binRoot.toPath(),
            payload.getValue("groups").jsonArray.map { decodeGroup(it.jsonObject) },
            payload.getValue("moves").jsonArray.map { decodeMove(it.jsonObject) },
            decodeFindings(payload.getValue("findings")),
        )
        val legacyGroups = payload.getValue("groups").jsonArray.all { "findings" !in it.jsonObject }
        val legacyStatus = if (proposal.moves.isEmpty() || proposal.findings.isNotEmpty() ||
            proposal.moves.any { it.findings.isNotEmpty() }) FileOperationValidationStatus.NEEDS_ATTENTION
            else FileOperationValidationStatus.READY
        val statusMatches = proposal.validationStatus.name == row.validationStatus ||
            (legacyGroups && legacyStatus.name == row.validationStatus)
        check(statusMatches &&
            proposal.groups.size.toLong() == row.groupCount && proposal.moves.size.toLong() == row.candidateCount &&
            proposal.candidateBytes == row.candidateBytes) { "Inconsistent proposal summary" }
        proposal
    }
}

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String) = getValue(key).jsonPrimitive.contentOrNull
private fun JsonObject.optionalLong(key: String) =
    getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.long }
private fun JsonObject.path(key: String) = string(key).toPath()
private fun JsonObject.optionalPath(key: String) = optionalString(key)?.toPath()

private fun JsonObjectBuilder.putPath(key: String, path: Path?) = put(key, path?.toString())

internal fun encodeProposalSnapshot(proposal: FileOperationProposal): String = buildJsonObject {
    put("version", 1)
    put("groups", buildJsonArray {
        proposal.groups.forEach { group -> add(buildJsonObject {
            put("key", group.key)
            put("categoryId", group.categoryId)
            put("categoryName", group.categoryName)
            put("explanation", group.explanation)
            put("duplicateConfidence", group.duplicateConfidence)
            put("findings", encodeFindings(group.findings))
            group.duplicateProvenance?.let { provenance ->
                put("duplicateProvenance", buildJsonObject {
                    put("source", provenance.selection.source.name)
                    put("files", JsonArray(provenance.selection.files.map(::encodeFile)))
                    put("selectedPaths", provenance.selection.selectedPaths?.let { paths ->
                        JsonArray(paths.sortedBy { it.toString() }.map { JsonPrimitive(it.toString()) })
                    } ?: JsonNull)
                    put("engineEvidence", provenance.engineEvidence)
                })
            }
            put("members", buildJsonArray {
                group.members.forEach { member -> add(buildJsonObject {
                    put("file", encodeFile(member.file))
                    put("role", member.role.name)
                    put("explanation", member.explanation)
                    put("decisionConfidence", member.decisionConfidence)
                    putPath("replacement", member.retainedReplacementPath)
                    put("capturedAt", member.capturedAt)
                    put("classification", member.classification?.name)
                    put("evidence", member.evidence)
                }) }
            })
        }) }
    })
    put("moves", buildJsonArray {
        proposal.moves.forEach { move -> add(buildJsonObject {
            putPath("source", move.sourcePath)
            putPath("relative", move.relativePath)
            putPath("destination", move.destinationPath)
            putPath("survivor", move.survivorPath)
            put("sizeBytes", move.sizeBytes)
            put("findings", encodeFindings(move.findings))
        }) }
    })
    put("findings", encodeFindings(proposal.findings))
}.toString()

private fun encodeFile(file: FileRecord) = buildJsonObject {
    putPath("path", file.path)
    putPath("directoryPath", file.directoryPath)
    put("name", file.name)
    put("extension", file.extension)
    put("sizeBytes", file.sizeBytes)
    put("modifiedAt", file.modifiedAt)
    put("createdAt", file.createdAt)
    put("lastScanned", file.lastScanned)
}

private fun decodeFile(value: JsonObject) = FileRecord(
    value.path("path"), value.path("directoryPath"), value.string("name"), value.optionalString("extension"),
    value.getValue("sizeBytes").jsonPrimitive.long, value.optionalLong("modifiedAt"),
    value.getValue("lastScanned").jsonPrimitive.long, value.optionalLong("createdAt"),
)

private fun encodeFindings(findings: List<FileOperationFinding>) = buildJsonArray {
    findings.forEach { finding -> add(buildJsonObject {
        put("code", finding.code.name)
        putPath("path", finding.path)
        put("detail", finding.detail)
    }) }
}

private fun decodeFindings(value: JsonElement) = value.jsonArray.map {
    val finding = it.jsonObject
    FileOperationFinding(FileOperationIssueCode.valueOf(finding.string("code")),
        finding.optionalPath("path"), finding.optionalString("detail"))
}

private fun decodeGroup(group: JsonObject) = FileOperationGroup(
    group.string("key"), group.string("categoryId"), group.string("categoryName"),
    group.string("explanation"), group.getValue("duplicateConfidence").jsonPrimitive.int,
    group.getValue("members").jsonArray.map {
        val member = it.jsonObject
        FileOperationMember(decodeFile(member.getValue("file").jsonObject),
            FileRecommendationRole.valueOf(member.string("role")), member.string("explanation"),
            member.getValue("decisionConfidence").jsonPrimitive.int, member.optionalPath("replacement"),
            member.optionalString("capturedAt"), member.optionalString("classification")?.let(DirectoryClassification::valueOf),
            member.getValue("evidence").jsonObject)
    },
    group["findings"]?.let(::decodeFindings).orEmpty(),
    group["duplicateProvenance"]?.takeUnless { it == JsonNull }?.jsonObject?.let { provenance ->
        DuplicateDeletionProvenance(DuplicateDeletionSelection(
            DeletionRecommendationSource.valueOf(provenance.string("source")),
            provenance.getValue("files").jsonArray.map { decodeFile(it.jsonObject) },
            provenance.getValue("selectedPaths").takeUnless { it == JsonNull }?.jsonArray
                ?.map { it.jsonPrimitive.content.toPath() }?.toSet()),
            provenance.getValue("engineEvidence").jsonObject)
    },
)

private fun decodeMove(move: JsonObject) = ProposedFileMove(
    move.path("source"), move.optionalPath("relative"), move.optionalPath("destination"),
    move.optionalPath("survivor"), move.getValue("sizeBytes").jsonPrimitive.long,
    decodeFindings(move.getValue("findings")),
)
