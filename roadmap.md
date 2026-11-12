# Project status and roadmap

This roadmap summarizes implemented capabilities, current limitations, and
development priorities. It is not a release schedule. Detailed behavioral
contracts and engineering rationale belong in [plan.md](plan.md).

## Delivered

- Shared Kotlin Multiplatform app for Android and desktop JVM, with shared
  Compose UI and platform-specific filesystem/database integrations.
- Persistent SQLite inventory with pausable scans, progress, cancellation,
  browsing, exclusions, diagnostics, and saved preferences.
- Duplicate candidate groups based on exact filename and size, plus suspected
  cross-filename matches using equal size and cached EXIF capture metadata.
- Explainable per-file retention scoring, deterministic group decisions,
  confidence/evidence, and conservative handling of missing or conflicting data.
- Optional, user-triggered Ollama directory classification and statistics
  comparing AI-informed with deterministic recommendations.
- Categorized review recommendations, inventory/recommendation statistics,
  filters, and copyable reports.
- Saved dry-run proposals, archive/Bin registration, operation history,
  validation, explicitly approved Bin moves, bounded journal batches,
  cancellation, and live progress.

## Current boundaries

- Candidate identity is not verified: files are not hashed or byte-compared.
  Metadata matches and recommendations are not proof of duplicates or guaranteed
  savings.
- Ollama provides optional directory labels. It does not inspect photos or
  authorize file operations.
- Approved Bin moves change the filesystem. Interrupted or uncertain moves
  may require manual reconciliation; automatic file-operation recovery and a
  user-facing restore workflow are not implemented.
- Moves require same-filesystem atomic rename, with no copy/delete fallback.
  Atomic rename is not a race-proof no-clobber guarantee against unrelated
  external writers. Source/Bin paths and mounts must not be changed concurrently.
- Permanent deletion and unattended cleanup are not implemented.

## Next priorities

1. **Recovery workflows.** Design and test user-reviewed restoration and
   interrupted-operation reconciliation. These should precede any consideration
   of permanent deletion or broader automation, neither of which is a commitment.
2. **Documentation and usability guidance.** Extend the existing screenshots
   where they help explain a workflow, and add concise platform-specific
   setup/use guidance. Keep documentation aligned with actual behavior and
   operational limitations.

## Possible future investigation

Content hashing, including SHA-256, could provide stronger evidence for some
candidate groups, but it would add file reads that may be costly on network
archives. It is an aspiration to evaluate only if there is a compelling use
case, not a committed or near-term feature.

Other ideas retained in the plan include CSV/JSON exports, NAS-side scanning,
and broader AI assistance with folder conventions or rule evaluation. These
are possibilities to assess against actual needs, not planned deliverables.

Priorities may change as real use and further validation uncover new requirements.
