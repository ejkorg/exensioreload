# Requirements Document

## Introduction

The Session Detail Modal in the My Sessions page currently presents dense, hard-to-scan information in a flat layout. This spec covers a full UI/UX redesign of that modal to make it visually centered, spatially balanced, and significantly more appealing — while preserving all existing functionality (metrics, analytics charts, file table, actions).

## Glossary

- **Modal**: The `detail-overlay` / `detail-modal` panel that opens when a user clicks a session row
- **Header_Zone**: The top area of the modal containing session ID, status badge, timestamps, and action buttons
- **Metrics_Bar**: The row of pill-shaped counters (Total, Staged, Enqueued, Done, Failed, Status)
- **Analytics_Panel**: The collapsible section containing date-range controls, preset buttons, status summary, and the two ECharts charts
- **Files_Panel**: The collapsible section containing the paginated file records table
- **Action_Toolbar**: The group of buttons (Refresh, Export Files CSV, Cancel, Close) in the header
- **Status_Badge**: Colored pill indicating session lifecycle state (COMPLETED, STAGING, CANCELLED, etc.)
- **Glassmorphism**: The existing design language — frosted-glass backgrounds, subtle borders, backdrop-blur

## Requirements

### Requirement 1: Centered, Constrained Modal Layout

**User Story:** As a user, I want the modal to appear perfectly centered on screen with comfortable proportions, so that it feels like a focused dialog rather than a page overlay.

#### Acceptance Criteria

1. WHEN the modal opens, THE Modal SHALL be horizontally and vertically centered within the viewport using CSS flexbox centering on the overlay
2. THE Modal SHALL have a maximum width of 900px and a maximum height of 88vh, with overflow-y scroll inside the modal body
3. THE Modal SHALL have a minimum width of 320px to remain usable on small screens
4. WHEN the viewport is narrower than 640px, THE Modal SHALL expand to 96vw width
5. THE Modal SHALL use `border-radius: 20px` and a layered `box-shadow` combining a dark ambient shadow and a colored glow (indigo/violet tones) to lift it off the backdrop

### Requirement 2: Redesigned Header Zone

**User Story:** As a user, I want the modal header to clearly communicate session identity and status at a glance, so that I don't have to hunt for key information.

#### Acceptance Criteria

1. THE Header_Zone SHALL display the truncated session ID in a monospace font with a subtle accent color, accompanied by a full-width gradient separator line beneath it
2. THE Status_Badge SHALL be visually prominent — larger padding, bolder font weight, and a soft glow matching the badge color — positioned inline with the session ID
3. THE Header_Zone SHALL show "Updated: {date}" and a "Copy ID" button in a secondary meta row beneath the title row
4. THE Action_Toolbar SHALL be grouped into a single pill-shaped container with consistent icon+label buttons, separated from the title area by clear visual spacing
5. WHEN the "Copy ID" button is clicked and the ID is copied, THE System SHALL show a brief "Copied ✓" confirmation state on the button for 2 seconds

### Requirement 3: Visual Metrics Bar Redesign

**User Story:** As a user, I want the file count metrics to be displayed as distinct, scannable cards rather than plain text pills, so that I can assess session health instantly.

#### Acceptance Criteria

1. THE Metrics_Bar SHALL render each metric (Total, Staged, Enqueued, Done, Failed) as a small card with an icon, a large numeric value, and a label beneath it
2. THE Metrics_Bar SHALL use a horizontal flex layout with equal-width cards that wrap on small screens
3. THE "Done" metric card SHALL use a green accent color scheme; the "Failed" card SHALL use a red accent; "Enqueued" SHALL use amber; "Staged" and "Total" SHALL use the primary indigo/violet accent
4. WHEN `filesFailed` is 0, THE Failed card SHALL be visually de-emphasized (reduced opacity) rather than hidden, so the layout remains stable
5. THE Status value SHALL be displayed as a standalone Status_Badge below the metrics cards row, not as a metric card itself

### Requirement 4: Collapsible Sections with Smooth Animation

**User Story:** As a user, I want the Analytics and Files sections to expand and collapse smoothly, so that I can focus on what matters without jarring layout jumps.

#### Acceptance Criteria

1. THE Analytics_Panel toggle button SHALL display a chevron icon that rotates 180° when the panel is expanded, using a CSS transition of 200ms ease
2. THE Files_Panel toggle button SHALL follow the same chevron rotation pattern
3. WHEN a panel expands or collapses, THE transition SHALL use `max-height` animation or Angular `@trigger` animation with a 250ms ease-in-out curve
4. THE toggle buttons SHALL have a consistent style: full-width, left-aligned label, right-aligned chevron, with a hover state that brightens the background slightly

### Requirement 5: Analytics Panel Layout Improvements

**User Story:** As a user, I want the analytics controls and charts to be laid out clearly so I can filter and interpret data without confusion.

#### Acceptance Criteria

1. THE date-range inputs and preset buttons SHALL be grouped in a single horizontal toolbar row with clear visual separation from the charts
2. THE status summary pills (Completed %, Failed %, etc.) SHALL be displayed in a dedicated summary row with color-coded backgrounds matching their status color
3. THE two ECharts containers (Daily Status Trend, Status Distribution) SHALL each have a minimum height of 220px and SHALL be placed in a responsive two-column grid that collapses to one column below 600px width
4. THE chart cards SHALL have a subtle inner border and background distinct from the panel background to visually separate them

### Requirement 6: Files Table Panel Improvements

**User Story:** As a user, I want the files table to be easy to read and scroll, so that I can inspect individual file records without losing context.

#### Acceptance Criteria

1. THE Files_Panel header SHALL show the file count badge inline with the toggle label (e.g. "Files Details · 100")
2. THE detail-table-wrap SHALL have a maximum height of 340px with internal scroll, sticky column headers, and alternating row backgrounds for readability
3. THE Status column in the files table SHALL render colored status badges consistent with the session-level Status_Badge styling
4. WHEN the files list is empty, THE Files_Panel toggle SHALL be hidden entirely

### Requirement 7: Responsive and Accessible Modal

**User Story:** As a user on any device, I want the modal to remain fully usable and readable, so that I can manage sessions from any screen size.

#### Acceptance Criteria

1. WHEN the viewport width is below 640px, THE Action_Toolbar SHALL stack vertically and each button SHALL be full-width
2. WHEN the viewport width is below 640px, THE Metrics_Bar cards SHALL display in a 2-column grid
3. THE modal close button (×) SHALL always be visible in the top-right corner of the modal, regardless of scroll position, using `position: sticky` on the header
4. THE overlay backdrop SHALL support click-to-close, and THE Modal SHALL trap focus while open (no tab-focus escaping to background content)
5. THE Modal SHALL include `role="dialog"`, `aria-modal="true"`, and `aria-labelledby` pointing to the session ID heading for screen reader support

### Requirement 8: Light Theme Parity

**User Story:** As a user who prefers the light theme, I want the redesigned modal to look equally polished, so that the experience is consistent regardless of theme.

#### Acceptance Criteria

1. ALL new color values, backgrounds, and borders SHALL have corresponding `:host-context(body.light-theme)` overrides
2. THE light theme modal SHALL use a white/near-white frosted glass background with indigo-tinted borders and shadows
3. THE metric cards, toggle buttons, and chart cards SHALL all have distinct light-theme variants that maintain sufficient contrast ratios
