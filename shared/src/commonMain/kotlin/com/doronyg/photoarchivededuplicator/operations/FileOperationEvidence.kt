package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendation
import com.doronyg.photoarchivededuplicator.retention.FileScore
import kotlinx.serialization.json.*

internal fun operationEvidence(
    advice: FileRecommendation,
    exif: ExifRecord?,
    score: FileScore?,
    classification: DirectoryClassificationRecord?,
): JsonObject = buildJsonObject {
    put("recommendationReasons", JsonArray(advice.reasonCodes.map { JsonPrimitive(it.name) }))
    put("decisionConfidenceReasons", JsonArray(advice.retentionDecisionConfidence.reasonCodes.map { JsonPrimitive(it.name) }))
    put("decisionConfidenceExplanation", advice.retentionDecisionConfidence.explanation)
    put("exif", exif?.let { record -> buildJsonObject {
        put("status", record.result.status.name)
        put("inputSizeBytes", record.input.sizeBytes)
        put("inputModifiedAt", record.input.modifiedAt)
        put("inputCreatedAt", record.input.createdAt)
        put("extractedAt", record.extractedAt)
        put("readerId", record.readerId)
        put("readerVersion", record.readerVersion)
        val metadata = record.result.metadata
        put("capturedAt", metadata.capturedAt)
        put("offsetOriginal", metadata.offsetOriginal)
        put("subsecondOriginal", metadata.subsecondOriginal)
        put("make", metadata.make)
        put("model", metadata.model)
        put("width", metadata.width)
        put("height", metadata.height)
        put("orientation", metadata.orientation)
        put("error", record.result.error)
    } } ?: JsonNull)
    put("retention", score?.let { saved -> buildJsonObject {
        put("type", saved.identity.type)
        put("algorithmId", saved.identity.algorithmId)
        put("algorithmVersion", saved.identity.algorithmVersion)
        put("configurationId", saved.identity.configurationId)
        put("value", saved.value)
        put("assessedAt", saved.createdAt)
        put("inputSizeBytes", saved.input.sizeBytes)
        put("inputModifiedAt", saved.input.modifiedAt)
        put("inputCreatedAt", saved.input.createdAt)
        put("factors", buildJsonArray {
            saved.factors.forEach { factor -> add(buildJsonObject {
                put("key", factor.key)
                put("contribution", factor.contribution)
                put("explanation", factor.explanation)
            }) }
        })
    } } ?: JsonNull)
    put("classification", classification?.let { saved -> buildJsonObject {
        put("directoryPath", saved.directoryPath.toString())
        put("originalPath", saved.originalPath)
        put("classification", saved.classification.name)
        put("server", saved.context.baseUrl)
        put("model", saved.context.model)
        put("promptVersion", saved.context.promptVersion)
        put("classifiedAt", saved.classifiedAt)
    } } ?: JsonNull)
}
