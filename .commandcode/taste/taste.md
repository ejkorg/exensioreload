# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/


# git
- Commit .vscode/ directory with shared workspace config files (settings, launch, tasks, extensions) for team-wide IDE setup. Confidence: 0.75

# documentation
- Check if docs are already up-to-date before writing new documentation files; don't rewrite existing docs unnecessarily. Confidence: 0.65

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

# error-handling
- Include diagnostic context in failure messages: ES failures should capture log.level, timestamp, and actual CP error message; pp_log failures should capture lot, idFile, process_code, and log_message; timeout/unresolved should document what was tried (ES, pp_log, Exensio) to aid operator investigation. Confidence: 0.60
- Include filename in enrichment diagnostic messages (ES failures, pp_log failures, timeout/unresolved summaries) for traceability. Confidence: 0.70
