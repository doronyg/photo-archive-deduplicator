# Database schema and generated code (SQLDelight)

This folder holds the **source of truth** for the local database: SQL schema and named
queries in `.sq` files. The [SQLDelight](https://sqldelight.github.io/sqldelight/) Gradle
plugin turns them into type-safe Kotlin.

> **Do not edit generated files.** This applies to people and AI assistants alike.
> Anything under `shared/build/generated/sqldelight/` is rewritten on every build.
> To change the database API, edit a `.sq` file here (or the Gradle config) and rebuild.

## Where things are

| What | Location |
|---|---|
| Schema + queries (edit these) | `shared/src/commonMain/sqldelight/com/doronyg/photoarchivededuplicator/db/*.sq` |
| Plugin config (edit this) | `sqldelight { databases { create("FilesDatabase") { ... } } }` in `shared/build.gradle.kts` |
| Generated Kotlin (never edit) | `shared/build/generated/sqldelight/code/FilesDatabase/commonMain/...` |
| Hand-written code using it | `shared/src/commonMain/kotlin/com/doronyg/photoarchivededuplicator/scan/DirectoryRepository.kt` |

Android Studio shows generated files like normal sources. Hover over the file tab, or check
the header comment, to see that a file lives under `build/`.

## What is generated from what

| Generated file | Source | Contents |
|---|---|---|
| `db/ScanDirectory.kt` | `ScanDirectory.sq` (`CREATE TABLE scanDirectory`) | Row data class + column adapter for `status` |
| `db/ScanDirectoryQueries.kt` | `ScanDirectory.sq` (named queries) | One function per query, e.g. `requeue`, `selectSubtree` |
| `db/ScanFile.kt` | `ScanFile.sq` (`CREATE TABLE scanFile`) | Row data class |
| `db/ScanFileQueries.kt` | `ScanFile.sq` (named queries) | One function per query, e.g. `updateFile` |
| `db/DirectoryExclusion.kt`, `db/DirectoryExclusionQueries.kt` | `DirectoryExclusion.sq` | User-entered excluded roots and optional reasons, independent of inventory |
| `db/ScanSession.kt`, `db/ScanSessionQueries.kt` | `ScanSession.sq` | Active elapsed time and last-selected logical scan root |
| `db/UiPreference.kt`, `db/UiPreferenceQueries.kt` | `UiPreference.sq` | Local UI choices stored as key/value entries |
| `db/FileScore.kt`, `db/FileScoreQueries.kt` | `FileScore.sq` | Independent assessments and input metadata; reads join their shared scoring profile |
| `db/ScoreProfile.kt`, `db/ScoreProfileQueries.kt` | `ScoreProfile.sq` | Exact score type, algorithm/version and configuration shared across assessments |
| `db/FileScoreFactor.kt`, `db/FileScoreFactorQueries.kt` | `FileScoreFactor.sq` | Ordered numeric contributions referencing shared factor definitions |
| `db/FactorDefinition.kt`, `db/FactorDefinitionQueries.kt` | `FactorDefinition.sq` | Exact factor keys and explanation text shared across contributions |
| `db/FileExif.kt`, `db/FileExifQueries.kt` | `FileExif.sq` | Sparse latest EXIF results, reader identity, and input metadata |
| `db/FilesDatabase.kt` | All `.sq` files + Gradle config | Database interface, `Schema`, factory |
| `db/shared/FilesDatabaseImpl.kt` | All `.sq` files + Gradle config | Implementation, `CREATE TABLE`/`INDEX` statements |

Naming rules: a file `Foo.sq` produces `FooQueries`; a table `foo` produces data class `Foo`;
a query label `bar:` produces function `bar(...)`; columns and `:params` become properties and
arguments with the same name. That is why tables and columns use camelCase.

Every generated file starts with a `GENERATED FILE - DO NOT EDIT` header listing its source
(including `.sqm` migrations for database-level classes).
SQLDelight does not write that header; a `doLast` step on the SQLDelight tasks at the bottom
of `shared/build.gradle.kts` adds it.

## Things that look wrong but are fine

- **Large numbers such as `driver.execute(-2_056_871_185, ...)`** are statement identifiers:
  a hash of package + `.sq` file name + query name. The driver uses them to cache prepared
  statements and to route change notifications. They change when a query is renamed.
  There is nothing to name or centralize.
- **`suspend` functions and `.await()`** come from `generateAsync = true`. Database calls
  are always suspending; `DirectoryRepository` moves them onto `Dispatchers.IO`.

## Rules for writing `.sq` files

- **camelCase** for tables, columns, indexes and parameters, so the generated Kotlin is idiomatic.
- **No status string literals.** Directory `status` maps to the `DirectoryStatus` enum through
  `EnumColumnAdapter`; EXIF status stores the enum name without an adapter. Always bind
  status as a parameter (`:status`) instead of writing `'PENDING'` or `'EXTRACTED'`.
- **Named parameters** (`:path`), not `?`, so generated argument names are meaningful.
- SQLDelight type-checks every query against the schema, so mistakes fail the build.

## Regenerating

Generation runs automatically before compilation and on Gradle sync. To run it by hand:

```sh
./gradlew :shared:generateCommonMainFilesDatabaseInterface
```

After changing a `.sq` file, run the TDD loop (`./gradlew :shared:allTests`).

## Exclusions and candidate searches

The shared **Exclusions** tab accepts absolute directory paths before they exist or have
been scanned, with an optional reason. Browse can also exclude the currently open folder.
Removing an exclusion makes its tree eligible for subsequent scans and searches again.
Exclusion edits are disabled during scanning and rejected by the repository while a scan
session is active. No sensitive-folder rules are built in; the existing Synology scan
defaults are unchanged.

Excluded trees are skipped before filesystem child metadata is read and filtered from
Android MediaStore results. Existing excluded inventory is retained during reconciliation,
including when an unprotected ancestor disappears. Browse still shows retained records.
Matching uses normalized paths with directory-component boundaries, never SQL `LIKE`
patterns (so `%` and `_` in a path are literal).

The shared **Duplicates** tab searches stored inventory under an entered/selected root.
Groups contain at least two files with the exact case-sensitive name and byte size;
excluded trees do not participate. These are candidates, not verified duplicates.
Creation and modification times are displayed in UTC as optional evidence, not filters.
File creation time is nullable: desktop uses Okio `createdAtMillis`; Android MediaStore
does not supply a birth time, and date-added is not substituted.
There is no hashing, AI decision-making, deletion, or writing to scanned files.

## Schema changes after release

Real inventory databases must be preserved. The released version 1 has `scanDirectory`
and `scanFile`, without exclusions, file creation times, the name/size index, or sessions.
Version 2 adds `directoryExclusion`, nullable `scanFile.createdAt`, `scanFileNameSizeIndex`,
and `scanSession` through `com/doronyg/photoarchivededuplicator/db/1.sqm`. It does not rebuild
or clear inventory, modification times, retry counts, or pending directory states. Existing
files get a null creation time until their next listing. Desktop's JDBC schema constructor
upgrades `PRAGMA user_version`; Android's SQLDelight SQLiteOpenHelper invokes the same
generated migration. Do not delete the database to upgrade it.

Migration 1 is not rerun on already-installed version 2 databases. Their earlier column
order is supported by SQLDelight's explicit named-column queries. Version 3 adds only the
`uiPreference` table through `com/doronyg/photoarchivededuplicator/db/2.sqm`, preserving
inventory, exclusions, and scan sessions. Version 4 adds separate score and score-factor
tables through `com/doronyg/photoarchivededuplicator/db/3.sqm`; it does not rewrite
inventory or reset scan queues, sessions, exclusions, or UI preferences.
Version 5 adds only the sparse `fileExif` table through
`com/doronyg/photoarchivededuplicator/db/4.sqm`. Existing inventory, scores and factors,
queues, sessions, exclusions, and preferences are unchanged.
Version 6 adds `scanFileSizeIndex` through
`com/doronyg/photoarchivededuplicator/db/5.sqm` for size-first suspected-image searches.
It only creates an index; existing inventory, EXIF, scores, queues, and settings remain intact.
An unreleased intermediate version 1 containing
some of the new columns/tables is not the historical migration target; do not infer its
schema by dropping the session table from today's schema.

Every later schema change needs the next numbered `.sqm` migration. Migration verification
is enabled in Gradle. `databases/1.db` is an empty snapshot of the actual installed v1 schema,
not user data. It was generated with `sqlite3` from the `CREATE TABLE`/`CREATE INDEX`
statements in `ScanDirectory.sq` and `ScanFile.sq` at commit
`03130373ae5ce8ab9cb8cce4a899082d6850173f`, stripping SQLDelight's `AS DirectoryStatus`
adapter annotation and setting `PRAGMA user_version = 1`. SQLDelight verifies that
migrating it matches a freshly created current schema:

```sh
./gradlew :shared:verifyCommonMainFilesDatabaseMigration --rerun-tasks
```

The shared migration tests reopen a populated historical v1 in-memory database using the
actual JDBC automatic-upgrade constructor, check that inventory and pending/retry state
survive, and exercise the new exclusions, creation-time, duplicate, and session queries.
They also reopen the previously installed v2 layout and verify its inventory, exclusions,
creation times, and session are unchanged. Android host tests run these tests through JDBC;
they do not emulate Android SQLiteOpenHelper.

Current-to-next upgrades are the supported migration-development focus until the
schema stabilizes. Earlier migration files remain intact, and databases are never
deleted or recreated as an upgrade strategy. The v3-to-v4 regression uses populated
records and verifies that existing inventory and preferences survive the upgrade.
The v4-to-current regression automatically upgrades a populated on-disk JDBC database,
compares every pre-existing table, then stores EXIF and closes/reopens it again.
The populated v5-to-v6 regression also preserves existing EXIF rows and verifies
the size-leading index before and after reopening.

### Lossless score normalization (version 8)

Migration `7.sqm` replaces repeated score identities/configurations with integer
references to `scoreProfile`, and repeated factor keys/explanations with references
to `factorDefinition`. It preserves score IDs, every historical identity and input
fingerprint, numeric values, assessment times, factor ordering and exact explanation
strings. Contributions stay on individual factor rows, not their shared definitions.
Nothing is rescored, pruned, translated or regenerated. Inventory, EXIF, AI
classifications, exclusions, sessions and preferences are unchanged.

The repository reconstructs the existing domain models through joined batch queries.
New dictionary IDs are cached only within the save transaction, so cancellation or
failure rolls back dictionary entries together with scores and contributions.

Normalization frees SQLite pages for reuse but does not automatically shrink the
database file. Compaction is a separate maintenance operation: after a verified
backup and clean application shutdown, `sqlite3 /path/to/inventory.db 'VACUUM;'`
can reclaim free space. Do not run it during a scan or inside a schema migration.
The upgrade needs temporary disk space for the replacement score/factor tables;
compaction also needs space for a rebuilt database.

### DeduplicatorGarbageBin and saved dry runs (version 9)

Migration `8.sqm` adds registered Bin roots and saved file-operation proposals.
It does not change existing inventory, score, EXIF or classification records.
Registered roots are mandatory exclusions even inside scanned directories.
Changing the active root retains previous roots as exclusions so older proposals
and future managed files cannot re-enter archive results. Ordinary user exclusions
remain separate; their previous browsing behavior is unchanged.

The File Operations tab configures the full Bin path and creates saved proposals
from selected deletion recommendations. Each proposal captures the Bin root,
complete group evidence and exact proposed destinations. No source archive root
needs to be configured. Destinations preserve the full original path beneath the
Bin, for example `/Photos/Trip/a.jpg` becomes `<Bin>/Photos/Trip/a.jpg`; Windows
drive letters and network share paths are preserved without the drive colon.
No operation or file IDs are inserted into directory names. Existing destinations
are reported as conflicts.

Dry runs perform metadata reads only. They neither create the Bin nor read file
contents, extract EXIF, rescore files, call Ollama, move files or delete anything.
Passed checks describe observations at the recorded time, not approval to move.
Snapshots use a versioned JSON payload; history summaries load independently of
the larger evidence payload. Stored proposals remain unchanged when configuration,
cached evidence or recommendation algorithms change.

Migration `9.sqm` (version 10) makes the legacy proposal archive-root field
optional without changing any existing proposal, mapping or evidence. New proposals
do not use that field. Missing Bin directories and missing parents do not block
dry runs; existing files, symlinks and collisions still produce findings. Creating
directories belongs to a future explicitly approved real run. The UI suggests an
editable Bin location, shows stay/move outcomes, collapses evidence by default and
can copy the complete result without exporting raw evidence JSON.

AI review, approval, native move-capability checks, actual moves and restoration
are not implemented in this increment. A future executor must revalidate after
AI review and obtain explicit approval. Metadata alone does not establish identical
contents, and no full-file hashing is introduced.

## Suspected duplicates

The adjacent **Suspected duplicates** tab uses SQL size buckets rather than exact
filename-and-size groups. Reuse the image extension classification and select only
files strictly larger than 100,000 bytes, respecting root boundaries and exclusions.
Buckets must contain multiple case-sensitive filenames before any EXIF is attempted.
All members of a qualifying bucket are visited, including same-name copies.

Analysis starts only on an explicit request and shares the existing app-owned queue,
cache, scan gates, and final-only score reassessment. It bypasses retention ambiguity
and dated-directory eligibility; those rules still apply to regular EXIF analysis.
Opening or refreshing the tab queries stored results only.

Matches require successful, current EXIF from the current KIM reader, a valid original
capture date/time to the second, and identical sizes. Match original offsets exactly;
unknown offsets form a separate group and malformed offsets are rejected. Do not infer
timezones or use filesystem creation times. Subseconds, camera, and dimensions are not
equality requirements. Only resulting groups containing different filenames are shown.
Missing or failed EXIF never establishes a match, and these groups are not proof of
identical contents. Retention and prior candidate-search behavior remain unchanged.

## Independent EXIF results

EXIF extraction runs in an app-owned queue after a genuine scan completes and regular
duplicate scoring finishes, or on explicit request in the duplicates browser. Paused,
failed, and startup-restored scans do not trigger it. It never runs during filesystem
scanning or automatically when results are opened. Results are stored separately from inventory
and file scores. Unprocessed files have no EXIF row. Each path holds its latest result
plus its input snapshot, including status, nullable metadata, error, extraction time,
and reader ID/version; a new extraction replaces only that row. Status names are
persisted as strings, not enum ordinals.

Capture time preserves raw `DateTimeOriginal` with its matching original offset and
subseconds; no timezone is inferred. GPS is not extracted or stored. Retention scoring
uses only successful, current stored original capture dates; scoring itself never opens files.
The streaming parser has a 16 MiB input cap and returns
`LIMIT_EXCEEDED` when exceeded, never a partial success.

`fileExif` reads bounded batches and defaults to current inputs only. A matching inventory
row must have the same path, directory, byte size, and nullable modification/creation times
(`IS` comparisons preserve null equality). `lastScanned` is not an extraction input.
Historical rows remain accessible with `currentOnly = false`, even after inventory changes
or deletion. Writes validate these inputs inside the same transaction as the upsert and
return false for changed or deleted files. Persisting EXIF does not directly rewrite
inventory, scores, or factors. The queue reassesses retention only after all selected
groups finish, not after each member or group. Cancellation keeps completed EXIF rows
without rescoring the partial queue. The additive migration does not reset or recreate
the database.

Reuse unchanged inventory inputs with matching reader identity/version, including failed
attempts. Do not retry merely because a new scan updates `lastScanned`, a user clicks again,
or a read-limit failure is cached. `KIM_VERSION` is generated under `shared/build/generated`
from `libs.versions.kim`; it is not a second hand-maintained library version.

Queue selection uses the two highest current retention scores (gap at most 10) and requires
at least one complete, valid directory date. Process every member of each selected group,
reusing cached attempts. Year-only and year/month-only groups are skipped because EXIF
cannot change their current organization scores. Queue progress/state is app-owned,
cancellable, and independent of browser navigation. Starting a new scan drains it first.

## Independent file scores

Scores are identified by file path, type, algorithm/model ID, version, and
configuration. Each row stores its relevant input metadata, numeric value,
assessment time, and ordered factor explanations. Different identities coexist;
recomputing one identity replaces only its value and factor breakdown.

Retention scoring uses stored path/size/creation/modification metadata and usable stored
EXIF original capture dates, not content reads.
`lastScanned` is not a scoring input. Assessments are reused only when their inputs
and identity match, including zero-contribution `exif-date-evidence` factors that track
the usable original calendar date. Extraction time, camera metadata, offsets, and time
of day do not invalidate scores when this date is unchanged. Cached scores are loaded
before current EXIF so evidence comparisons use the later EXIF snapshot. Writes recheck
current file metadata within the same database transaction. Retention writes also supply
an optional expected EXIF snapshot, rejecting a concurrent EXIF replacement/removal
before saving rather than publishing an obsolete calculation. A rejected write leaves
existing score identities intact; a subsequent assessment can recompute it. Batches are
bounded to avoid an archive-wide write lock.

Default retention weights/rules live in `retention/RetentionScoring.kt` as
`RetentionConfig`. Changing weights or matching rules changes the canonical
configuration identity. Changes to the algorithm's interpretation must also bump
its explicit algorithm version; this is independent of the SQLDelight schema
version. New score types or algorithm versions do not need an inventory schema
change and do not replace older identities.

Retention algorithm v5 retains v4's year/month organization +45 and year-only +10,
plus filename/folder-date disagreement evidence and WhatsApp/PhotoSync location rules.
Matching stored EXIF raises alternative full-date folder organization from +40 to +90;
preferred organization remains +100. Reliable disagreement contributes -40 once across
WhatsApp filename and EXIF evidence, unless a recognized filename date supports the folder.
Standard camera/IMG/VID/PXL filename dates only protect a matching folder from EXIF
penalties; they do not introduce standalone filename mismatch penalties.
Missing, failed, limit-exceeded, invalid, or stale EXIF is neutral. Year/month backup
copies score +45 -25 = +20 even when extraction fails; temporary-only copies remain -60.
Dates use the original calendar date,
not timezone conversion. Find candidates or Recompute scores produces v5 assessments
from stored metadata when the queue is inactive. During queue processing the browser uses
`RetentionScoreService.stored`, a database-only read that hides obsolete evidence without
computing or saving scores. The extraction barrier also downgrades overlapping assessment
requests to stored reads, so a previously queued browser request cannot rescore partial
EXIF after cancellation. A fresh explicit assessment remains available afterward.
After the whole queue finishes, final reassessment refreshes
visible scores and member ranking. The ambiguity label also uses a 10-point margin.
v1/v2/v3/v4 assessments and other score types remain available. Queue scheduling and caching
need no database schema change or inventory reset.

Algorithm v5 adds configurable filename cues: a valid trailing
` (yyyy_MM_dd HH_mm_ss UTC)` contributes -20 and a trailing `-001` through `-999`
contributes -10. Count each cue once, allow both to combine, and do not treat backup
timestamps as capture dates. Interpret underlying filename dates after removing
recognized copy decorations so existing filename/folder-date protection survives.
Rules apply consistently to both screens and each per-file score is reusable
regardless of group composition. This needs no schema migration.

`assessSuspected` and `storedSuspected` reuse the same score persistence and extraction
barrier, but present rankings across each whole suspected group. Recheck the full
capture timestamp, original offset, successful status, reader identity/version,
and file input before recommending a leader; a same-day time change can invalidate
membership without changing the numeric retention score. Missing/outdated evidence
suppresses group recommendations. Opening/refreshing suspected matches computes
missing/current-profile scores from stored data only when inactive; queue terminal
refreshes and aborted-queue browsing use stored reads. Explicit Recompute scores
remains available after cancellation. No scoring operation reopens image files.

Deterministic group recommendations are computed in memory from these stored
snapshots; they introduce no tables, migration, or score version change. Both
duplicate screens reuse existing filename and directory evidence and expose
group actions, per-file roles, explanations, and reason codes, plus independent
group duplicate and per-file retention-decision confidence assessments. Confidence uses the
existing persisted timestamps, EXIF and retention factors; it adds no columns or
score writes. Duplicate confidence is the weakest reference comparison using
filename (30), modification timestamp (25), capture timestamp (30), camera
make/model (5 each), and dimensions (5). Size earns no points, missing evidence
earns nothing, and known EXIF contradictions block redundant recommendations.
No source files are reread or modified, and even 100/100 is not content verification.
Insufficient duplicate confidence or missing/stale retention assessments produces
review-only results. High-confidence groups preserve one representative per
unknown/special ordinary collection; uncertain chronological locations remain preserved.
The existing backup/import/sync directory classification is an explicit expendable
location policy for all descendants, including Google Photos and device/Camera
folders beneath PhotoSync. A strictly higher-scoring retained replacement without
known folder-date contradictions and sufficient direct duplicate evidence can
make such a copy redundant. Other required collections, explicit protection and
at least one surviving copy remain enforced. Backups without a qualifying better
replacement are preserved; individual retention scores/weights remain unchanged.
Explicit protected-path input forces preservation through `PROTECTED`; the input
defaults empty and no directory-protection classifier or configuration is added.
Every redundant candidate names a surviving replacement in its group and its
confidence is capped by a direct cached-metadata comparison with that replacement.
Whole-plan validation rejects losing every copy or a required collection,
invalid/cyclic dependencies, protected candidates, and inconsistent roles.
Rejected plans preserve all original members for review with zero per-file
decision confidence and no replacement dependencies. No group retention-confidence
number or hypothetical high removal confidence is presented.

Recommendation statistics read all stored roots using the same candidate queries
and exclusions. `duplicateCandidates(root = null)` groups exact filename/size
matches across roots; supplied roots retain their existing literal subtree
semantics. The Statistics tab computes separate regular/suspected aggregates from
stored scores and EXIF, without saving scores or extracting metadata. Counts by
group action include every group member; per-file role totals are separate views
of those same files. The cohorts may overlap and are not combined. This changes
only a query's optional scope, not the schema or existing inventory.

## Cached AI directory classifications

`DirectoryAiClassification.sq` stores successful directory classifications
independently of inventory, file scores and EXIF. Additive migration `6.sqm`
advances schema 6 to 7 without changing existing rows. The composite identity
includes normalized directory path, configured server/model and prompt version.
The legacy `preferredRootsKey` column remains in the key for compatibility but
is empty for new records. Prompt version 2 classifies paths without configured
preferred roots; version-1 rows remain stored but are not reused. Original path
and classification time are preserved. Exactly PREFERRED, BACKUP, PROTECTED and UNKNOWN are valid labels.
No model explanations or confidence values are stored.

Repository reads use the existing serialized database lane and bounded path
chunks. Each successful request is upserted immediately; HTTP runs outside the
database lane. A technical failure never replaces a row with UNKNOWN or erases a
valid cached result. Successful UNKNOWN is reusable until explicit refresh or
profile changes. Server/model/prompt profiles cannot be mixed.

Only a user-triggered job submits distinct directories from the currently
filtered complete duplicate groups. The app creates its Ktor client lazily, sends
paths/names only, and snapshots job scope. Cancellation stops
pending requests; accepted result commits and revisions finish before shutdown
closes the client/driver. No filesystem scanning, SMB or EXIF extraction is added.

The Ollama settings results viewer reads all persisted rows without contacting
the server or scanning the archive. It can filter to the current saved
server/model/prompt or show historical profiles, preserving separate results
for the same directory. It displays classification, path, time and provenance.
This adds a read query only, not a schema change or migration.

Both duplicate screens and Statistics consume cached exact-directory labels
without network calls. AI PROTECTED and UNKNOWN override backup expendability
for that directory; a parent BACKUP label is not inherited. Explicit protected
paths, per-file retained-replacement validation and last-copy/collection safety
remain authoritative. Individual retention scores and duplicate confidence do
not change. PREFERRED is a path-based AI hint, not restricted to an explicit root list.

## Remembered UI choices

The selected tab, scan folder, browse navigation stack, duplicate-search folder and
filters/sort choices, and log text/level/follow filters are restored before the container is returned.
Enum values are stored by name, not ordinal. Browse paths occupy separate indexed
entries so filenames containing newlines or other delimiters round-trip unchanged.
Regular and suspected duplicate sort priorities are stored independently under
`duplicateSort.*` and `suspectedDuplicateSort.*`, as ordered criterion names with
separate enabled and direction values per criterion. Recommendation type is
an optional criterion, appended disabled when absent from older saved priorities.
Existing preferences without these keys use
the default filename sort; unknown future criteria warn and are ignored while
known criteria retain their priority.

Suspected-result display filters are independent of the regular duplicate search:
`suspectedDuplicateRoot`, `suspectedDuplicateFilenamePattern`, and
`suspectedDuplicateRecommendation`. Missing keys default to all roots, any filename,
and all recommendation actions; the latter is stored as `ALL`, or an action's enum
name. Unknown actions warn and fall back to all recommendations. These settings use
the existing key-value table without a schema migration.
Filtering happens against loaded groups only, retaining every member of a matching
group; folder and filename must match the same member. Copy results exports that
displayed snapshot and its metadata/recommendations, without querying the database
or reading source files. Analysis/recomputation and Statistics remain independent
of these display filters.

An app-lifetime writer conflates pending edits and saves complete snapshots in a single
database-lane transaction. UI callbacks only update in-memory state and enqueue work.
Normal shutdown drains accepted edits before closing the driver; a force-kill can lose
queued or in-flight edits. Save failures are logged and surfaced in the UI; a later
changed edit attempts to save the complete current state again.
Restoring settings does not start scans, rerun duplicate searches, or open files.

## Resuming and elapsed time

Desktop **Resume** uses the entered root, or the last selected persisted scan when the input
is empty. It reads only pending/interrupted directories under that root, not queues belonging
to other roots. Starting a new scan intentionally reconciles that root again and resets its
logical elapsed total. Other roots' inventory and saved elapsed totals remain intact.
New scan selection and root queuing share one database transaction, so pausing during
initialization cannot leave a saved unfinished session without its scan work. A failure
queuing the root rolls back the selection and session reset too.
An older v1 inventory has no recorded elapsed time: its first resume starts at zero without
rescanning completed directories. If several old roots are pending, enter the desired root
before resuming instead of combining unrelated queues and timers.

**This run** is monotonic time since Start/Resume; **Total active** adds the persisted time
for that logical root across resumes and app launches. Paused time and app downtime are not
counted. Both displays tick once per second independently of directory events, including
while an SMB listing blocks or retry backoff is running. Time is checkpointed every five
seconds, not per file, and finalized on completion, pause, failure, and orderly desktop exit.
An abrupt process kill can lose up to the last five seconds of active time (or longer if
SQLite itself is unavailable). OS-blocked filesystem calls cannot be forcibly interrupted;
Pause freezes elapsed time immediately, but another scan and orderly exit wait for that
call to unwind before reusing/closing SQLite.

Android Resume re-reads the local MediaStore index for the selected root, as before; it does
not perform an SMB/filesystem walk. Only desktop directory queues are incrementally resumed.
Scan counters are per run; only active elapsed totals are persisted.

## Native file and folder links

Browse and Duplicates share muted, underlined filename and containing-folder links. Browse's
directory-row navigation still drills into stored inventory; **Open in file manager** is a
separate native action. On macOS, folders open in Finder and files use their default apps.
Desktop filesystem probes and native launches run on IO, with URI escaping rather than shell
commands. Missing files, permissions, and native application errors appear beside the link.

Android resolves the recorded path through MediaStore at click time and opens an image/video
`content://` URI with a read grant. It never exposes `file://` URIs and needs no new permission
or content-URI database column. Missing/inaccessible index entries and absent viewers surface
as link errors. This media-library view has no portable folder-opening contract: folder links
explicitly report that the action is unsupported and direct users to their file manager.

No checksum-specific schema or hashing is included. Optional Ollama directory
classification is separate from file identity and never compares contents. Future analysis
belongs in separate tables, not new checksum fields on this inventory.
