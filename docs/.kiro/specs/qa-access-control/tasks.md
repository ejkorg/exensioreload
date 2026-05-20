# Implementation Plan: QA Access Control

## Overview

Restrict QA environment visibility in the stepper to admin and super-admin users only. Regular users see only PROD, which is auto-selected on init. Changes are confined to `stepper.component.ts` and `stepper.component.html`.

## Tasks

- [x] 1. Add role-based environment options to StepperComponent
  - Inject `AuthService` into `stepper.component.ts`
  - Add computed signal: `readonly envOptions = computed<('PROD' | 'QA')[]>(() => this.authService.isAdmin() ? ['PROD', 'QA'] : ['PROD'])`
  - In `ngOnInit` (or constructor effect): if `!this.authService.isAdmin()`, call `this.onEnvChange('PROD')` to auto-select PROD and trigger site loading
  - _Requirements: 1.1, 1.2, 1.3, 4.1, 4.2_

- [ ]* 1.1 Write property test for environment options based on role (Property 1)
  - Use fast-check: generate random role arrays; assert `envOptions` contains `QA` iff roles include `ADMIN` or `SUPER_ADMIN`
  - **Property 1: Environment options match user role**
  - **Validates: Requirements 1.1, 1.2**

- [x] 2. Update stepper template to use dynamic environment options
  - In `stepper.component.html`, replace `[options]="['PROD', 'QA']"` with `[options]="envOptions()"`
  - _Requirements: 1.1, 1.2_

- [ ]* 2.1 Write property test for site list filtering (Property 2)
  - Use fast-check: generate random mixed site lists (some `-PROD`, some `-QA`); assert that for regular users the rendered list contains no `-QA` sites
  - **Property 2: Site list contains only role-appropriate sites**
  - **Validates: Requirements 2.1, 2.2**

- [x] 3. Checkpoint — Ensure all tests pass, ask the user if questions arise. - ## Execution Constraints - Do not rely on `node`, `npm`, `java`, `JDK`, or `JRE` being available in this workspace. - The environment does not have the privilege to install those runtimes. - Do not run build, test, or install commands that depend on those runtimes after code changes

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- `AuthService.isAdmin()` already returns `true` for both `ADMIN` and `SUPER_ADMIN` — no new role logic needed
- The existing `listSitesForEnvironment()` already filters by `-PROD` / `-QA` suffix — no backend changes required
- Auto-selecting PROD for regular users also triggers `onEnvChange('PROD')` which loads the site list immediately
