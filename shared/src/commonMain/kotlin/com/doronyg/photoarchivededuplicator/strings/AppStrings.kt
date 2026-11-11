package com.doronyg.photoarchivededuplicator.strings

/** Shared English copy; feature namespaces keep labels and templates together. */
object AppStrings {
    internal val inventory = InventoryStrings
    internal val analysis = AnalysisStrings
    internal val recommendations = RecommendationStrings
    internal val duplicates = DuplicateStrings
    internal val review = ReviewStrings
    internal val operations = OperationStrings
    internal val cleanup = CleanupResultsStrings
    val platform = PlatformStrings

    const val unknown = "Unknown"
    const val unavailable = "Unavailable"
    const val none = "None"
    const val loading = "Loading..."
    const val cancel = "Cancel"
    const val refresh = "Refresh"
    const val copy = "Copy"
    const val close = "Close"
    const val remove = "Remove"
    const val showDetails = "Show Details"
    const val hideDetails = "Hide Details"
    const val scanTab = "Scan"
    const val browseTab = "Browse"
    const val duplicatesTab = "Duplicates"
    const val suspectedTab = "Suspected Duplicates"
    const val statisticsTab = "Cleanup Results"
    const val recommendationsTab = "Deletion Recommendations"
    const val fileOperationsTab = "File Operations"
    const val ollamaTab = "Ollama Settings"
    const val exclusionsTab = "Exclusions"
    const val logsTab = "Logs"

    fun field(label: String, value: String) = "$label: $value"
    fun fileSize(bytes: Long, formatted: String) = "$bytes bytes ($formatted)"
    fun utc(value: String) = "$value UTC"
}
