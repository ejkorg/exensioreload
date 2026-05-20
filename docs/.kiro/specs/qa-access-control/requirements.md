# Requirements Document

## Introduction

Currently, the stepper's environment selector shows both `PROD` and `QA` options to all authenticated users, and QA sites/locations are accessible to everyone. QA environments are intended for internal testing only and should be restricted to admin and super-admin users. Regular users should only see and interact with production environments.

## Glossary

- **Regular_User**: An authenticated user whose roles do not include `ADMIN` or `SUPER_ADMIN`.
- **Admin_User**: An authenticated user with the `ADMIN` or `SUPER_ADMIN` role.
- **AuthService**: The Angular service that exposes `isAdmin()` and `isSuperAdmin()` role-check methods.
- **Stepper**: The multi-step resend request workflow component (`stepper.component`).
- **Environment_Selector**: The dropdown in Step 1 of the Stepper that allows selection of `PROD` or `QA`.
- **Site_Selector**: The dropdown in Step 1 of the Stepper that lists available DTP instances filtered by the selected environment.
- **Location_Selector**: The dropdown in Step 1 of the Stepper that lists available locations for the selected site.
- **QA_Environment**: The `QA` option in the environment selector, corresponding to sites with the `-QA` suffix.
- **PROD_Environment**: The `PROD` option in the environment selector, corresponding to sites with the `-PROD` suffix.

---

## Requirements

### Requirement 1: Environment Selector Visibility

**User Story:** As a regular user, I want the environment selector to only show production options, so that I cannot accidentally submit data to QA systems.

#### Acceptance Criteria

1. WHEN a Regular_User views the Stepper Step 1, THE Environment_Selector SHALL only display the `PROD` option.
2. WHEN an Admin_User views the Stepper Step 1, THE Environment_Selector SHALL display both `PROD` and `QA` options.
3. THE Stepper SHALL derive the available environment options from the current user's roles via AuthService at component initialization.

---

### Requirement 2: QA Site Filtering

**User Story:** As a regular user, I want the site list to only show production sites, so that I cannot select a QA site even if I manipulate the environment value.

#### Acceptance Criteria

1. WHEN a Regular_User has the `PROD` environment selected, THE Site_Selector SHALL only display sites with the `-PROD` suffix.
2. WHEN an Admin_User has the `QA` environment selected, THE Site_Selector SHALL display sites with the `-QA` suffix.
3. IF a Regular_User attempts to load sites for a `QA` environment (e.g., via direct API call), THE Stepper SHALL not render QA sites in the Site_Selector.

---

### Requirement 3: QA Location Filtering

**User Story:** As a regular user, I want the location list to only show locations from production sites, so that QA locations are never accessible to me.

#### Acceptance Criteria

1. WHEN a Regular_User selects a site, THE Location_Selector SHALL only display locations associated with PROD sites.
2. WHEN an Admin_User selects a QA site, THE Location_Selector SHALL display locations associated with that QA site.

---

### Requirement 4: Default Environment for Regular Users

**User Story:** As a regular user, I want the environment to default to PROD automatically, so that I do not need to manually select it every time.

#### Acceptance Criteria

1. WHEN a Regular_User opens the Stepper, THE Environment_Selector SHALL be pre-selected to `PROD` by default.
2. WHEN an Admin_User opens the Stepper, THE Environment_Selector SHALL remain unselected (requiring explicit choice) as it does today.
