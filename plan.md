# Photo Archive Deduplicator

## 1. Goal

Build a Kotlin Multiplatform (KMP) tool to identify redundant files in a large personal photo archive stored on a Synology NAS.

The archive contains hundreds of thousands of files accumulated over approximately 20 years, with an estimated total size of 0.5 TB. Many files are believed to be duplicates resulting from repeated imports, computer backups, and copying between folders.

The tool should eventually automate duplicate cleanup while preserving the intended folder organization and minimizing the risk of losing valuable photos.

This is also a genuine portfolio project demonstrating Kotlin Multiplatform, software architecture, filesystem processing, performance optimization, data persistence, test-driven development, and practical local AI integration using Ollama.

## 2. Core principles

1. **Safety first.** Initial phases must be entirely read-only. Destructive operations are explicitly out of scope until detection and selection logic are well tested.
2. **Automation over manual review.** The system must handle hundreds of thousands of files without requiring manual inspection of every duplicate group.
3. **Conservative duplicate detection.** Filename and file size identify candidates, not definitive duplicates. Content verification is required before classifying files as exact duplicates.
4. **Preserve existing organization.** The tool should identify the likely canonical copy without reorganizing surviving files.
5. **Deterministic core, AI assistance.** Ordinary code handles file identity, persistence, validation, and safety invariants. Ollama assists with folder-convention discovery, ambiguous cases, and auditing.
6. **Test-driven development.** Prefer writing a failing test, implementing the smallest useful change, and refactoring while keeping tests green. Use TDD particularly for duplicate classification, selection policies, persistence, and safety-critical behavior.
7. **Measure before optimizing.** Benchmark NAS and network I/O before committing to a complex hashing strategy or execution environment.
8. **Resumable processing.** Long scans must survive interruptions without unnecessarily reprocessing the entire archive.
9. **Multiplatform from the beginning, platform-specific only where necessary.** Put portable domain logic in shared Kotlin code. Isolate filesystem, database, UI, and platform integrations behind appropriate interfaces.
10. **Useful early deliverables.** Every phase should produce something useful, even if later phases are never implemented.
11. **Never block the UI with application I/O.** Public database/filesystem operations (including container initialization and shutdown) must be suspend APIs that dispatch blocking work internally to IO. UI event handlers launch coroutines; UI callbacks and native UI operations run explicitly on Main. Do not use `runBlocking` on UI or `CoroutineStart.UNDISPATCHED` to start scans. Cancel and join scan work before closing its driver on IO, without holding UI/progress locks across I/O. Keep raw drivers, generated database access, and blocking filesystem implementations private/internal; do not edit generated sources. The compiler enforces suspend call boundaries, not arbitrary effect safety. Modal folder chooser construction/browsing is a native UI operation and stays on Main; application-owned filesystem preparation runs on IO.

## 3. Technology and project structure

### Development environment

- Android Studio.
- Kotlin Multiplatform.
- Gradle with Kotlin DSL.
- Git and automated tests from the beginning.
- GitHub Copilot as a development assistant, with human review of generated code and tests.

### Initial targets

- **Android:** include an Android application target from the initial project skeleton. Use it to establish the KMP structure, exercise shared business logic, and provide a simple interface for inspecting inventory reports or test data.
- **Desktop JVM:** the intended target for scanning the large archive from a PC, accessing NAS folders, running bulk processing, and integrating with local Ollama.
- **NAS-side scanner:** an optional future deployment approach, potentially using Docker. Do not commit to it before benchmarking.

The Android target is not required to scan the entire NAS directly. Its initial purpose is to make Android Studio the starting point for the project and establish a working multiplatform codebase.

### Suggested source-set organization

```text
composeApp/
  src/
    commonMain/
      kotlin/
        model/
        dedup/
        policy/
        inventory/
        reporting/
    commonTest/
      kotlin/
        dedup/
        policy/
        inventory/
    androidMain/
    androidUnitTest/
    androidInstrumentedTest/
    jvmMain/
    jvmTest/
```

The exact layout may vary with the KMP template selected in Android Studio.

Use shared source sets for portable domain models, candidate grouping, duplicate classification, policy decisions, and validation rules.

Keep platform-specific implementations separate where required:

- Filesystem traversal and file access.
- SQLite integration.
- Desktop CLI and user interface.
- Android UI and platform integration.
- Ollama HTTP client and desktop configuration.

Do not force all code into `commonMain`. Use platform abstractions only where they provide a clear benefit, and prefer the simplest implementation that supports the required targets.

### Persistence

Use SQLite for the inventory when its multiplatform integration has been evaluated. SQLDelight is a candidate because it supports Kotlin Multiplatform.

Keep the initial persistence API narrow so the domain and duplicate-detection tests do not depend on a particular database implementation.

### Local diagnostics

Desktop diagnostics are automatically written under `desktopDataDirectory()/logs`, including before inventory startup completes. On macOS this is `~/Library/Application Support/PhotoArchiveDeduplicator/logs`. Windows uses `%APPDATA%/PhotoArchiveDeduplicator/logs` (home fallback); Linux uses `$XDG_DATA_HOME/PhotoArchiveDeduplicator/logs` or `~/.local/share/PhotoArchiveDeduplicator/logs`.

The background single writer appends `diagnostics.log` across restarts and rotates to `diagnostics.1.log` through `diagnostics.4.log`: at most five files of 5 MiB each. Every entry is UTF-8 encoded and flushed. File and console workers receive events independently: a stalled or failing sink cannot block the other. No filesystem operations run in producers or while displaying the folder path.

The Logs screen exposes the folder link even if inventory startup fails, as well as file errors and separate queue-drop/failure counters. Queues are bounded and discard oldest pending events under overload to retain the latest tail; operation beginnings can still be lost under sustained overload or rotation. Publication is batched every 200 ms. Flush empties Java buffers, not OS caches (`fsync` is not used); force-kill can lose queued/in-flight entries, and power failure can also lose OS-cached data. Normal desktop exit attempts a bounded two-second drain. Clear only clears the in-memory view, never saved files.

Logs are local only and can contain filenames and paths; review before sharing. Android continues to use Logcat without duplicate private log files. Persistent logging helps diagnose freezes; it does not establish or fix their cause.

### UI

Start with the Android application target and a minimal interface. A polished desktop UI is a later milestone, not a prerequisite for building the scanning engine.

A CLI remains a good option for the desktop bulk-scanning workflow.

UI choices are stored locally with the inventory on desktop and Android: the
selected tab, scan folder, browse navigation stack, duplicate-search folder and
filters, and log text/level/follow filters. Restoring choices never starts a scan
or opens a file. Search results, running/busy state, and unsaved exclusion edits
are not preferences.

Preference updates change in-memory state immediately and queue serialized,
transactional database writes off the UI thread. Normal shutdown drains pending
writes before closing the database. A force-kill can lose an in-flight or queued
change; saving failures are logged and shown in the UI rather than silently
discarded.

## 4. Test-driven development approach

Use a pragmatic TDD workflow:

1. Write a test describing the required behavior.
2. Run it and verify that it fails for the expected reason.
3. Implement the smallest change that makes the test pass.
4. Refactor without changing the observed behavior.
5. Run the relevant test suite before committing.

Prioritize tests for shared logic in `commonTest`, with platform-specific tests where behavior depends on the filesystem, database, Android, or desktop runtime.

Avoid writing tests that merely duplicate implementation details. Tests should express observable behavior and important invariants.

### Initial test fixtures

Create synthetic archives in temporary directories, containing:

- Identical files with the same name and size.
- Different files with the same name and size.
- Identical content with different names.
- Identical content in different folder hierarchies.
- Files with changed timestamps.
- Unreadable files and interrupted scans.
- Files that change between inventory and verification.
- Filename collisions and ambiguous canonical paths.

Never use the real photo archive as the initial test environment.

For destructive operations in future phases, test the failure and recovery paths before implementing actual cleanup.

## 5. Phased implementation plan

### Phase 0: KMP skeleton and development workflow

- Create the project in Android Studio using a Kotlin Multiplatform template.
- Include Android and desktop JVM targets from the outset if the selected template supports them cleanly.
- Configure Gradle, source sets, and test tasks.
- Establish the package structure and basic domain model.
- Add a small passing test in `commonTest`.
- Demonstrate that shared code is exercised by the Android target.
- Configure Git, a useful README, and a basic build/test workflow.

**Deliverable:** a buildable KMP project with Android and JVM targets, shared code, and a working test-first workflow.

### Phase 1: Read-only inventory

Recursively scan selected archive roots and record:

- Full path and filename.
- File size and extension.
- Modification time.
- Relevant filesystem attributes.
- Scan status and errors.

Persist the inventory in SQLite.

Requirements:
- Support hundreds of thousands of files.
- Handle inaccessible files and disappearing files gracefully.
- Resume interrupted scans.
- Avoid unnecessary rescanning when metadata indicates a file is unchanged.
- Provide progress reporting and scan summaries.
- Never modify source files.

Start with a desktop JVM scanner. Keep its interface independent of the later duplicate-selection and AI logic.

**Deliverable:** a reliable inventory database and a report generated from a read-only scan.

### Phase 2: Duplicate candidate discovery

Group files by filename and size.

The Duplicates tab supports All, Images, Videos, and Documents categories based on
case-insensitive filename extensions. An optional case-insensitive whole-filename
wildcard is combined with the category: `*` matches any text, `?` matches one
character, and all other characters (including `.`) are literal. For example,
`IMG*.jpg` matches `img_123.JPG`. A blank wildcard does not restrict results.
Filters affect only candidate searches in the stored inventory, not scanning,
exclusions, or the exact case-sensitive name-and-size grouping rule. Searches and
filtering run in the repository's database lane, never on the UI thread.

Candidate groups can be sorted by filename (case-sensitive lexicographic order),
individual file size, copy count, and earliest known creation date in the group.
Each criterion has an on/off toggle and ascending/descending direction; left/right
arrows adjust priority, with the leftmost enabled criterion taking precedence.
Unknown creation dates sort last in either direction when that criterion is
compared. Remaining ties use filename then size for deterministic ordering.
Filename sorting is enabled by default; disabling all criteria preserves search
order. Sort changes apply to loaded groups without another database query or any
filesystem reads. Sorting is cancellable and dispatched off the UI thread, and
priority, enabled flags, and directions are remembered across app restarts.

Duplicate result text is selectable. A single **Copy results** action in the result
header copies all loaded groups in their displayed order, including off-screen
items, without adding controls to individual groups. The plain-text report includes
the applied search/sort context, exact byte sizes, full paths, and UTC timestamps,
and identifies the results as unverified candidates. Formatting runs off the UI
thread and does not read files or query the database. Paths are quoted, with line
breaks and other control characters escaped; review local paths before sharing.
Desktop results have a visible draggable scrollbar; Android retains touch scrolling.
Copying does not transmit data to an AI or any other service.

#### Advisory retention scoring

Each candidate copy has a persisted, explainable retention score; higher means a
better retention candidate, not confirmed content identity or permission to delete.
Central configuration defines the weights and matching rules:

| Evidence | Points |
|---|---:|
| Valid `yyyy-MM-dd` directory, optionally followed by a separated descriptive label | +100 |
| Valid `yyyy_MM_dd`, `yyyyMMdd`, or `yyyy/MM/dd` structure | +40 |
| Valid year/month structure (`yyyy/MM`) | +30 |
| Recognizable year-only structure | +10 |
| Known backup/import/synchronization directory | -25 |
| Known temporary directory | -60 |
| Recognized filename date contradicts a recognized complete folder date | -80 |
| Modification UTC date agrees with complete folder date | +2 |
| Creation UTC date agrees with complete folder date | +1 |

Only the strongest organization bonus and strongest location penalty apply.
Known directory names are matched case-insensitively at component boundaries.
Unknown/intentional collection names are neutral. Missing or disagreeing timestamps
are neutral too: neither older folder dates nor older filesystem timestamps win by
themselves. EXIF, content hashes, and AI assessments are not calculated.

The filename evidence introduced in algorithm v2 recognizes valid WhatsApp-style `IMG-yyyyMMdd-WA<number>` and
`VID-yyyyMMdd-WA<number>` filenames (case-insensitive). A filename alone never
incurs a date penalty: there must also be a valid complete folder date to compare.
A mismatching preferred chronological folder contributes +100 -80 = +20.
Timestamp bonuses are suppressed on that mismatch, even if copied timestamps
agree with the folder. Filename dates remain advisory, not verified capture dates.
Invalid/unrecognized filename dates and partial year-only folders do not trigger
the mismatch penalty. Alternative complete date organization still earns +40,
so a recognized mismatch there contributes +40 -80 = -40 before location penalties.

Algorithm v3 separates year/month organization (+30) from year-only organization
(+10). A consistent partial structure therefore outranks a contradictory preferred
full-date folder (+20). Full dates retain their +40/+100 bonuses. Year/month
evidence takes precedence over a later year-only component, and valid months are
restricted to `01` through `12`. No timestamp bonus is needed to earn the +30.
Previous algorithm versions remain stored independently.

`PhotoSync` is a known synchronization directory. A whole `WhatsApp` word in
a directory name (including `WhatsApp Images` or `WhatsApp_backup`) is a backup/
import/synchronization signal, matched case-insensitively. Substrings such as
`WhatsApplication` do not match. Multiple backup/sync matches incur only one -25
penalty; temporary locations retain the stronger -60 penalty instead.

Scores within five points of the maximum are ambiguous; tied leaders are labeled
as ties. A group with missing/outdated scores cannot receive a leading-copy
recommendation. All files remain visible. Tap the compact score line to expand
its contributing factors; Copy results includes scores and explanations.
Within each group, current scores sort from highest to lowest, with full-path
order breaking ties. Missing/outdated scores follow all current scores, including
negative ones. This member ordering does not change the selected group-order
criteria or ambiguity labels, and copied results use the same displayed order.

Find candidates reuses current assessments and calculates only missing/stale ones
from stored metadata on a background dispatcher. Recompute scores explicitly
refreshes the current result set. Sorting or merely opening the tab does not score
files. Bounded database write batches recheck the inputs before saving; metadata
that changed during computation is rejected and the UI requests a fresh search.
Cancellation rolls back the active batch; previously committed batches are reusable.
The inventory view is a snapshot, not a live replacement for another search after
a concurrent scan changes its records.

Score persistence is separate from inventory and supports independent score types,
algorithm/model identifiers, versions, and configurations. Updating the current
retention assessment preserves other algorithms, versions, and score types.
Database schema v4 adds score/factor tables through migration `3.sqm`; current v3
inventories upgrade in place without rescanning. Keep earlier migration files,
and prioritize populated current-to-next migration regression coverage while the
schema is evolving.

For each group, report:
- Number of candidates.
- All candidate paths.
- Total bytes represented by the group.
- Relevant metadata differences.

Do not classify these candidates as exact duplicates yet.

Measure how many files and groups survive the initial filter.

**Deliverable:** a candidate report showing the potential scope of deduplication.

### Phase 3: Exact duplicate verification

Verify candidate contents before declaring them exact duplicates.

Start with full SHA-256 hashing of candidate files. Benchmark this approach on the actual NAS.

If network I/O is expensive, evaluate:
- Comparing initial and final content samples to reject candidates early.
- Caching hashes for unchanged files.
- Parallel processing where benchmarks justify it.
- Running a lightweight scanner locally on the NAS.

Sampling is a filter only, never proof of equality.

For the initial cleanup policy, a file is eligible for consideration only when filename, size, and verified content all match.

**Deliverable:** a trustworthy report of exact duplicate groups, with no file modifications.

### Phase 4: Canonical-copy selection

Develop a deterministic policy engine to recommend which copy should be retained.

Potential evidence:
- Configured archive roots.
- Folder hierarchy and naming conventions.
- Backup, download, and temporary-folder indicators.
- File metadata and available EXIF information.
- Explicit user-defined preferences.

The engine must support an undecided result. It must not automatically assume that the oldest, newest, or largest file is the correct one.

Store the recommendation and its reasoning in the inventory.

**Deliverable:** a report of recommended keepers, redundant copies, confidence, and unresolved groups.

### Phase 5: Ollama integration

Use Ollama as an advisory component, not as the authority over file identity or destructive actions.

Potential uses:
- Discover recurring folder conventions from aggregated inventory statistics.
- Propose candidate canonical-copy rules.
- Rank ambiguous locations using supplied metadata and paths.
- Audit proposed rules and batches for suspicious outcomes.

Requirements:
- Keep AI integration behind a replaceable interface.
- Use structured, schema-validated outputs.
- Record model decisions, explanations, and model version.
- Evaluate AI suggestions against deterministic baselines.
- Begin in shadow mode: record suggestions without allowing them to influence actions.
- Never allow a model response alone to authorize deletion.

**Deliverable:** an evaluation showing whether AI improves selection quality or reduces unresolved cases.

### Phase 6: Operational interface and performance

Based on real usage, add only the necessary UI and operational features.

Potential additions:
- Android UI for inspecting status and reports.
- Desktop UI for managing large scans and reviewing results.
- Search, filtering, and duplicate-group navigation.
- Scan and processing statistics.
- Export to CSV or JSON.
- Improved cancellation and resumption.
- Optional NAS-side scanning.

Avoid duplicating complex business logic between Android and desktop. Both should use shared domain logic wherever practical.

**Deliverable:** a usable tool for operating on the entire archive.

### Phase 7: Cleanup, explicitly deferred

This phase is outside the scope of the initial implementation.

Only after detection, selection, and recovery have been thoroughly validated should cleanup be considered.

Future safeguards:
- Independent validation of the keeper and redundant file immediately before an operation.
- Persistent action manifest and audit log.
- Collision-safe quarantine.
- Revalidation of file contents.
- Tested recovery and interruption handling.
- Small, bounded batches.
- Delayed permanent deletion as a separate operation.

No permanent deletion should be implemented in the early phases.

## 6. Performance strategy

Do not assume that hashing 0.5 TB is necessarily the dominant cost. File enumeration, SMB latency, candidate frequency, and disk throughput may matter more.

Collect measurements for:
- Total files and total bytes.
- Files enumerated per second.
- Candidate group count and candidate bytes.
- Bytes read during verification.
- Hash throughput.
- Total elapsed time.
- Cache hit rate on subsequent scans.

Use these results to decide whether partial reads, hash caching, parallelism, or NAS-side scanning are justified.

Keep performance tests separate from deterministic correctness tests where appropriate.

## 7. Portfolio objectives

The repository should demonstrate sound engineering judgment rather than simply the use of multiple technologies.

Document:
- Architecture and key design decisions.
- The TDD workflow and meaningful regression tests.
- Benchmark methodology and measured results.
- Correctness guarantees and known limitations.
- Tests for failure handling and recovery.
- AI evaluation against a deterministic baseline.
- Actual results from the archive, without publishing private paths or personal photos.

Prioritize a reliable, measurable solution over an elaborate initial architecture.

## 8. Initial milestone and acceptance criteria

The first milestone is complete when:

- Android Studio builds the KMP project.
- Android and desktop JVM targets compile.
- Shared code has a working test suite.
- The test workflow is documented.
- The desktop scanner can inventory a representative portion of the NAS.
- The inventory can be persisted and resumed.
- Candidate groups can be identified by filename and size.
- Candidate contents can be verified to produce exact-duplicate reports.
- Reports can be exported without modifying any source files.

Everything else follows from the evidence collected in this phase.
