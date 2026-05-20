# XFCS Removal from DTP Resender — Walkthrough

## Summary
Removed all **XFCS/Exensio Dearchiver/Archive Reloader** components from `dtp-resender-fullstack`, leaving only DTP Resender functionality.

---

## Files Deleted (31 total)

### Java Files (27)
| Category | Files Removed |
|----------|---------------|
| **Controllers** | `XfcsController.java` |
| **Services** | `XfcsArchiveService`, `XfcsDashboardService`, `XfcsReloadService`, `EnvFolderResolver`, `ReloadErrorCode`, `SshClient`, `DefaultSshClient` |
| **Entities** | `ReloadPendingFileEntity`, `ReloadSessionEntity`, `ReloadSessionEventEntity` |
| **Repositories** | `ReloadPendingFileRepository`, `ReloadSessionEventRepository`, `ReloadSessionRepository` |
| **DTOs** | `ArchiveLotDetail`, `ArchiveReloaderDetailsDto`, `ReloadFilterOptions`, `ReloadRequest`, `ReloadSession`, `ReloadSessionEventDto`, `ReloadSessionSummary`, `ReloadStatus`, `EnvYearRange`, `EnvPathInfo`, `DashboardData`, `DownloadFilesRequest`, `YearRange` |

### Liquibase Changelogs (4)
`db.changelog-5.0-reload-sessions.xml`, `5.1`, `5.2`, `5.3`

---

## Files Modified

| File | Change |
|------|--------|
| `pom.xml` | Removed `net.schmizz:sshj` dependency |
| `application.yml` | Context-path → `/dtp-resender`, removed `xfcs:` block, updated logging |
| `application-onsemi-oracle.yml` | Removed ~130-line `xfcs:` block, `exensio:` block, updated logging and URLs |
| `DashboardController.java` | Replaced `ExensioDearchiveService` → `RefDbService.fetchStatuses()`/`fetchStatusesForUser()` |
| `db.changelog-1.0.xml` | Removed 5.0–5.3 reload changelog includes |

---

## Verification
- No `Xfcs` references remain in any `.java` file
- No `xfcs:` config blocks remain in YAML files
- `sshj` dependency removed (was only used by SSH client for XFCS)
- `DashboardController` refactored to use `RefDbService` directly
- All DTP Resender components (Sender, Stage, Discovery, External DB, Sessions, Auth, Dashboard) preserved
- Root-level `ExensioDearchiveService`/`Controller`/`Application` analyzed — no DTP functionality needed extraction

> **Note:** Build verification requires `mvn compile` on a machine with Java/Maven installed.
