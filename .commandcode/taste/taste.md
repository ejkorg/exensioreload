# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/


# git
- Commit .vscode/ directory with shared workspace config files (settings, launch, tasks, extensions) for team-wide IDE setup. Confidence: 0.75

# communication
- When the user asks for a command or instruction (e.g., "what is the command"), answer with the command/instructions directly before running system diagnostics or availability checks. Confidence: 0.55

# workflow
- Tracks long multi-step migration tasks via a markdown checklist file (e.g., ORACLE_TO_POSTGRES_INTERNAL_DB_CHECKLIST.md) and expects work to resume from the last completed item and drive the checklist to completion (e.g., "go ahead finish these check list"). Confidence: 0.78
- Verify factual claims about the codebase against the actual code (grep/read) before asserting them; user pushes back on unverified statements (e.g., "check again" when told a table isn't created by any Liquibase changelog). Confidence: 0.75

# database
See [database/taste.md](database/taste.md)
# documentation
- Check if docs are already up-to-date before writing new documentation files; don't rewrite existing docs unnecessarily. Confidence: 0.65
- Checklist/documentation wording must accurately reflect the chosen approach (e.g., "add a profile" rather than "replace"), and when the approach changes, all sections of the doc (Goal, Scope, Rollback, Acceptance Criteria, cleanup) should be reframed consistently so no lingering contradictory wording remains. Confidence: 0.80
- Prefers a clean project root with only backend/, frontend/, and docs/ directories — no scattered root-level markdown files. Documentation consolidates into docs/exensio.md, a single AI-context file (like CLAUDE.md) with up-to-date information about the current codebase. Confidence: 0.65

# logging
- Disable or reduce Spring Boot DEBUG console logging to minimize noise. Confidence: 0.70
- Make logging configurable via YAML properties rather than hardcoded changes. Confidence: 0.75
- Log datasource identity at startup and query time to make database routing visible (e.g., QA vs PRD refdb). Confidence: 0.70

# status-naming
- Use lowercase status names (e.g., pending, staging, completed, failed) following xfcs-reloader convention rather than uppercase abbreviations like NEW, ENRICHMENT, DONE. Confidence: 0.70

# enrichment
- pp_log must be independent of Elasticsearch — if pp_log finds a success for a lot or lot/wafer, treat it as sufficient without checking or overriding from ES results. Confidence: 0.85
- pp_log routes to PRODUCTION database while all other data sources use QA database. Confidence: 0.70
- Use updatedAt for pp_log temporal queries — updatedAt reflects when the record was (re)staged for third-party consumption, which matches process_datetime in pp_log. When first staged, createdAt and updatedAt are the same. Confidence: 0.70

# dead-code

- Dead or obsolete code should be annotated with `@Deprecated` + a Javadoc note (version, reason, migration guidance) rather than deleted, so the team can track what was identified and migrate callers over time. Deletion destroys that visibility. Confidence: 0.90

# project-structure
- In a monorepo with separate backend (Maven/Java) and frontend (Node), Maven build files belong only in `backend/` — no root-level Maven aggregator pom. The repo root stays clean of language-specific build tooling. Confidence: 0.60

# coding-style
- When fields/methods are renamed in records or classes, prefer adding backward-compatible alias methods (e.g., `stagedToRefdb()` → `ready()`, `failed()` → `cpFailed + loadFailed`) rather than rewriting every call site. This keeps diffs minimal, avoids cascading changes, and lets callers migrate gradually. Confidence: 0.65

# error-handling
- Include diagnostic context in failure messages: ES failures should capture log.level, timestamp, and actual CP error message; pp_log failures should capture lot, idFile, process_code, and log_message; timeout/unresolved should document what was tried (ES, pp_log, Exensio) to aid operator investigation. Confidence: 0.60
- Include filename in enrichment diagnostic messages (ES failures, pp_log failures, timeout/unresolved summaries) for traceability. Confidence: 0.70

# exensio
- Exensio wafer IDs are not prefixed with "W" — do not reconstruct "W"-prefixed variants when matching wafer numbers in Exensio. Confidence: 0.75
