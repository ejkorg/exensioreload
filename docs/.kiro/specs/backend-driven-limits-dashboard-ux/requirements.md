# Requirements Document

## Introduction

This feature aligns the frontend monitoring and dashboard with backend-authoritative runtime limits, removes hardcoded capacity assumptions, and improves operator-first UX on the dashboard. Currently, `monitorPageSize` and `monitorMaxRows` are read exclusively from environment files, the `/api/config/limits` endpoint exists in the backend but is never called by the frontend, and the dashboard hardcodes `1000` as a backlog capacity reference. This work makes the backend the single source of truth for all limits, keeps environment values only as resilience fallbacks, and surfaces actionable context to operators during high-volume monitoring.

## Glossary

- **Backend_Limits**: The runtime limit values returned by `GET /api/config/limits`, sourced from `application.yml` properties (`app.preview.*`, `app.stage.*`).
- **Environment_Fallback**: The values in `environment.ts` / `environment.prod.ts` used only when the limits API is unavailable.
- **LimitsConfig**: The TypeScript interface `{ previewMaxRowsCap, previewFetchCap, stagePageSizeCap, stageMaxRowsCap, stageDefaultMaxRows }` already defined in `backend.service.ts`.
- **monitorPageSize**: The number of file records fetched per API page call during session monitoring.
- **monitorMaxRows**: The hard ceiling on total file records hydrated into the UI for a single session.
- **BackendService**: The Angular service `backend.service.ts` responsible for all HTTP calls to the backend.
- **StagingSessionService**: The Angular service `staging-session.service.ts` that drives session monitoring, SSE, and file hydration.
- **Dashboard**: The Angular component `dashboard.component.ts` / `dashboard.component.html` showing global metrics, site/sender cards, and quick actions.
- **Backlog_Capacity**: The reference value used to render the backlog fill bar and tooltip on sender cards in the Dashboard.
- **Fallback_Banner**: A non-blocking UI banner shown when the limits API call fails and environment fallback values are in use.
- **Operator**: A user of the Dashboard who monitors and acts on queue pressure, backlog, and staging sessions.

## Requirements

### Requirement 1: Expose getLimits in BackendService

**User Story:** As a frontend developer, I want a single method to fetch runtime limits from the backend, so that all Angular services can consume authoritative values without duplicating HTTP logic.

#### Acceptance Criteria

1. THE BackendService SHALL expose a `getLimits()` method that calls `GET /api/config/limits` and returns an `Observable<LimitsConfig>`.
2. WHEN the `GET /api/config/limits` call fails, THE BackendService SHALL return an `Observable` that emits the environment fallback `LimitsConfig` values and does not throw.
3. THE BackendService `getLimits()` method SHALL apply a timeout of no more than 5000 ms before falling back to environment values.

### Requirement 2: Wire Runtime Limits into StagingSessionService

**User Story:** As an operator, I want the monitoring page size and max rows to reflect what the backend is actually configured to handle, so that the UI never silently truncates or over-fetches beyond the server's capacity.

#### Acceptance Criteria

1. WHEN StagingSessionService initializes, THE StagingSessionService SHALL call `BackendService.getLimits()` to resolve `monitorPageSize` and `monitorMaxRows` before the first file hydration begins.
2. THE StagingSessionService SHALL use `stagePageSizeCap` from Backend_Limits as `monitorPageSize`, falling back to `environment.monitoring.monitorPageSize` only when the API is unavailable.
3. THE StagingSessionService SHALL use `stageMaxRowsCap` from Backend_Limits as `monitorMaxRows`, falling back to `environment.monitoring.monitorMaxRows` only when the API is unavailable.
4. WHILE Backend_Limits are loading, THE StagingSessionService SHALL defer file hydration until limits are resolved.
5. IF the limits API call fails, THEN THE StagingSessionService SHALL log a warning and proceed with environment fallback values.

### Requirement 3: Baseline Environment Files as Fallback-Only

**User Story:** As a developer, I want the environment files to clearly document that their monitoring values are fallbacks, so that no future code accidentally treats them as primary configuration.

#### Acceptance Criteria

1. THE `environment.ts` file SHALL retain `monitorPageSize` and `monitorMaxRows` under `monitoring` with their current numeric values.
2. THE `environment.prod.ts` file SHALL retain `monitorPageSize` and `monitorMaxRows` under `monitoring` with their current numeric values.
3. WHEN environment monitoring values are used as fallback, THE StagingSessionService SHALL not apply additional clamping beyond what the environment values already specify.

### Requirement 4: Fallback Banner for Limit Resolution Failure

**User Story:** As an operator, I want to know when the UI is operating on local fallback limits rather than backend-authoritative values, so that I can make informed decisions about data accuracy.

#### Acceptance Criteria

1. WHEN the limits API call fails and environment fallback values are active, THE Dashboard SHALL display a non-blocking informational banner with the text "Using local fallback limits — backend configuration unavailable."
2. WHEN the limits API call succeeds, THE Dashboard SHALL not display the fallback banner.
3. THE fallback banner SHALL be dismissible by the operator without reloading the page.
4. THE fallback banner SHALL not block or obscure any primary dashboard content.

### Requirement 5: Backend-Driven Backlog Capacity on Dashboard Sender Cards

**User Story:** As an operator, I want the backlog fill bar and tooltip on sender cards to reflect the backend's actual stage max rows cap, so that the visual pressure indicator is meaningful rather than arbitrary.

#### Acceptance Criteria

1. THE Dashboard SHALL resolve `Backlog_Capacity` from `Backend_Limits.stageMaxRowsCap` rather than the hardcoded value `1000`.
2. WHEN Backend_Limits are unavailable, THE Dashboard SHALL use `environment.monitoring.monitorMaxRows` as `Backlog_Capacity`.
3. THE `getBacklogCapacity()` method in `dashboard.component.ts` SHALL accept the resolved limit value rather than returning a constant.
4. THE `getBacklogTooltip()` method SHALL produce a string in the format `"X / Y backlog (cap: Z)"` where X is current backlog, Y is the resolved capacity, and Z is the Backend_Limits cap value.

### Requirement 6: Operator-Facing "Showing X of Y (cap Z)" Copy

**User Story:** As an operator, I want the monitoring file list to show exactly how many records are displayed versus the total and the system cap, so that I understand whether I am seeing all files or a truncated view.

#### Acceptance Criteria

1. WHEN the session file list is rendered, THE Dashboard SHALL display a label in the format `"Showing X of Y (cap: Z)"` where X is the count of loaded records, Y is the session's `totalFiles`, and Z is the resolved `monitorMaxRows`.
2. WHEN X equals Y, THE Dashboard SHALL omit the cap suffix and display `"Showing X of Y"`.
3. WHEN X is less than Y and X equals Z, THE Dashboard SHALL display `"Showing X of Y (cap reached)"` to indicate truncation.
4. THE copy SHALL not use wording that implies the cap value equals the total number of files the system can handle.

### Requirement 7: Canonical Limit Properties in application.yml

**User Story:** As a backend developer, I want all preview and stage limits to be declared in `application.yml` with explicit defaults, so that operators can override them via environment variables without modifying code.

#### Acceptance Criteria

1. THE `application.yml` SHALL declare `app.preview.max-rows-cap`, `app.preview.fetch-cap`, `app.stage.page-size-cap`, `app.stage.max-rows-cap`, and `app.stage.default-max-rows` with their current default values.
2. THE `ConfigController` SHALL read each limit exclusively from the corresponding `@Value` property, with no hardcoded fallback values in the controller body.
3. WHEN `GET /api/config/limits` is called, THE ConfigController SHALL return all five limit fields in the response body.

### Requirement 8: Operator-First Dashboard Visual Improvements

**User Story:** As an operator, I want stronger visual separation for queue pressure and actionable status chips on sender cards, so that I can identify and act on critical senders faster during high-volume events.

#### Acceptance Criteria

1. WHEN a sender's backlog fill ratio exceeds 0.75, THE Dashboard SHALL apply a `warning` visual state to the sender card's fill bar with a distinct amber color.
2. WHEN a sender's backlog fill ratio exceeds 1.0, THE Dashboard SHALL apply a `critical` visual state to the sender card's fill bar with a distinct red color.
3. THE Dashboard SHALL render a status chip on each sender card that displays the current backlog status label (`"Normal"`, `"Warning"`, or `"Critical"`).
4. WHEN a sender card is in `warning` or `critical` state, THE Dashboard SHALL display a persistent quick-action button labeled `"Dispatch"` adjacent to the sender card metrics.
5. THE `"Dispatch"` quick-action button SHALL be visible without requiring the operator to expand or hover over the sender card.
