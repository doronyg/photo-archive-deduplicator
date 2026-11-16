# Photo Archive Deduplicator

This is the detailed product and engineering reference for contributors and
coding assistants. Preserve the rationale, behavioral contracts, edge cases,
and safety constraints here when changing the application. This is not a
portfolio summary or a checklist of features still to build.

[roadmap.md](roadmap.md) summarizes delivered work, current limitations, and
future priorities. The sections below describe implemented behavior unless
explicitly marked as future work or investigation. Algorithm and schema version notes
explain how the current design evolved; they are not instructions to revert to
an earlier version.

When this document and the implementation disagree, investigate the relevant
code and tests before changing either. An inconsistency may be a documentation
error or an implementation regression, not an intentional product decision.

## 1. Goal

Build a Kotlin Multiplatform (KMP) tool to identify potentially redundant files
in a large personal photo archive stored on a NAS.

The motivating archive contains more than 200,000 images accumulated over
approximately 20 years. Repeated imports, backup services, and copying between
folders have produced many possible duplicates.

The tool should make analysis and explicitly approved bulk cleanup practical
while preserving the intended folder organization and minimizing the risk of
losing valuable photos. Routine analysis should use a persistent inventory
rather than repeatedly traversing the archive, especially over network storage.

Current recommendations and Bin moves use metadata evidence, not hashes or
byte comparison. Content verification remains a possible future investigation,
not a committed feature or prerequisite for the existing reviewed workflow.
Metadata matches must not be described as confirmed identical files.

## 2. Core principles

1. **Safety first.** Scanning and analysis must not modify archive files. Moves require saved proposals, fresh validation, and explicit approval. Permanent deletion and automatic file-operation recovery are not implemented.
2. **Practical bulk review.** The system must handle hundreds of thousands of files without requiring individual interaction with every duplicate group. Bulk selection does not bypass validation or explicit execution approval.
3. **Conservative duplicate detection.** Filename and file size identify candidates, not definitive duplicates. Content verification is required before classifying files as exact duplicates.
4. **Preserve existing organization.** The tool should identify the likely canonical copy without reorganizing surviving files.
5. **Deterministic core, AI assistance.** Ordinary code handles candidate grouping, persistence, validation, and safety invariants. Ollama supplies optional directory classifications; it does not verify content or authorize operations.
6. **Test-driven development.** Prefer writing a failing test, implementing the smallest useful change, and refactoring while keeping tests green. Use TDD particularly for duplicate classification, selection policies, persistence, and safety-critical behavior.
7. **Measure before optimizing.** Benchmark NAS and network I/O before committing to a complex hashing strategy or execution environment.
8. **Continue interrupted work.** Long scans must survive interruptions without unnecessarily reprocessing the entire archive.
9. **Multiplatform from the beginning, platform-specific only where necessary.** Put portable domain logic in shared Kotlin code. Isolate filesystem, database, UI, and platform integrations behind appropriate interfaces.
10. **Useful increments.** Each change should produce something useful without depending on speculative future features.
11. **Never block the UI with application I/O.** Public database/filesystem operations (including container initialization and shutdown) must be suspend APIs that dispatch blocking work internally to IO. UI event handlers launch coroutines; UI callbacks and native UI operations run explicitly on Main. Do not use `runBlocking` on UI or `CoroutineStart.UNDISPATCHED` to start scans. Cancel and join scan work before closing its driver on IO, without holding UI/progress locks across I/O. Keep raw drivers, generated database access, and blocking filesystem implementations private/internal; do not edit generated sources. The compiler enforces suspend call boundaries, not arbitrary effect safety. Modal folder chooser construction/browsing is a native UI operation and stays on Main; application-owned filesystem preparation runs on IO.

## 3. Technology and project structure

### Development environment

- Android Studio.
- Kotlin Multiplatform.
- Gradle with Kotlin DSL.
- Git and automated tests from the beginning.
- GitHub Copilot as a primary implementation tool, with human-defined requirements, architectural direction, and review of generated code and tests.

### Targets

- **Android:** shared UI and business logic, with MediaStore-based scanning and Android-specific permissions and integrations.
- **Desktop JVM:** the intended target for scanning the large archive from a PC, accessing NAS folders, running bulk processing, and integrating with local Ollama.

The Android target is not required to scan the entire NAS directly. A NAS-side
scanner remains an optional deployment idea, not an implemented target; do not
commit to it before benchmarking.

### Source-set organization

```text
app/          Android host
desktopApp/   Desktop JVM host
shared/
  src/
    commonMain/   Shared UI, application/domain logic, SQLDelight schema
    commonTest/   Shared behavior and persistence tests
    androidMain/  Android platform implementations
    jvmMain/      Desktop platform implementations
    jvmTest/      Desktop-specific tests
```

Use shared source sets for portable domain models, candidate grouping, duplicate classification, policy decisions, and validation rules.

Keep platform-specific implementations separate where required:

- Filesystem traversal and file access.
- SQLite integration.
- Desktop host, native folder chooser, and platform UI integrations.
- Android UI and platform integration.
- HTTP engines and platform configuration where required; the Ollama protocol client is shared.

Do not force all code into `commonMain`. Use platform abstractions only where they provide a clear benefit, and prefer the simplest implementation that supports the required targets.

### Persistence

The inventory uses SQLite through SQLDelight, with platform-specific drivers.
Store file metadata and reusable analysis results so searches, filtering, and
reports do not repeat expensive archive filesystem operations. The inventory
is a stored snapshot, not a live view of external changes.

Keep persistence APIs narrow so domain and duplicate-detection tests do not
depend on a particular database implementation.

### Local diagnostics

Desktop diagnostics are automatically written under `desktopDataDirectory()/logs`, including before inventory startup completes. On macOS this is `~/Library/Application Support/PhotoArchiveDeduplicator/logs`. Windows uses `%APPDATA%/PhotoArchiveDeduplicator/logs` (home fallback); Linux uses `$XDG_DATA_HOME/PhotoArchiveDeduplicator/logs` or `~/.local/share/PhotoArchiveDeduplicator/logs`.

The background single writer appends `diagnostics.log` across restarts and rotates to `diagnostics.1.log` through `diagnostics.4.log`: at most five files of 5 MiB each. Every entry is UTF-8 encoded and flushed. File and console workers receive events independently: a stalled or failing sink cannot block the other. No filesystem operations run in producers or while displaying the folder path.

The Logs screen exposes the folder link even if inventory startup fails, as well as file errors and separate queue-drop/failure counters. Queues are bounded and discard oldest pending events under overload to retain the latest tail; operation beginnings can still be lost under sustained overload or rotation. Publication is batched every 200 ms. Flush empties Java buffers, not OS caches (`fsync` is not used); force-kill can lose queued/in-flight entries, and power failure can also lose OS-cached data. Normal desktop exit attempts a bounded two-second drain. Clear only clears the in-memory view, never saved files.

Logs are local only and can contain filenames and paths; review before sharing. Android continues to use Logcat without duplicate private log files. Persistent logging helps diagnose freezes; it does not establish or fix their cause.

### UI

Android and desktop share the Compose UI. Keep platform-specific interaction
differences, such as native folder selection and visible desktop scrollbars,
at the platform boundary.

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

User-facing English copy lives in the shared `strings` package, accessed through
`AppStrings` and feature namespaces. Keep labels and interpolated text templates
there; calculations, filtering, state transitions and evidence selection remain
in their existing logic. Storage keys, reason codes, parser patterns, protocol
fields and model prompts are not UI strings.

Keep screens focused on useful labels, counts, progress, errors and actual
decision evidence. Avoid repeated disclaimers and cache/implementation narration.
Deletion Recommendations has one concise review notice, not warnings on every
group/file. Model/server/profile details belong in Ollama settings and its saved
results viewer, not file recommendations or statistics. Collapsed file rows show
the decision; expanding them reveals the actual explanation and confidence.
Changes to presentation copy must not change detection, scoring, safety, or
the meaning of persisted score-factor explanations.

## 4. Test-driven development approach

Use a pragmatic TDD workflow:

1. Write a test describing the required behavior.
2. Run it and verify that it fails for the expected reason.
3. Implement the smallest change that makes the test pass.
4. Refactor without changing the observed behavior.
5. Run the relevant test suite before committing.

Prioritize tests for shared logic in `commonTest`, with platform-specific tests where behavior depends on the filesystem, database, Android, or desktop runtime.

Avoid writing tests that merely duplicate implementation details. Tests should express observable behavior and important invariants.

### Test fixtures

Create synthetic archives in temporary directories, containing:

- Identical files with the same name and size.
- Different files with the same name and size.
- Identical content with different names.
- Identical content in different folder hierarchies.
- Files with changed timestamps.
- Unreadable files and interrupted scans.
- Files that change between inventory and verification.
- Filename collisions and ambiguous canonical paths.

For changes to file operations, test failure and interruption handling before
expanding mutation behavior. Do not use the real archive for automated tests.

## 5. Functional design and behavioral contracts

### KMP foundation and development workflow

Maintain buildable Android and desktop JVM targets, shared code, and focused
regression tests. Keep build and test commands documented in the README.

### Read-only inventory

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

Keep scanner interfaces independent of duplicate-selection and AI logic.

**Deliverable:** a reliable inventory database and a report generated from a read-only scan.

### Duplicate candidate discovery

Group files by filename and size.

The Duplicates tab supports All, Images, Videos, and Documents categories based on
case-insensitive filename extensions. An optional case-insensitive whole-filename
wildcard is combined with the category: `*` matches any text, `?` matches one
character, and all other characters (including `.`) are literal. For example,
`IMG*.jpg` matches `img_123.JPG`. A blank wildcard does not restrict results.
Filters affect only candidate searches in the stored inventory, not scanning,
exclusions, or the exact case-sensitive name-and-size grouping rule. Searches and
filtering run in the repository's database lane, never on the UI thread.

Regular and suspected candidate groups share sorting controls for filename
(case-sensitive lexicographic order), individual file size, copy count, earliest
known creation date, and recommendation type. Mixed-name suspected groups use
their lexicographically smallest filename, independent of retention member order.
Each criterion has an on/off toggle and ascending/descending direction; left/right
arrows adjust priority, with the leftmost enabled criterion taking precedence.
Unknown creation dates and unavailable recommendations sort last in either
direction when their criterion is compared. Recommendation ascending order is
`KEEP_ONE`, `KEEP_MULTIPLE`, `REVIEW`, `PROTECTED`; the last action remains reserved,
not an implemented protection policy. Remaining ties use filename then size;
suspected ties also use capture timestamp, offset, and path for deterministic order.
Filename sorting is enabled by default; disabling all criteria preserves search
order. Sort changes apply to loaded groups without another database query or any
filesystem reads. Sorting is cancellable and dispatched off the UI thread, and
priority, enabled flags, and directions are remembered independently for each tab
across app restarts. Recommendation sorting is disabled by default; older saved
priorities are preserved with the new criterion appended disabled. Sorting never
reassesses scores, extracts EXIF, or changes retention-ranked order within a group.

Duplicate result text is selectable. A single **Copy results** action in the result
header copies all loaded groups in their displayed order, including off-screen
items, without adding controls to individual groups. The plain-text report includes
the applied search/sort context, exact byte sizes, full paths, and UTC timestamps,
and identifies the results as unverified candidates. Formatting runs off the UI
thread and does not read files or query the database. Paths are quoted, with line
breaks and other control characters escaped; review local paths before sharing.
Desktop results have a visible draggable scrollbar; Android retains touch scrolling.
Copying does not transmit data to an AI or any other service.

Browse provides **Copy file paths** for the current directory and all subdirectories;
at **All roots**, it copies every stored file. The text contains one unquoted full
path per line, sorted by path, with no headers or metadata. It includes retained
inventory in excluded trees and never inspects source files. Database retrieval
and text formatting run off the UI thread. Paths containing line breaks produce
an explicit error rather than ambiguous or silently omitted entries.

#### Advisory retention scoring

Each candidate copy has a persisted, explainable retention score; higher means a
better retention candidate, not confirmed content identity or permission to delete.
Central configuration defines the weights and matching rules:

| Evidence | Points |
|---|---:|
| Valid `yyyy-MM-dd` directory, optionally followed by a separated descriptive label | +100 |
| Valid `yyyy_MM_dd`, `yyyyMMdd`, or `yyyy/MM/dd` structure | +40 |
| Valid year/month structure (`yyyy/MM`) | +45 |
| Recognizable year-only structure | +10 |
| Known backup/import/synchronization directory | -25 |
| Known temporary directory | -60 |
| Recognized filename date contradicts a recognized complete folder date | -40 |
| Usable stored EXIF date confirms an alternative complete folder date | Organization raised from +40 to +90 |
| Usable stored EXIF date contradicts a complete folder date, without a matching filename date | -40 |
| Strict, valid backup-style UTC timestamp suffix in filename | -20 |
| Trailing three-digit copy suffix such as `-001` through `-999` | -10 |
| Modification UTC date agrees with complete folder date | +2 |
| Creation UTC date agrees with complete folder date | +1 |

Only the strongest organization bonus and strongest location penalty apply.
Known directory names are matched case-insensitively at component boundaries.
Unknown/intentional collection names are neutral. Missing or disagreeing timestamps
are neutral too: neither older folder dates nor older filesystem timestamps win by
themselves. Retention scoring consumes stored EXIF when available, but never opens
source files, calculates content hashes, or invokes AI assessments.

The filename evidence introduced in algorithm v2 recognizes valid WhatsApp-style `IMG-yyyyMMdd-WA<number>` and
`VID-yyyyMMdd-WA<number>` filenames (case-insensitive). A filename alone never
incurs a date penalty: there must also be a valid complete folder date to compare.
A mismatching preferred chronological folder contributes +100 -40 = +60.
Timestamp bonuses are suppressed on that mismatch, even if copied timestamps
agree with the folder. Filename dates remain advisory, not verified capture dates.
Invalid/unrecognized filename dates and partial year-only folders do not trigger
the mismatch penalty. Alternative complete date organization still earns +40,
so a recognized mismatch there contributes +40 -40 = 0 before location penalties.

Algorithm v3 introduced separate year/month evidence. Current weights award +45
for year/month organization and +10 for year-only organization. Year/month evidence
takes precedence over a later year-only component, and valid months are restricted
to `01` through `12`. The +45 intentionally exceeds unconfirmed alternative full-date
organization (+40); preferred organization stays +100, and a contradicted preferred
folder now earns +60 before location adjustments. A year/month backup copy earns
+45 -25 = +20. No timestamp or EXIF bonus is needed for these year/month points.
Earlier algorithm versions and weight configurations remain stored independently.

Algorithm v4 adds stored EXIF capture-date evidence. Compare the original
camera-local calendar date directly with a complete folder date; do not infer a
timezone or convert to UTC. Only successful, inventory-matched extractions with a
valid full `DateTimeOriginal` in 1971 through 2099 supply evidence. Missing, invalid,
epoch-default, failed, and stale metadata are neutral. Partial folders are not
treated as complete dates.

An EXIF-confirmed alternative full-date folder earns 90 organization points;
preferred `yyyy-MM-dd` organization remains 100. Existing location penalties and
weak timestamp bonuses still apply. A reliable EXIF/folder mismatch incurs
-40 and suppresses timestamp support, but a recognized filename date matching the
folder protects all its existing points. This protection recognizes WhatsApp names
and conventional `yyyyMMdd`, `yyyy-MM-dd`, `yyyy_MM_dd`, `IMG_`, `VID_`, and `PXL_`
date/timestamp names.
It does not introduce new filename-only penalties for camera names.

Matching EXIF also protects a folder from a conflicting advisory WhatsApp filename
date. When both EXIF and filename dates disagree with the folder, apply only the
stronger date penalty once. Factors explain both contradictions and protections.
Persist the usable EXIF date as a zero-contribution evidence factor, allowing
cached assessments to detect relevant metadata changes without another schema
migration. Re-extraction with the same usable date need not recalculate a score.
Earlier score versions remain available.

Algorithm v5 extends the shared per-file scorer for both duplicate screens.
Recognize valid terminal ` (yyyy_MM_dd HH_mm_ss UTC)` decorations before the
extension and three-digit numbered suffixes such as `-001`. Each filename cue
counts at most once; both cues can combine for -30. These are conservative copy
indicators, not proof: ordinary camera counters such as `IMG_0001` and malformed
backup timestamps are neutral. Strip recognized copy decorations only when
interpreting the underlying filename date; backup timestamps never become capture
date evidence. Original filename/folder-date protection still applies.

Scores are independent of peers, so the same persisted score is reused in regular
and suspected groups. The suspected screen ranks and labels all members of its
size/capture-time group together, across filenames, rather than choosing a leader
separately for every filename. Plain copies in undated collection folders may tie;
do not infer which collection name is more correct.

Failed or limit-exceeded EXIF is treated exactly like absent EXIF, not as negative
evidence. For example, `20191210_104020-ANIMATION.gif` scores +20 in
`MobileBackup/.../Google Photos/2019/12` and -60 in `.Picasa3Temp_1`, regardless of
whether extraction was attempted unsuccessfully.

`PhotoSync` is a known synchronization directory. A whole `WhatsApp` word in
a directory name (including `WhatsApp Images` or `WhatsApp_backup`) is a backup/
import/synchronization signal, matched case-insensitively. Substrings such as
`WhatsApplication` do not match. Multiple backup/sync matches incur only one -25
penalty; temporary locations retain the stronger -60 penalty instead.

Scores within ten points of the maximum are ambiguous; tied leaders are labeled
as ties. A group with missing/outdated scores cannot receive a leading-copy
recommendation. All files remain visible. Tap the compact score line to expand
its contributing factors; Copy results includes scores and explanations.
Within each group, current scores sort from highest to lowest, with full-path
order breaking ties. Missing/outdated scores follow all current scores, including
negative ones. This member ordering does not change the selected group-order
criteria or ambiguity labels, and copied results use the same displayed order.

In the Duplicates tab, Find candidates reuses current assessments and calculates only missing/stale ones
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

Schema v8 (`7.sqm`) normalizes the two largest sources of repeated score data.
`scoreProfile` stores each exact type/algorithm/version/configuration identity once;
`factorDefinition` stores each exact factor key/explanation pair once. Assessments
and ordered contributions use integer references, while the repository reconstructs
unchanged domain models through batched joins. All historical assessments, score
IDs, input fingerprints, values, timestamps, factor ordering and explanation text
survive. Contributions remain per assessment, and failed/cancelled saves roll back
new dictionary entries too. This does not normalize AI profiles or file paths,
rescore files, prune history or automatically compact the database. Freed pages
are reusable; reclaiming disk space requires separate backup-and-shutdown maintenance.

For each group, report:
- Number of candidates.
- All candidate paths.
- Total bytes represented by the group.
- Relevant metadata differences.

Do not classify these candidates as exact duplicates yet.

#### Deterministic group recommendations

Analyze complete regular and suspected groups using stored files, current EXIF,
and existing retention assessments. This is a separate, pure rule-based layer:
group policy must not silently change individual retention weights, scoring
ambiguity labels, or persistence. No AI calls, filesystem reads, content
verification, or file deletion/modification/movement is involved.

Return a group action (`KEEP_ONE`, `KEEP_MULTIPLE`, or `REVIEW`), qualitative
metadata evidence strength, per-file roles, human-readable explanations, and
stable reason codes. Derive preferred, redundant-candidate, and must-preserve
members from those roles. Explicitly supplied protected paths force `PROTECTED`
and preserve every member before ordinary ranking; the optional input defaults
empty. This explicit protection input is separate from cached AI directory
classifications described below. Do not hardcode collection names or infer
explicit protection from directory names.

Use the retention scorer's shared filename parser and directory evidence rather
than interpreting explanation text or duplicating detection/scoring rules.
Recognized copy suffixes normalize a filename family; stem case is preserved and
only extension case is normalized for this comparison. Paths are not case-folded.

Assess group duplicate confidence separately from each file's retention-decision
confidence. Do not assign a retention-confidence number to the whole group.
Scores are integer heuristics from 0 to 100, never calibrated probabilities or
content verification. Bands are Very low (0-24), Low (25-49), Medium (50-74),
High (75-89), and Very high (90-100).

Choose the highest current individual retention score as the reference; equal
scores use case-sensitive path order. If no current score is available, use a
deterministic reference and explain that limitation; missing/stale retention
assessments still prevent a retention recommendation. Compare every other file
against the reference. Default adjustable `DuplicateConfidenceConfig` weights:

| Evidence | Points |
|---|---:|
| Matching normalized filename | 30 |
| Matching non-null modification timestamp, exact stored milliseconds | 25 |
| Matching valid original capture timestamp | 30 |
| Matching camera make / model | 5 / 5 |
| Matching complete positive width and height | 5 |

Equal size is a prerequisite and earns zero points. Missing, invalid, failed, or
stale EXIF is unavailable, earns no points, and is not contradictory. Do not
renormalize the total when fields are absent. Camera fields compare trimmed
case-sensitive values. Filename and modification-time differences lose their
supporting points but are not content contradictions. Recognized suffixes use the
existing strict parser; arbitrary filename similarity earns nothing.

Original offsets and subseconds retain camera-local semantics, without timezone
conversion or correlated extra points. Equivalent subsecond fractions compare
equally. Reliable known EXIF contradictions, including capture time, offset,
subseconds, make/model, dimensions, and orientation, cap duplicate confidence at
24 and block redundant-candidate recommendations. Missing offset evidence is not
a contradiction, but the existing mixed-known/unknown offset safety gate remains.
Use the weakest reference comparison, not an average, for group confidence.
Check known contradictions throughout the group too, so a field missing from the
reference cannot hide conflicting values in two other members.

At least High duplicate confidence (75 by default) and current retention
assessments are required before identifying redundant candidates. Within each
directory, keep the highest-scoring representative; equal scores use stable path
order and explicitly acknowledge that any representative is acceptable. A unique
unsuffixed original is no longer required. Never claim that a small numerical
score gap establishes a decisively better original.

Across locations, prefer a unique capture-date-matching chronological directory
only when all remaining representatives have date-only directories under the same
organization root. Fall back to a reliable recognized filename date when capture
date evidence is unavailable and duplicate confidence otherwise qualifies. Other
directories must contradict that capture date. Date-prefixed event labels,
collections below a date, configured backup/temporary locations, and different
organization roots do not qualify. Respect conflicting filename/folder-date
evidence from the existing retention assessment; a higher score cannot override
it. Never choose between two locations that both match the capture date merely
because one date format scores higher.

Keep one representative in each non-standard/special ordinary collection.
As an explicit user-approved exception, locations classified by the existing
backup/import/sync rules are expendable when a strictly higher-scoring retained
replacement exists and passes the duplicate-evidence rules. This applies to all
descendants, not just flat roots: for example Google Photos albums and
PhotoSync/<device>/Camera. Reuse `RetentionConfig.backupDirectoryNames` and
`backupDirectoryWords` through the scorer's directory evidence, without adding
a second list or changing individual scores. Temporary locations are not promoted
to replacement keepers by this rule.

Choose a stronger surviving replacement in stable score/path order, excluding
known date-folder contradictions; do not infer a better location from a higher
score in a contradicted dated folder. Flatten local-copy dependencies to the final
survivor, validate its direct duplicate evidence, and waive collection preservation
only for backup directories actually replaced. Multiple ordinary collections
remain represented. Backup-only groups retain their best available copy; equal
copies in separate backup locations remain preserved when neither is stronger.
Explain unreduced backups as necessary fallback copies, not special collections.
Explicit protection and insufficient/conflicting evidence still block candidates.
No archive files are deleted or changed.

Ambiguous chronological locations likewise
return `KEEP_MULTIPLE`, explaining why each location's representative must remain.
Insufficient or contradictory duplicate evidence returns `REVIEW` with no
redundant candidates, even when the conditional retention decision is clear.

Per-file retention-decision confidence measures support for that file's assigned
role, not whether it is the best original. Preservation uses explicit rule tiers:
95 for a uniquely supported date location or a meaningful same-directory score
advantage beyond the existing ambiguity margin; 90 for a designated same-directory
survivor or a collection representative, including unresolved dated locations;
100 for obeying explicit protection. Equal copies can have a high-confidence
representative assignment without claiming the chosen copy is better.

Every redundant candidate names `retainedReplacementPath`: an actual surviving
preferred/preserved file in this group, never another candidate. Compare the
candidate directly against that replacement using cached metadata, not merely
against the group's original reference. Require High duplicate evidence for that
pair too; if it is weak/conflicting, return `REVIEW` for the whole group. Its
per-file decision confidence is the minimum of retention justification, direct
replacement duplicate confidence, and group duplicate confidence. Include the
direct comparison evidence, explicit dependency, and unverified-content warning
in the explanation and codes. Unresolved review files have confidence 0 and no
replacement; do not display a high hypothetical removal confidence.

Validate the complete plan before publication: every original member must be
accounted for with current metadata, at least one must remain, required
collections must retain representatives, and explicitly protected files cannot
be candidates. Reject missing/self/out-of-group/candidate-to-candidate replacement
dependencies, low-confidence candidates, and action/role inconsistencies. Reject
an unsafe plan as `REVIEW` with all original members preserved, per-file confidence
0, cleared dependencies, and `RETENTION_PLAN_INVALID` explanations. Reassessment
rebuilds every dependency when the designated keeper changes. This is advisory
plan validation, not a deletion mechanism.

Both duplicate tabs show a group summary and per-file explanations with expandable
normalized names and reason codes. Both tabs' Copy results include the displayed
recommendation snapshot, group duplicate confidence, per-file decision confidence
and replacement dependencies, reference/comparison evidence, explanations and
reason codes without changing file order. Calculations run off the UI
dispatcher; source/evidence changes invalidate previous presentation snapshots.
Active scans/EXIF queues or loading evidence produce review-only results.
Live group recommendations are derived rather than persisted as a separate
assessment. Saved file-operation proposals capture the reviewed evidence
independently; they must not be confused with current live recommendations.

#### User-triggered Ollama directory classification

Prompt version 3 expands protection examples to personal/family document archives,
creative work, printing selections and multilingual path hierarchies. It uses no
preferred-root list. Older prompt results remain viewable but are not reused.

Retention algorithm v6 adds a deterministic `.picasaoriginals` directory-component
rule (case-insensitive, including descendants): an additional -10, counted once,
on top of other location penalties. For example, Backup/-25 plus this rule/-10
totals -35. The directory is a backup location even without AI. AI hints cannot
promote it above a qualifying retained normal copy or protect it as an independent
collection; explicit file protection, duplicate confidence, direct replacement
evidence and last-survivor safeguards still apply. Existing score rows become
outdated through the normal algorithm/configuration identity mechanism; no schema
change or archive read is needed.

The Ollama settings screen configures a server base URL and model identifier.
Both default to empty; configuration alone never
contacts a server. An explicit Fetch models action calls `GET {baseUrl}/api/tags`
using the entered URL, without requiring a selected model or sending directory
data. Users can choose an installed model from the returned list or enter an
identifier manually, then save settings. Android declares network access and
permits cleartext traffic app-wide to support user-configured HTTP endpoints.
This is not a host-scoped network permission. The app lazily creates its shared Ktor client only
when the user fetches models or starts a classification job and closes it after cancelling requests
at shutdown.

Ollama settings also offers View saved directory results, a database-only viewer
with directory/classification/model text filtering. Current saved settings shows
only matching server/model/prompt results; All saved results includes older
profiles and prompt versions, explicitly marked as not used by current settings.
Show the directory, classification, timestamp, model, server and prompt version.
Reloading never contacts Ollama; an open viewer updates as job results persist.
The results list uses the shared desktop scrollbar (touch scrolling on Android).
Copy results exports the entire filtered snapshot, including off-screen rows,
in displayed order with profile/timestamp details and escaped paths and text.
AI classifications affect group retention policy, not numeric individual-file
retention scores or duplicate-confidence calculations.

Both duplicate screens offer classification, explicit refresh, cancellation and
retry-failed controls. Scope is every complete group matching the current screen
filters, including off-screen members and members outside a matching folder.
Snapshot the selected dataset at invocation; later navigation/filter changes
must not add new request targets. Deduplicate normalized directory paths and
submit no directories outside those groups. Retry failures intersects the
current selected dataset. Display unique/missing directories and completed,
cached, remaining and failed counts; technical errors are visible.

Use sequential requests through Ktor to `POST {configuredBaseUrl}/api/chat`.
Send `stream: false`, `think: false`, temperature zero and an object JSON Schema requiring
exactly `classification`, with only `PREFERRED`, `BACKUP`, `PROTECTED`, `UNKNOWN`.
The model receives directory full path and directory name
only: never filenames, contents/images, EXIF, scores or duplicate-group
information. Paths/names are untrusted data, not instructions. The system prompt
forbids invented contents, inherited backup labels and unsupported generic
protection; insufficient evidence should produce UNKNOWN. PREFERRED is inferred
from the supplied path, with no configured-root restriction. Reject malformed/extra result fields and unsupported
values. Bound requests/responses and propagate cancellation. Technical failures
are not successful UNKNOWN classifications and must remain retryable.

Local diagnostics use the `Ollama` tag for request IDs, method/URL, request and
response body previews, HTTP status, elapsed time, failures and cancellation.
Body previews are escaped and capped at 4 KiB; they may contain directory paths
and model output. Job logs include cache skips, persisted results and final counts.
In the Logs screen, filter by `Ollama` and select `D+` to include payload previews.

Persist each successful result immediately in `directoryAiClassification`.
Migration `6.sqm` adds this independent table (schema 6 to 7), preserving all
inventory, EXIF, scores and preferences. Store normalized directory identity,
original path, one of the four labels, server/model, prompt version and
classification time. Prompt version 2 removes the preferred-root configuration
and does not reuse version-1 classifications. The legacy roots-key column remains
empty for new records to preserve schema compatibility; old rows are left intact.
Legacy preferred-root preferences are ignored. Reuse only matching profiles;
explicit refresh bypasses cache, while a failed refresh retains the last valid
row. No AI explanations or confidence values are requested or stored.

The deterministic group engine consumes cached exact-directory classifications.
No AI data preserves existing behavior. An exact AI UNKNOWN or PROTECTED result
vetoes deterministic backup expendability for that directory; BACKUP labels
themselves never propagate to descendants. PROTECTED retains the collection's
representative, including under a backup ancestor, without forbidding justified
same-directory copy reduction. Existing explicit protected paths retain priority.
PREFERRED is accepted as an AI location hint and supports group location choices;
BACKUP alone never authorizes a redundant candidate. Duplicate confidence,
direct retained-replacement evidence, collection/last-copy safety and individual
retention scores remain independent of model output.

Read cached labels and refresh recommendations as successful rows are persisted,
without starting network calls from browsing, Statistics, copying or scoring.
Classification jobs must not block ordinary browsing or depend on the filesystem
lane. All HTTP tests use MockEngine/fakes and all migration/persistence tests use
test databases, never the production archive database.

#### Recommendation statistics

The **Cleanup Results** tab follows Suspected duplicates and is the primary
statistics entry point. Keep the existing `STATISTICS` preference key. A
**Detailed statistics** card opens the original selectable report within the
same tab, with a back button; re-entering the tab starts at Cleanup Results.

The overview uses the existing inventory summary for current non-Bin file count
and logical archive bytes, including inventoried folders excluded from analysis.
Show adaptive decimal size units. Potential savings are the journal-confirmed
logical bytes currently recorded in the selected Bin, not lifetime bytes moved,
permanent deletion or physical space reclaimed. Show recorded Bin file counts
and confirmed move-event counts separately; events are not unique files.
Missing/unreadable history is unavailable, not zero. Preserve partial-history
information in the presentation model, but keep history diagnostics in the
detailed report rather than adding explanatory notes to the overview.
Permanent deletions are not tracked, so omit that metric rather than infer a count.
Use direct labels without promotional subtitles or explanatory disclaimers.

Give AI retention impact a prominent before/after presentation, with changed
groups, changed file decisions, withdrawn candidates (kept or left for review) and
new candidates. Keep regular/suspected views separate and never add them.
Use the existing comparison against deterministic-only recommendations, not new
scoring or a second analysis algorithm. Do not expose diagnostic classification
coverage or transition tables here. Show an unconfigured state when AI settings
are absent.

The compact duplicate visualization uses the existing path-deduplicated union
and registered file types. Its archive percentage uses the overview's full
inventory denominator; matching files can include retained copies, and analysis
still respects exclusions. All cards, charts and text use theme colors and
responsive layouts, supporting the system light/dark theme without new
charting dependencies. A dedicated presentation model maps existing snapshots;
no schema, scanning or detection changes are required.

The detailed report retains plain selectable text for
regular duplicate candidates and suspected matches separately, across all stored
roots and excluding excluded folders, independent of the duplicate-tab filters.
Regular grouping must span roots rather than combining per-root query results,
so copies in different roots still form a single filename/size group.

Start with inventory totals for All and each registered file category (Images,
Videos, Documents and Other). Show total files, possible duplicate files and the
percentage within that category, including zero-valued categories. Possible
duplicates are distinct paths in the union of regular and stored suspected groups,
including retained copies, not only deletion candidates. Count overlapping
members once. Inventory totals use the same exclusions as duplicate datasets.
Aggregate total counts by stored extension in SQL rather than loading every file;
use the existing extension classification rules, including dot-file handling.
The category registry drives all rows so additional registered types need no new
presentation or aggregation branches. Use only database records, not scans,
EXIF extraction, score calculation or server requests.

For each section show group count, file count, and total MB; break down file counts
and bytes both by the whole group's recommendation and by each file's individual
role. A Keep one group's totals include all members, not just its preferred copy.
Include image, video, document, and other categories overall and within each
recommendation/role bucket, reusing the existing extension classifications.
Show zero-valued categories too. MB uses decimal 1,000,000-byte units and two
rounded decimal places; figures represent file sizes, not reclaimable space.
The cohorts can overlap, so their totals must not be added together.

For each dataset, show AI coverage using only exact-directory classifications
matching the current saved server, model and prompt version. Count unique
classified/missing directories and their member-file counts/bytes, with separate
PREFERRED, BACKUP, PROTECTED and UNKNOWN buckets. UNKNOWN is classified evidence,
not missing. Report fully classified, partially classified and unclassified groups.
Excluded/out-of-dataset directories and historical profiles must not enter totals.

Compare current cached-AI recommendations with a deterministic-only run using the
same groups, EXIF and saved retention assessments. Count changed groups, changed
group actions, changed file roles/replacement dependencies, action/role transitions,
new and withdrawn redundant candidates and before/after candidate counts/bytes.
Changing only explanations or confidence does not count as a decision change.
Replacement changes between still-redundant candidates are reported separately.
Candidate bytes are advisory, not verified reclaimable space. No AI settings or
no matching labels means zero coverage/impact and unchanged deterministic results.
Keep server/model/prompt details in Ollama settings rather than the statistics
report; the statistics still use only the current profile.

Build a cancellable, background, database-only snapshot using stored EXIF,
stored current-profile retention scores, and the same recommendation engine.
Missing or stale evidence stays Review; opening or refreshing statistics never
extracts EXIF or computes/saves retention assessments. Do not publish partially
loaded results. Wait while scanning or EXIF processing is active, then reload
after completion/cancellation; allow explicit refresh when inactive. No charting
library, file operations, new persistence tables, or schema migration is needed.

#### Deletion Recommendations review screen

The Deletion Recommendations tab is separate from duplicate investigation.
Category cards have stable IDs, names, explanations, group counts and proposed
file counts/bytes. The selected category is explicit; empty categories remain
selectable. Overall totals deduplicate groups and proposed paths across categories.
Sizes are advisory, not verified reclaimable space.

The first category is **same-directory metadata matches**, following the approved
metadata-only scope: the inventory has no hashes/byte comparisons or persisted
final deletion plans. These are explicitly **unverified review candidates**, not
proven identical files or authorization to delete. Use complete stored suspected
image groups (strictly larger than 100,000 bytes); never trim a cross-directory
group down to a qualifying subset. Require differing names, equal stored sizes,
current valid matching capture timestamps/EXIF and current saved retention scores.
The existing recommendation engine and retention-plan safety guard must identify
exactly one retained preferred copy with every other member a high-confidence
redundant candidate explicitly depending on that survivor. Missing, outdated,
contradictory or weak evidence does not qualify.

The separate **Metadata matches within each folder** category extends this rule
to complete multi-directory suspected groups without changing the original
category. Partition by exact directory, recompute each local decision using the
existing engine and pass it through the original same-directory provider. Combine
qualifying local plans into one complete KEEP_MULTIPLE result per original group;
retain all files in singleton or nonqualifying directories. Every candidate must
depend on a survivor in its own directory, and every original directory must
remain represented. Confidence describes the weakest qualifying local comparison,
not cross-directory identity. Any protected member still excludes the whole group.
File Operations reloads this provider through the existing fresh-recommendation
validation path; no new execution policy, scoring algorithm or schema is needed.

The **EXIF and folder date mismatch** category consumes complete regular duplicate
groups, without imposing the suspected-image size threshold. Require current
successful EXIF with equal camera-local capture dates and recognized complete
directory dates for every member, using the scorer's existing date recognition
and comparison helpers. The existing decision must designate the matching-folder
copy as preferred and explicitly connect mismatching-folder redundant candidates
to it. Only those mismatching-folder candidates are shown in this category; the
complete original decision and evidence remain available. Do not infer a new
keeper, score files again, require differing AI classifications, or add camera,
dimension or filesystem-timestamp eligibility rules. The existing engine's safety
and confidence decisions remain authoritative.

Any current-profile PROTECTED directory on any member excludes the entire group
from this category, including protection on a retained or otherwise unshown copy.
Both directories may be PREFERRED. Each displayed file includes the recognized
directory date, EXIF capture date and explicit match/mismatch comparison alongside
the unchanged retention explanation/confidence. These remain unverified review
candidates, not proof of identical contents.

The **Regular duplicates (100/100)** category uses complete regular groups with
exactly equal filenames and sizes, current saved EXIF/retention evidence, and an
existing group duplicate confidence of exactly 100/100 without contradictions.
Confidence alone does not choose a survivor or make a file redundant: only safe
KEEP_ONE/KEEP_MULTIPLE decisions with actual redundant candidates qualify.
Display every engine-designated survivor and candidate with the original
explanations and replacement dependencies. A current-profile PROTECTED member
excludes the entire group from this category, even if it would be retained.
A 100/100 metadata score is not byte verification. This category has no
suspected-image size threshold.

Three additional categories consume complete regular and suspected groups:

- **Every folder retains a copy** accepts existing KEEP_MULTIPLE plans only when
  every original directory retains at least one group member. It does not invent
  extra keepers or modify the engine's decisions.
- **Redundant copies in one protected folder** is a narrow exception to the
  protected-group restriction: the entire group must occupy one current-profile
  PROTECTED directory, duplicate confidence must be high (at least 75/100) without
  contradictions, and a safe engine plan must retain one copy in that directory.
  The exception is explicit in saved operation evidence and revalidated before
  execution; it does not permit manual protected selections or cross-directory
  protected moves.
- **Preferred/backup groups with a score-100+ keeper** requires every directory to
  be explicitly classified PREFERRED or BACKUP and at least one engine-retained
  file to have a current retention score of at least 100. All existing engine
  keepers remain, including multiple keepers.

All three require current saved EXIF/retention evidence, high duplicate confidence
without contradictions, valid retained-replacement dependencies and actual
redundant candidates. The protected exception is unavailable to other categories.
Categories may overlap; overall totals continue to deduplicate groups and paths.

The database-only service derives advice from saved evidence with the existing
engine, not a second decision policy. It never extracts EXIF, recomputes/saves
retention scores or contacts Ollama. Only labels matching current saved settings
apply. Scan/EXIF activity and evidence/profile refreshes immediately hide obsolete
results; reload on completion/revision changes, with explicit manual refresh.

Providers registered in the service own category eligibility and return generic
review groups; the UI does not switch on category IDs. Additional providers can
use different rules, explanations and multiple survivors without changing the
presentation. Providers select regular or suspected duplicate evidence; the service
loads only requested sources and keeps their complete group decisions separate.

#### File Operations: saved dry-run proposals

The configurable DeduplicatorGarbageBin preserves each file's full original path
beneath the Bin, with no source archive root needed for dry runs. Operation/file
IDs are not inserted into photo destination paths. This photo destination
mapping is separate from the portable archive-relative paths used in history.
Registered Bin roots are mandatory archive exclusions, including already
inventoried content and roots inside the archive. Previous roots remain excluded
after configuration changes.

Whole groups containing a current-profile PROTECTED member are excluded from
ordinary deletion categories. The single-protected-directory category is the
only explicit exception for moving redundant copies within a protected
directory; preserve its saved policy and revalidate it before execution.
Manual duplicate selections do not receive that exception. Explicit protected
paths remain separate from the AI directory-label policy.

Users select existing recommendations and prepare a saved proposal in the File
Operations tab. It records exact candidates/destinations, original survivors,
complete group evidence and metadata-validation findings. Proposal preparation
performs no photo-content reads, hashing, rescoring, AI calls, or writes to the
source archive or Bin. Saving the proposal writes to the local database.
Independent AI review is not implemented; its absence is explicitly disclosed
when approving real runs.
Restoration and permanent deletion remain unavailable.
Missing Bin directories do not block a dry run. The UI suggests an editable Bin
location. Metadata validation uses one depth-first pass over retained/destination paths, caches
both observations and ancestor verdicts, and skips descendants of missing or unsafe
parents. Shared ancestors are not re-walked per file. Phase/progress logs explain
long runs without logging each file, and every new validation gets a fresh cache.
Candidate source locations are not preflighted; missing candidates do not block
dry runs. Group-level findings reject the group and any groups sharing its
candidates, while independent valid groups remain eligible for approval.
The UI presents each file's suggested stay/move destination, provides copyable
results and keeps saved evidence collapsed until opened.

#### Operation history infrastructure

Passed dry runs can explicitly prepare destination directories without moving
photos. Preparation verifies the archive/Bin registration and writes a
`.deduplicator-bin-directory.json` mapping marker in the Bin and each destination
ancestor. Scans discover markers and persist whole-subtree exclusions, skipping
known Bins and never descending below a marked Bin. Inventory browsing, duplicate
analysis and retention scoring use stored exclusions without filesystem sweeps;
externally added markers take effect at the next scan. Directory preparation
does not approve moves or count as completed file operations.

Dry runs with eligible groups can execute after an explicit upper-bound file-count
approval. The executor verifies archive/Bin identity, fresh recommendations and
retained/destination metadata. Source safety and same-filesystem capability are
checked only by the actual move primitive at the mutation boundary. Per-file checks stay
targeted, with no whole-inventory filesystem sweep. The move primitive rejects
existing destinations and requires same-filesystem atomic rename, with no
cross-filesystem copy/delete fallback. This is not a race-proof no-clobber
guarantee: an unrelated external writer can create a destination after its
absence check, and atomic rename may replace it. App-owned execution locks
coordinate cooperating writers only; source/Bin paths and mounts must not be
changed concurrently by external tools. An immutable portable manifest and
flushed intent/completion journal preserve evidence independently of inventory.
Flushing metadata files does not fsync parent directories or guarantee remote
storage durability through power loss.
Confirmed completed moves remove only the matching source inventory, EXIF,
profile scores/factors and directory count; the retained copies stay unchanged.
Invalid groups and confirmed no-effect failures skip that group's remaining
candidates while independent groups continue. Missing or failed moves do not
count or alter inventory/cache records. Global errors, uncertain outcomes and
journal or database reconciliation failures stop the whole run. Interrupted
intents and stale locks require manual reconciliation, not automatic retries.
Journal flushing is bounded by `JOURNAL_BATCH_SIZE = 50`, separate from manifest
batch sizes: force all window intents, perform sequential verified moves, then
force individual terminal records together before inventory/statistics updates.
A crash can leave the current window requiring manual reconciliation.
The Cancel operation button stops after the current file, finalizes the partial
window and unused intents, and releases locks without rolling back completed moves.
Blocking filesystem calls can delay stopping. Live progress remains visible while
scrolling and distinguishes verified moves from durably recorded moves, with phase,
path, counts, logical bytes, elapsed time and approximate ETA. Phase/count changes
publish immediately; redundant progress is throttled. History refreshes after execution.
Both Deletion Recommendations and File Operations support Select all and Clear
selection, including off-screen groups. Recommendation selection is scoped to the
current category; File Operations retains its available group pool when cleared.
Duplicates and Suspected Duplicates can add explicit file selections or complete
engine group decisions to the same File Operations job. Manual selection overrides
keep/review advice but never protected groups or the last unselected copy.
Group selection retains all engine keepers, including multiple copies. Saved
selection provenance enables fresh validation without requiring the narrower
Deletion Recommendations categories; both modes still require a dry run and
explicit approval to move files into the Bin.
Changing selection invalidates the displayed dry run, not saved snapshots.
Large selections use automatic bounded execution batches under one explicit
approval, retaining global identity/configuration checks and one execution lock
session. Group-local failures remain isolated across batch boundaries. Indexed
per-file validation and lazy list rows avoid repeatedly traversing
tens of thousands of groups.
Completed-move counts and logical bytes are checkpointed per operation every 20
moves and at the end, including failures and orderly cancellation. These
best-effort JSON summaries are replaceable; journals remain authoritative.
Statistics shows confirmed history totals separately from recommendation
estimates and does not claim that Bin moves free physical storage.

File Operations also provides history on local or network storage, separate from
local dry-run proposals. Explicit registration writes archive/Bin identity metadata; each
installation verifies and pins its own local archive mapping. Versioned manifests
and append-only JSON-lines events live under the Bin's `.deduplicator-history`
directory, using archive-relative and Bin-relative paths rather than mount paths.
Readers display malformed, unsupported and interrupted records, and aggregate
confirmed events without claiming physical storage reclamation. Reads are off
the main thread and publish stable snapshots on refresh. Restoration and
permanent deletion controls remain unavailable.
Provider-supplied file evidence is rendered generically without category-specific
UI branches. Extend the input datasets only when a future category requires it.

Lazy group summaries show survivors and proposed counts/bytes. Expanding reveals
distinct retained/candidate cards with file/folder links, sizes, saved capture and
identifying EXIF, score factors, cached AI labels and the actual engine explanations,
confidence evidence and replacement dependencies. Details are lazy list entries,
with the shared desktop scrollbar. Review rows do not perform ad hoc file
mutations; approved moves go through the File Operations workflow. Permanent
deletion and restoration remain unavailable.

#### Cached and queued EXIF inspection

After a genuine file scan completes, including a resumed scan, calculate regular
retention scores for duplicate candidates under that scan's root. Only then build
an EXIF queue. Startup restoration of an already-completed scan, failed scans, and
explicitly paused scans do not automatically start EXIF. A new scan cancels and
drains an existing EXIF queue before beginning filesystem scanning.

A group is eligible only when its two highest current scores differ by at most
10 points and at least one member has a valid complete directory date. Year-only
and year/month folders alone cannot benefit from the current EXIF scoring model.
A low-scoring outlier does not prevent an otherwise ambiguous group from qualifying.
Freeze the selected groups before processing; visit every file in every selected
group, rather than stopping when one file's metadata could resolve the ambiguity.

The app-owned queue processes files sequentially using KIM, without modifying
source files. Reuse all current extraction results, including errors, unsupported
formats, missing EXIF, and read-limit failures. A file is opened only when its
stored inventory input or library identity/version differs. `lastScanned` alone
does not invalidate the cache. The library version is generated from the Gradle
version catalog, so upgrading KIM invalidates prior attempts automatically.

The duplicates screen also allows explicitly starting this pipeline for displayed
candidates while scanning is inactive, including after a paused scan. Progress
counts visited files, including cache hits. Navigating away, sorting, or changing
filters does not cancel the app-owned queue or start another one. Merely opening
or searching the browser never extracts EXIF.

Reassess scores only after the entire EXIF queue finishes, then refresh visible
scores and within-group ordering from persisted data. Recompute controls are
disabled while the pipeline is active; browsing during extraction reads stored
scores without calculating new ones. Cancellation preserves completed extraction
attempts but does not reassess a partial queue. A subsequent pipeline or explicit
score recomputation can consume those stored results.

Persist only the whitelisted original capture date/time, corresponding timezone
offset and subseconds, camera make/model, orientation, and available pixel
dimensions. Keep capture components raw: a missing offset is unknown, not UTC or
the current computer's timezone. GPS, full metadata dumps, previews, and image
content are not stored.

Schema v5 adds a separate sparse `fileExif` table through additive migration
`4.sqm`, preserving the existing inventory, scores, and settings. Store the latest
attempt's outcome, extraction time, reader identity/version, and inventory input
snapshot. Previously extracted results can be displayed and copied without
reopening source files. They describe the last extraction, not a live-file
guarantee; changed inventory inputs invalidate them.

Read streams on the serialized filesystem lane, using MediaStore content URIs
on Android. Parsing has a 16 MiB read budget rather than loading complete files.
Unsupported formats, missing EXIF, unreadable metadata, changed sources, and
budget-exceeded results are explicit outcomes, not successful partial extractions.
Compare source metadata before and after each read, and validate inventory inputs
transactionally before saving on the database lane. Cancellation preserves
already completed attempts without recording an interrupted file as a failure.

Shutdown stops accepting queue requests, cancels and drains its work, and then
closes the filesystem/database lanes. No EXIF process runs concurrently with the
regular filesystem scan.

#### Suspected duplicates across different filenames

The **Suspected duplicates** tab sits immediately after **Duplicates** and is an
explicit, manual analysis across the stored inventory, respecting exclusions.
Opening the tab or refreshing stored matches never starts filesystem reads.

Select images strictly larger than 100,000 bytes using the existing image-extension
classification. SQL groups them by exact byte size and requires at least two
different case-sensitive filenames per bucket. Skip same-name-only buckets; for a
qualifying bucket, visit every member, including its regular duplicate copies.

Use the existing app-owned sequential EXIF queue and all-status cache. This manual
mode does not require close retention scores or dated directories. It shares scan,
overlap, cancellation, and shutdown gates with ordinary EXIF analysis. After all
selected files finish, update their retention scores; never rescore a partial
cancelled queue. Changing tabs does not cancel the analysis.

Read matches from successful current `fileExif` rows whose input fingerprint and
KIM reader identity/version match. Require a valid original capture timestamp to
the second and equal byte sizes, with at least two different filenames in each
displayed result. Same-day-only, missing, invalid, stale, or failed EXIF is not a
match. Matching original offsets are required: unknown offsets form a separate
group, malformed offsets are rejected, and no timezone is inferred. Subseconds,
camera details, and dimensions are not equality requirements in this first pass.

Group results rather than generating every possible file pair. Show capture
timestamps, exact sizes, filenames, and full directory paths. These remain
suspected duplicates, not content-verified matches, and no file is modified.
Load/reuse retention scores from stored metadata, show expandable factors, and
order members by descending score with path tie-breaking. Recompute scores is
available without any EXIF rereads. While scanning or processing EXIF, and on
terminal queue refreshes, read stored scores only. Cancellation/failure must not
cause an automatic partial-queue reassessment; explicit recomputation can consume
the persisted data later. A changed capture timestamp, offset, status, reader, or
input fingerprint invalidates the old group's recommendation even when its
calendar-date retention contribution would remain unchanged.

Horizontal action groups and filter chips wrap within the available width on
phone layouts. Duplicate sort cards wrap in numbered priority order, including
their direction/priority actions; the Filters panel scrolls vertically to keep
all controls reachable. Result headings and actions must not squeeze filenames
or paths into unusable columns. Apply the same wrapping/stacking approach to
settings, operations, logs, scan actions and browsing metadata; ordered
navigation such as breadcrumbs may scroll horizontally instead.

A collapsible Filters panel applies folder, filename wildcard, and recommendation
action filters to loaded results immediately. Remember these independently from
the regular duplicate filters. Folder matching is case-sensitive, literal, and
component-aware; filename wildcards share the regular screen's case-insensitive
`*`/`?` semantics. Folder and filename must match the same member, but retain the
entire group, including copies outside the folder or with different filenames.
Filter changes neither query inventory nor extract EXIF or recalculate scores.
Analysis and Recompute scores continue to cover all stored roots.

Copy results exports the current filtered and sorted snapshot, including off-screen
members in displayed order, full paths, exact sizes, filesystem dates, cached EXIF,
retention factors, and group/per-file recommendation explanations and reason codes.
Include filter/sort context and capture timestamps with their original offsets;
state explicitly that these are unverified, advisory matches and no files changed.
Quote paths and other text to escape control characters safely. Invalid folder
filters show an error instead of exposing or copying stale results.

Schema v6 adds a size-leading index through additive migration `5.sqm`, preserving
the existing inventory, EXIF cache, scores, and settings.

### Future investigation: content verification

Verify candidate contents before describing them as exact duplicates, but do
not assume verification must be added to the existing metadata-based workflow.
Content hashing, including SHA-256, remains a possible aspiration, not a
committed or near-term feature.

Reading candidate contents over a NAS may cost more I/O and time than the
additional certainty is worth for this use case. If a compelling need arises,
benchmark candidate bytes, actual read throughput, and cache reuse before
deciding whether to implement hashing. It is acceptable to defer it or conclude
that the extra reads are not worthwhile.

If investigation is justified, possible approaches include:
- Comparing initial and final content samples to reject candidates early.
- Full SHA-256 hashing of a limited candidate set.
- Caching verification results with an explicit input-invalidation policy.
- Parallel processing where benchmarks justify it.
- Running a lightweight scanner locally on the NAS.

Sampling is a filter only, never proof of equality.

Any future verified-content state must remain distinct from metadata
confidence. Do not silently relabel old recommendations as verified, and do
not change the existing approved-move eligibility policy as part of a
documentation update.

### Future investigation: broader AI assistance

The implemented AI scope is directory classification, described above.
Additional ideas, not current capabilities or committed requirements, include:
- Discover recurring folder conventions from aggregated inventory statistics.
- Propose candidate canonical-copy rules.
- Audit proposed rules and batches for suspicious outcomes.

If broader assistance is added, keep it behind a replaceable interface, use
schema-validated outputs, and record appropriate provenance. Evaluate against
deterministic baselines in shadow mode before allowing a new kind of model
output to affect recommendations. The existing directory-label integration
already influences location policy; this is not an instruction to disable it.
A model response alone must never authorize a file operation.

### Future investigation: operational extensions

Add operational features based on actual use rather than the original
speculative phase list. CSV/JSON exports and a NAS-side scanner remain
possible extensions; existing copyable reports are not those export formats.
Share business logic between Android and desktop rather than duplicating it
for an additional interface or deployment.

### Future work: recovery and permanent deletion

Approved Bin moves are implemented; restoration, automatic reconciliation, and
permanent deletion are not. Preserve the existing validation, manifests,
journals, bounded batches, and uncertain-outcome handling described above.
Design and test a user-reviewed restore/reconciliation workflow before adding
permanent deletion or broader automation. Permanent deletion would be a
separate, explicitly approved operation, not an implicit continuation of a Bin
move. These aspirations do not establish transactional filesystem guarantees.

## 6. Performance strategy

Measure the actual workload before optimizing. File enumeration, SMB latency,
candidate frequency, database queries, and disk throughput may matter more
than any one algorithm. Ordinary analysis should use stored metadata; do not
add archive reads merely to refresh a presentation.

Collect measurements for:
- Total files and total bytes.
- Files enumerated per second.
- Candidate group count and candidate bytes.
- Bytes read during EXIF extraction or any future verification.
- Hash throughput if content verification is investigated.
- Total elapsed time.
- Cache hit rate on subsequent scans.

Use these results to decide whether partial reads, hash caching, parallelism, or NAS-side scanning are justified.

Keep performance tests separate from deterministic correctness tests where appropriate.

## 7. Documentation goals

Keep this plan useful as engineering context. The README is the concise
portfolio presentation; the roadmap describes status and priorities.

Document:
- Architecture and key design decisions.
- The TDD workflow and meaningful regression tests.
- Benchmark methodology and measured results when available; do not invent metrics.
- Correctness guarantees and known limitations.
- Tests for failure handling and recovery.
- AI evaluation against a deterministic baseline.
- Actual results from the archive, without publishing private paths or personal photos.

Prioritize a reliable, measurable solution over speculative architecture.

## 8. Validation expectations

When changing the relevant areas, preserve these expectations:

- Android Studio builds the KMP project.
- Android and desktop JVM targets compile.
- Shared code has a working test suite.
- The test workflow is documented.
- The desktop scanner can inventory a representative portion of the NAS.
- The inventory can be persisted and resumed.
- Candidate groups can be identified by filename and size.
- Metadata-only candidates remain clearly distinguished from any future content-verified matches.
- Reports can be copied without modifying source files or unexpectedly starting analysis.
- File-operation changes retain proposal validation, explicit approval, journaling, and tested failure handling.

Content verification is not a requirement for declaring the existing app
complete. Future scope and priorities are tracked separately in
[roadmap.md](roadmap.md).
