# Refactoring DTP Resender Backend — XFCS Removal

## Objective
Remove all **XFCS/Exensio Dearchiver/Archive Reloader** functionality from `dtp-resender-fullstack`, keeping only **DTP Resender** features.

## Classification Method
Each file was traced through its dependency chain. A file is **XFCS-only** if it is only referenced by other XFCS files and has no DTP Resender consumers.

---

## Deleted Files (31 total) ✅

### Services (7 files)
| File | Reason |
|------|--------|
| `XfcsController.java` | XFCS archive reloader controller |
| `XfcsArchiveService.java` | XFCS archive scanning, env.conf caching, SSH remote operations |
| `XfcsDashboardService.java` | XFCS reload dashboard |
| `XfcsReloadService.java` | XFCS file reload orchestration |
| `EnvFolderResolver.java` | Resolves .mgr/.cfg files (XFCS infra only) |
| `ReloadErrorCode.java` | XFCS reload error codes |
| `SshClient.java` + `DefaultSshClient.java` | SSH — only used by deleted XFCS services |

### Entities (3 files)
`ReloadPendingFileEntity`, `ReloadSessionEntity`, `ReloadSessionEventEntity`

### Repositories (3 files)
`ReloadPendingFileRepository`, `ReloadSessionEventRepository`, `ReloadSessionRepository`

### DTOs (13 files)
`ArchiveLotDetail`, `ArchiveReloaderDetailsDto`, `ReloadFilterOptions`, `ReloadRequest`, `ReloadSession`, `ReloadSessionEventDto`, `ReloadSessionSummary`, `ReloadStatus`, `EnvYearRange`, `EnvPathInfo`, `DashboardData`, `DownloadFilesRequest`, `YearRange`

### Liquibase Changelogs (4 files)
`db.changelog-5.0-reload-sessions.xml`, `5.1-reload-sessions-enhancement.xml`, `5.2-add-reload-pending-file.xml`, `5.3-add-event-error-code.xml`

---

## Config Changes ✅

### `pom.xml`
- Removed `net.schmizz:sshj:0.34.0` dependency

### `application.yml`
- Context-path: `/exensio-dearchiver` → `/dtp-resender`
- Log file: `exensio-dearchiver.log` → `dtp-resender.log`
- Logging packages: `com.onsemi.cim.apps.exensio.*` → `com.onsemi.dtp.resender.*`
- Removed entire `xfcs:` config block (30 lines)
- Removed XFCS log level line

### `application-onsemi-oracle.yml`
- Removed ~130-line `xfcs:` config block (archives, SSH, env.conf, reload settings, servers, filename-parse-rules)
- Removed `exensio.metadata.forceAllView` block
- Renamed config key `exensio-dearchiver:` → `dtp-resender:`
- Updated logging packages and log file name
- Updated reset-url-base path from `/exensio-dearchiver/` to `/dtp-resender/`

### `DashboardController.java`
- Replaced broken `ExensioDearchiveService` import with direct `RefDbService` calls
- `dashboardService.getStageStatuses()` → `refDbService.fetchStatuses(null)`
- `dashboardService.getStageStatusesForUser()` → `refDbService.fetchStatusesForUser()`

### `db.changelog-1.0.xml`
- Removed 4 XFCS changelog includes (5.0–5.3)

---

## Kept Files (DTP Resender)

| Component | Files |
|-----------|-------|
| **Sender** | `SenderController`, `SenderService`, `SenderDispatchService`, `SenderQueueMonitor`, `SenderQueueRepository`, `SenderQueueEntry`, `SenderCandidate` |
| **Stage** | `StageController`, `StageRecordMapper`, `RefDbService`, `StageRecord`, `StageStatus`, `StageResult`, `StageMonitorService`, etc. |
| **Discovery** | `MetadataImporterService`, `DiscoveryScheduler`, `DiscoveryProperties` |
| **External DB** | `ExternalDbResolverService`, `ExternalLocationService`, `ExternalConnectionsController`, `ExternalLocationController`, `ExternalEnvironmentRepository`, `ExternalLocationRepository`, `JdbcExternalMetadataRepository` |
| **Sessions** | `SessionsController`, `SessionPushService`, `CompletionNotificationService`, `LoadSession`, `LoadSessionPayload`, `LoadSessionRepository`, `LoadSessionPayloadRepository` |
| **Auth** | `AuthController`, `AdminController`, `UserAdminController`, `SimpleUserController`, `SimpleUserAdminController`, `RegisterController`, `BasicUserController` |
| **Dashboard** | `DashboardController` (refactored to use `RefDbService` directly) |
| **Config** | JWT auth filters, `ConfigController`, `DiagnosticController`, `TestController`, `DevDbInspectController` |
| **User Mgmt** | `AppUserDetailsService`, `AuthTokenService`, `ModernAuthService`, `RoleService`, `UserManagementService`, `RefreshTokenService`, `MailService`, `MetricsService`, `AuditService` |

---

## Root-Level Files Analysis (ExensioDearchiver)

The 3 root-level files from `new_ed` (`ExensioDearchiverApplication`, `ExensioDearchiveService`, `ExensioDearchiveController`) were analyzed and determined **not needed**:

- `ExensioDearchiverApplication` — `onApplicationEvent` calls `MgrConfigParser.autoDiscoverEnvironmentVariables()` which is XFCS-only. `ResenderApplication.java` already has `@EnableScheduling`/`@EnableAsync`.
- `ExensioDearchiveService` — Thin delegation layer; all DTP methods already exist in `RefDbService` and `MetadataImporterService`.
- `ExensioDearchiveController` — All DTP endpoints already covered by `StageController`, `SenderController`, `ConfigController`.
