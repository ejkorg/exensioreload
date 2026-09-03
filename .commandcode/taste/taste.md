# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/


# git
- Commit .vscode/ directory with shared workspace config files (settings, launch, tasks, extensions) for team-wide IDE setup. Confidence: 0.75

# communication
- When the user asks for a command or instruction (e.g., "what is the command"), answer with the command/instructions directly before running system diagnostics or availability checks. Confidence: 0.55
- When the user asks whether a solution is the "final and most efficient" (or similar terse challenges like "is that the best approach?"), they are pushing back on the current solution and expect the assistant to critically re-examine its approach — switching to a cleaner alternative even if it means undoing the assistant's own recent changes. Don't just confirm; find the better way. Confidence: 0.55

# workflow
- The user's environment cannot run Java or Node builds (no `java`/`node`/`npm`/`mvn` on PATH, and the local `.m2` repo may be missing expected artifacts); verify fixes statically via grep/read, IDE diagnostics, and type-level reasoning rather than attempting builds or compilation. Confidence: 0.88
- Tracks long multi-step migration tasks via a markdown checklist file (e.g., ORACLE_TO_POSTGRES_INTERNAL_DB_CHECKLIST.md) and expects work to resume from the last completed item and drive the checklist to completion (e.g., "go ahead finish these check list"). Confidence: 0.78
- Verify factual claims about the codebase against the actual code (grep/read) before asserting them; user pushes back on unverified statements (e.g., "check again" when told a table isn't created by any Liquibase changelog). Confidence: 0.75
- When the user reports specific compile/type errors and asks for a "root cause fix for all," do a comprehensive sweep (grep across the full codebase) to find every instance of the same class of issue before applying fixes — fix the root cause (e.g., missing interface fields) rather than patching individual call sites one at a time. Confidence: 0.80
- When asked to make another backend app in the same workspace match a documented standard (e.g., an API documentation file written for a sibling app), first assess what's actually applicable before applying changes — align the genuinely-applicable parts and explicitly flag/hold back anything intentionally out of scope for that app rather than forcing full parity. Confidence: 0.65

# database
See [database/taste.md](database/taste.md)
# documentation
See [documentation/taste.md](documentation/taste.md)
# logging
- Disable or reduce Spring Boot DEBUG console logging to minimize noise. Confidence: 0.70
- Make logging configurable via YAML properties rather than hardcoded changes. Confidence: 0.75
- Log datasource identity at startup and query time to make database routing visible (e.g., QA vs PRD refdb). Confidence: 0.70

# status-naming
- Use lowercase status names (e.g., pending, staging, completed, failed) following xfcs-reloader convention rather than uppercase abbreviations like NEW, ENRICHMENT, DONE. Confidence: 0.70
- When status/enum values are renamed during a refactor, audit all layers for consistency — Java SQL string literals, Liquibase CHECK constraints, and any other hardcoded status references — not just the enum definition itself. Confidence: 0.65

# enrichment
- pp_log must be independent of Elasticsearch — if pp_log finds a success for a lot or lot/wafer, treat it as sufficient without checking or overriding from ES results. Confidence: 0.85
- pp_log routes to PRODUCTION database while all other data sources use QA database. Confidence: 0.70
- Use updatedAt for pp_log temporal queries — updatedAt reflects when the record was (re)staged for third-party consumption, which matches process_datetime in pp_log. When first staged, createdAt and updatedAt are the same. Confidence: 0.70

# dead-code

- Dead or obsolete code should be annotated with `@Deprecated` + a Javadoc note (version, reason, migration guidance) rather than deleted, so the team can track what was identified and migrate callers over time. Deletion destroys that visibility. Confidence: 0.90

# project-structure
- In a monorepo with separate backend (Maven/Java) and frontend (Node), Maven build files belong only in `backend/` — no root-level Maven aggregator pom. The repo root stays clean of language-specific build tooling. Confidence: 0.60

# coding-style
- When fields/methods are renamed in records or classes, prefer adding backward-compatible alias methods (e.g., `stagedToRefdb()` → `ready()`, `failed()` → `cpFailed + loadFailed`) rather than rewriting every call site. This keeps diffs minimal, avoids cascading changes, and lets callers migrate gradually. Confidence: 0.40
- When record components or accessor names change during a refactor, prefer migrating all callers directly to the canonical names and removing the old aliases entirely — especially during a dead-code cleanup where adding deprecated aliases would be counterproductive. The clean approach is to fix call sites, not accumulate backward-compat shims. Confidence: 0.75
- Avoid JDK-internal `sun.*` APIs (e.g., `sun.security.x509.*`) that don't compile on modern JDKs; use public standard APIs or established libraries (e.g., BouncyCastle, already a transitive dependency) for things like self-signed certificate generation. Confidence: 0.70
- When editing a file, remove imports that become unused as a result of the change (and drop any leftover dead imports spotted in the same file) rather than leaving them behind. Confidence: 0.55

# error-handling
- Include diagnostic context in failure messages: ES failures should capture log.level, timestamp, and actual CP error message; pp_log failures should capture lot, idFile, process_code, and log_message; timeout/unresolved should document what was tried (ES, pp_log, Exensio) to aid operator investigation. Confidence: 0.60
- Include filename in enrichment diagnostic messages (ES failures, pp_log failures, timeout/unresolved summaries) for traceability. Confidence: 0.70

# exensio
- Exensio wafer IDs are not prefixed with "W" — do not reconstruct "W"-prefixed variants when matching wafer numbers in Exensio. Confidence: 0.75
in Exensio. Confidence: 0.75
_log failures, timeout/unresolved summaries) for traceability. Confidence: 0.70

# exensio
- Exensio wafer IDs are not prefixed with "W" — do not reconstruct "W"-prefixed variants when matching wafer numbers in Exensio. Confidence: 0.75
in Exensio. Confidence: 0.75
