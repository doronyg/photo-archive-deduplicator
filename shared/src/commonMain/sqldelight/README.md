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
| `db/FilesDatabase.kt` | All `.sq` files + Gradle config | Database interface, `Schema`, factory |
| `db/shared/FilesDatabaseImpl.kt` | All `.sq` files + Gradle config | Implementation, `CREATE TABLE`/`INDEX` statements |

Naming rules: a file `Foo.sq` produces `FooQueries`; a table `foo` produces data class `Foo`;
a query label `bar:` produces function `bar(...)`; columns and `:params` become properties and
arguments with the same name. That is why tables and columns use camelCase.

Every generated file starts with a `GENERATED FILE - DO NOT EDIT` header listing its source.
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

## Schema changes after release

No database file exists in the field yet, so the schema can still change freely. Once users
have a database, every schema change must come with an `.sqm` migration file
(`1.sqm`, `2.sqm`, ...) in this folder, and `verifyMigrations` should be enabled.
