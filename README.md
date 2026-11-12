# Photo Archive Deduplicator

Over 20 years, more than 200,000 photos accumulated across backup services,
cloud storage, computers, and a local NAS. Finding duplicates is only part of
the problem: deciding which copies to keep means accounting for folder
organization, metadata, intentional collections, and the risk of losing files.

Photo Archive Deduplicator is a Kotlin Multiplatform app for investigating that
archive. It builds a persistent inventory, identifies duplicate candidates,
explains retention recommendations, and supports reviewed moves to a separate
Bin. It runs on Android and desktop JVM, with shared Compose UI and application
logic.

**Candidates are not verified identical files.** The app currently does not
hash or compare file contents. Recommendations are evidence for review, not
proof that files match or permission to delete.

## Cleanup Results

![Cleanup Results showing the archive overview, AI recommendation impact, and duplicate-file statistics](docs/images/Photo%20Archive%20Deduplicator%20-%20Cleanup%20Results.png)

*Example from a populated inventory. Bin figures report recorded logical moves;
duplicate candidates are not content-verified.*

## How it works

1. Scan a selected folder and save file metadata in a local SQLite inventory.
   Scans can be paused and continued later.
2. Search the stored inventory for exact filename-and-size groups or suspected
   cross-filename matches using cached EXIF capture metadata.
3. Review explainable recommendations based on folder structure, filename
   cues, stored metadata, and optional directory classifications.
4. Prepare a dry run. After reviewing its candidates, survivors, destinations,
   and validation findings, explicitly approve any moves to the configured Bin.

## Engineering decisions

- **Use an inventory for fast analysis.** Repeatedly querying a large archive,
  especially over a network mount, can make every search or view wait on
  filesystem latency. The app stores file metadata and analysis results in
  SQLite so browsing, filtering, duplicate searches, statistics, and
  recommendations query the local database rather than walking the archive
  again. Filesystem I/O is reserved for scans and tasks that need file access,
  such as extracting uncached EXIF.
- **Separate candidate detection from retention decisions.** Matching names,
  sizes, or capture metadata can identify candidates, but does not establish
  file identity. A separate deterministic recommendation engine evaluates
  evidence for each file and group. Missing or conflicting evidence can leave
  a group for review or recommend keeping multiple copies.
- **Keep AI advisory and scoped.** On explicit request, a configured Ollama
  server can classify directory paths to inform location recommendations.
  Deterministic logic remains responsible for candidate evidence and safety
  checks; an AI response cannot prove files identical or authorize a move.
- **Treat file operations as a separate, reviewed phase.** Dry-run evidence is
  saved and validated before explicit approval. Moves use same-filesystem
  atomic rename and a persistent intent/completion journal, with bounded
  batches, cancellation, and progress.
- **Engineering ownership and AI-assisted development.** I defined the behavioral 
  requirements, made the architectural and technical decisions, and developed the 
  application iteratively, using tests and runtime behavior to validate the design 
  and catch regressions. GitHub Copilot generated much of the implementation and test 
  code; I directed the development, reviewed its output, and remained responsible 
  for the resulting design and behavior.

## Safety and data handling

Scanning and recommendation analysis do not modify source files. Moving files
to the Bin does. Keep independent backups and review every proposal before
approval. Permanent deletion and a user-facing restore workflow are not
implemented.

Moves require an atomic rename on the same filesystem. The app validates paths,
metadata, archive/Bin identity, and reviewed evidence, and journals operation
intents and outcomes. It does not automatically recover interrupted or
uncertain moves; manual reconciliation may be needed. Atomic rename is not a
race-proof no-overwrite guarantee against unrelated external writers. Do not
modify the source or Bin paths concurrently with an operation.

Inventory data and diagnostics are stored locally; operation history is stored
under the configured Bin. Paths and filenames may appear in those records and
in exported reports, so review them before sharing. Explicit Ollama
classification sends directory paths and names to the configured server, not
photo contents, filenames, or EXIF.

## Platforms and architecture

| Platform | App | Notes |
|---|---|---|
| Android | `:app` | Shared UI and logic; scans media available through Android storage permissions |
| Desktop JVM | `:desktopApp` | Intended for large local or mounted archives |

```text
app/          Android host and platform setup
desktopApp/   Desktop host, window, and folder chooser
shared/       Compose UI, domain logic, scanning, persistence
  commonMain/ Shared implementation and SQLDelight schema/migrations
  androidMain/Android filesystem and database integrations
  jvmMain/    Desktop filesystem and database integrations
```

The shared module contains the scanner, repositories, duplicate and retention
logic, EXIF pipeline, Ollama integration, and file-operation workflow.
SQLDelight manages the SQLite schema and migrations. Desktop package formats
are configured for DMG, MSI, and DEB.

## Build and test

Use the included Gradle wrapper. Android builds require Android SDK 37; the
Gradle build uses its configured JDK toolchain.

```sh
# Run the desktop app
./gradlew :desktopApp:run

# Build the Android debug APK
./gradlew :app:assembleDebug

# Run shared, desktop, and Android host/unit tests
./test.sh
```

The Android debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## License

© 2026 Doron Yakovlev Golani. All rights reserved.

The source is public so it can be read and reviewed, for example as a work
sample. No license is granted: copying, modifying, distributing or using this
code requires the author's written permission.
