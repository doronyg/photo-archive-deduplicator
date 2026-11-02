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
- **No status string literals.** `status` maps to the `DirectoryStatus` enum through
  `EnumColumnAdapter`; always bind it as a parameter (`:status`) instead of writing `'PENDING'`.
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

Already-installed version 2 databases are left untouched: this correction adds no version
bump and does not rerun migration 1 on them. Their earlier column order is supported by
SQLDelight's explicit named-column queries. An unreleased intermediate version 1 containing
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

No checksum-specific schema, hashing, or AI/Ollama analysis is included. Future analysis
belongs in separate tables, not new checksum fields on this inventory.
