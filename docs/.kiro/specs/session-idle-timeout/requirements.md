# Requirements Document

## Introduction

The current session expiry UX triggers a warning modal based purely on the JWT token's `exp` claim — it fires 2 minutes before the token expires regardless of whether the user is actively using the application. This means a user actively working in the app can be interrupted by an expiry warning even though they are not idle.

The correct behavior is inactivity-based: the session expiry countdown should only begin when the user has stopped interacting with the application. If the user is active, the token should be silently refreshed in the background and the warning should never appear.

This feature adds an idle detection layer to the existing session expiry system. The JWT token refresh mechanism already exists; this feature controls *when* the warning and expiry UX is shown based on user activity.

## Glossary

- **Idle_Timer**: A countdown that starts (or resets) based on user activity. When it reaches zero, the session warning is triggered.
- **Activity_Event**: Any user interaction with the application UI — mouse movement, mouse click, keyboard press, or touch event.
- **Inactivity_Threshold**: The duration of no activity after which the session warning modal is shown. Default: 25 minutes.
- **Expiry_Threshold**: The duration of no activity after which the session is considered expired and the user is logged out. Default: 30 minutes.
- **Warning_Window**: The period between the Inactivity_Threshold and the Expiry_Threshold (5 minutes / 300 seconds) during which the warning modal is shown with a countdown.
- **SessionExpiryService**: The existing Angular service responsible for session warning and expiry events, modal coordination, and singleton guard.
- **AuthService**: The existing Angular service managing JWT tokens and refresh scheduling.
- **SessionWarningModalComponent**: The existing modal shown during the Warning_Window with a countdown and "Stay Logged In" / "Log Out" actions.
- **SessionExpiredModalComponent**: The existing modal shown when the session has fully expired.

---

## Requirements

### Requirement 1: Idle Detection

**User Story:** As a logged-in user, I want the session timer to only count down when I am not using the app, so that I am not interrupted by expiry warnings while actively working.

#### Acceptance Criteria

1. THE SessionExpiryService SHALL listen for `mousemove`, `mousedown`, `keydown`, and `touchstart` events on the `document` to detect user activity.
2. WHEN an Activity_Event is detected, THE SessionExpiryService SHALL reset the Idle_Timer to the Inactivity_Threshold (25 minutes).
3. WHEN an Activity_Event is detected while the SessionWarningModalComponent is open, THE SessionExpiryService SHALL close the warning modal and reset the Idle_Timer.
4. THE SessionExpiryService SHALL register activity listeners only while a user session is active (token present) and remove them when the session ends.

---

### Requirement 2: Inactivity-Based Warning

**User Story:** As a logged-in user, I want to be warned after 25 minutes of inactivity, so that I have time to extend my session before being logged out.

#### Acceptance Criteria

1. WHEN the Idle_Timer reaches the Inactivity_Threshold (25 minutes) with no Activity_Event, THE SessionExpiryService SHALL emit a session-warning event.
2. WHEN a session-warning event is emitted, THE SessionWarningModalComponent SHALL be displayed with a countdown showing the Warning_Window duration (300 seconds).
3. WHILE the SessionWarningModalComponent is visible and no Activity_Event occurs, THE countdown SHALL decrement every second from 300 to 0.
4. WHEN the user clicks "Stay Logged In" in the SessionWarningModalComponent, THE SessionExpiryService SHALL call the token refresh endpoint, close the modal, and reset the Idle_Timer.
5. WHEN the user clicks "Log Out" in the SessionWarningModalComponent, THE AuthService SHALL perform a logout and navigate to `/login`.

---

### Requirement 3: Inactivity-Based Expiry

**User Story:** As a logged-in user, I want my session to expire after 30 minutes of inactivity, so that my account is protected when I leave my workstation unattended.

#### Acceptance Criteria

1. WHEN the Idle_Timer reaches the Expiry_Threshold (30 minutes) with no Activity_Event, THE SessionExpiryService SHALL emit a session-expired event.
2. WHEN a session-expired event is emitted, THE SessionExpiredModalComponent SHALL be displayed.
3. WHEN the countdown in the SessionWarningModalComponent reaches zero, THE SessionExpiryService SHALL emit a session-expired event and transition to the SessionExpiredModalComponent.

---

### Requirement 4: Activity Resets Warning State

**User Story:** As an active user, I want any interaction I make to dismiss the session warning, so that I am not forced to click "Stay Logged In" while I am already working.

#### Acceptance Criteria

1. WHEN an Activity_Event is detected while the SessionWarningModalComponent is open, THE SessionWarningModalComponent SHALL be closed automatically.
2. WHEN the SessionWarningModalComponent is closed due to activity, THE SessionExpiryService SHALL reset the Idle_Timer to the Inactivity_Threshold (25 minutes).
3. WHEN the SessionWarningModalComponent is closed due to activity, THE SessionExpiryService SHALL silently refresh the token in the background.

---

### Requirement 5: JWT Token Refresh Coordination

**User Story:** As a developer, I want the idle timer and JWT refresh to work together, so that active users never experience token expiry interruptions.

#### Acceptance Criteria

1. WHEN a user is active and the JWT token is within 30 seconds of expiry, THE AuthService SHALL silently refresh the token without showing any modal.
2. WHEN the token is successfully refreshed, THE SessionExpiryService SHALL reset the Idle_Timer to the Inactivity_Threshold (25 minutes).
3. WHEN the token refresh fails while the user is active, THE SessionExpiryService SHALL emit a session-expired event.

---

### Requirement 6: Session Lifecycle Cleanup

**User Story:** As a developer, I want activity listeners to be properly cleaned up, so that there are no memory leaks when the user logs out.

#### Acceptance Criteria

1. WHEN the user logs out, THE SessionExpiryService SHALL remove all document-level activity event listeners.
2. WHEN the user logs out, THE SessionExpiryService SHALL cancel the Idle_Timer.
3. WHEN a new session is established (login or token refresh), THE SessionExpiryService SHALL re-register activity listeners and reset the Idle_Timer.
