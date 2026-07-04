# Device Filter Reporting - Testing Guide

## Overview

This document provides comprehensive testing instructions for the Device Filter Reporting feature. Due to environment constraints, tests cannot be automatically executed but can be run locally by developers.

## Backend Test Execution

### Prerequisites

- Java 21 or higher
- Maven 3.8.0 or higher
- All backend dependencies installed via `mvn dependency:resolve`

### Running All Backend Tests

Execute the following command from the `backend/` directory:

```bash
# Run all tests with verbose output
mvn clean test

# Run tests with coverage report
mvn clean test jacoco:report

# Run only device-filter related tests
mvn test -Dtest=*Device*,*StagingSession*,*LoadSessionPayload*,*Analytics*

# Run with specific property test iterations
mvn test -Dtest=*PropertyTest* -DargLine="-Dproperty.test.iterations=100"
```

### Unit Tests to Execute

The following unit test classes should all pass:

#### Backend Unit Tests

1. **LoadSessionPayloadRepositoryTest**
   - Tests device column persistence
   - Tests device query methods
   - Tests NULL device handling

2. **StagingServiceTest**
   - Tests device capture during staging
   - Tests device extraction from metadata
   - Tests NULL device graceful handling

3. **SessionServiceTest**
   - Tests device filter application to session queries
   - Tests distinct device retrieval
   - Tests device filter with other filters

4. **AnalyticsServiceTest**
   - Tests device filter application to analytics calculations
   - Tests analytics with filtered device sets

5. **ApiControllerTest**
   - Tests /api/sessions/devices endpoint
   - Tests device parameter in GET /api/sessions
   - Tests device parameter in GET /api/analytics/summary
   - Tests device parameter in dashboard endpoints
   - Tests backward compatibility (requests without device param)

### Property-Based Tests to Execute

The following property-based tests should run with minimum 100 iterations each:

#### Backend Property Tests (Using JUnit 5 + AssertJ)

1. **Property 1: Device Persistence Round-Trip**
   - Test class: `LoadSessionPayloadPropertyTest`
   - Feature: device-filter-reporting
   - Validates: Requirements 1.1, 1.4, 5.3

2. **Property 2: NULL Device Handling**
   - Test class: `LoadSessionPayloadPropertyTest`
   - Feature: device-filter-reporting
   - Validates: Requirements 1.2, 5.4, 8.1, 8.4

3. **Property 3: Device Filter Correctness**
   - Test class: `StagingServicePropertyTest`
   - Feature: device-filter-reporting
   - Validates: Requirements 2.2, 3.2, 4.2, 7.2

4. **Property 6: Distinct Devices Accuracy**
   - Test class: `LoadSessionPayloadRepositoryPropertyTest`
   - Feature: device-filter-reporting
   - Validates: Requirements 2.5, 7.3

5. **Property 11: Paginated Filter Consistency**

- Test class: `ApiControllerPropertyTest`
- Feature: device-filter-reporting
- Validates: Requirements 7.4

12. **Property 12: Multi-Filter Composition**

- Test class: `SessionServicePropertyTest`
- Feature: device-filter-reporting
- Validates: Requirements 3.5, 7.5

### Verifying Test Coverage

After running tests, verify coverage with:

```bash
# Generate coverage report
mvn jacoco:report

# View coverage report (opens in browser on macOS)
open target/site/jacoco/index.html

# Check specific class coverage
cat target/site/jacoco/index.html | grep "LoadSessionPayload\|StagingService\|SessionService"
```

**Expected Minimum Coverage:**

- `LoadSessionPayload`: 90%
- `LoadSessionPayloadRepository`: 85%
- `StagingService`: 85%
- `SessionService`: 85%
- API Controllers: 80%

## Frontend Test Execution

### Prerequisites

- Node.js 20+ and npm 10+
- All frontend dependencies installed via `npm install`

### Running All Frontend Tests

Execute the following commands from the `frontend/` directory:

```bash
# Run all tests once (non-watch mode)
npm test -- --run

# Run tests with coverage
npm test -- --run --code-coverage

# Run only device-filter related tests
npm test -- --run --include="**/*device*filter*.spec.ts"

# Run with specific number of iterations for property tests
npm test -- --run --env="PROPERTY_TEST_ITERATIONS=100"
```

### Unit Tests to Execute

The following unit test classes should all pass:

1. **GlassDeviceFilterComponent.spec.ts**
   - Tests component initialization
   - Tests device options loading
   - Tests selection changes emit events
   - Tests model binding

2. **AnalyticsComponent.spec.ts**
   - Tests device filter integration
   - Tests filter state changes
   - Tests API calls include device parameter

3. **MySessionsComponent.spec.ts**
   - Tests device filter integration
   - Tests session filtering by device
   - Tests device display in session details

4. **DashboardComponent.spec.ts**
   - Tests device filter integration
   - Tests metric updates with device filter
   - Tests SSE event filtering by device

### Property-Based Tests to Execute

The following property-based tests should run with minimum 100 iterations each (using fast-check):

#### Frontend Property Tests

1. **Property 4: Unfiltered Default Behavior** (Analytics variant)
   - Test file: `analytics.component.property.spec.ts`
   - Feature: device-filter-reporting, Property 4
   - Validates: Requirements 2.3, 8.2

2. **Property 5: Filter State Persistence** (Analytics & Dashboard variants)
   - Test file: `analytics.component.property.spec.ts`
   - Test file: `dashboard.component.property.spec.ts`
   - Feature: device-filter-reporting, Property 5
   - Validates: Requirements 2.4, 4.5

3. **Property 7: Session Detail Device Display**
   - Test file: `my-sessions.component.property.spec.ts`
   - Feature: device-filter-reporting, Property 7
   - Validates: Requirements 3.3

4. **Property 8: Real-Time Filter Application**
   - Test file: `dashboard.component.property.spec.ts`
   - Feature: device-filter-reporting, Property 8
   - Validates: Requirements 4.3

5. **Property 9: Discovery Device Retrieval**
   - Test file: `discovery.component.property.spec.ts`
   - Feature: device-filter-reporting, Property 9
   - Validates: Requirements 5.1, 5.2

6. **Property 10: Preview Filter Accuracy**
   - Test file: `discovery.component.property.spec.ts`
   - Feature: device-filter-reporting, Property 10
   - Validates: Requirements 5.5

### Verifying Frontend Test Coverage

After running tests, check the coverage report:

```bash
# Coverage report is generated at:
# frontend/coverage/index.html

# Key metrics to verify:
# - GlassDeviceFilterComponent: >85% coverage
# - AnalyticsComponent: >80% coverage
# - MySessionsComponent: >80% coverage
# - DashboardComponent: >80% coverage
# - StagingSessionService: >90% coverage
```

## Test Execution Report Template

When running the full test suite, document results using this template:

```markdown
## Test Execution Report - Device Filter Reporting

**Date:** [DATE]
**Executor:** [YOUR NAME]
**Environment:** [DEV/STAGING]

### Backend Tests

- Total Tests: [X]
- Passed: [X]
- Failed: [X]
- Skipped: [X]
- Coverage: [X]%

#### Property Tests

- Property 1 (Device Persistence): [PASS/FAIL] - Iterations: [X]
- Property 2 (NULL Handling): [PASS/FAIL] - Iterations: [X]
- Property 3 (Device Filter): [PASS/FAIL] - Iterations: [X]
- Property 6 (Distinct Devices): [PASS/FAIL] - Iterations: [X]
- Property 11 (Pagination): [PASS/FAIL] - Iterations: [X]
- Property 12 (Multi-Filter): [PASS/FAIL] - Iterations: [X]

### Frontend Tests

- Total Tests: [X]
- Passed: [X]
- Failed: [X]
- Skipped: [X]
- Coverage: [X]%

#### Property Tests

- Property 4 (Unfiltered Default - Analytics): [PASS/FAIL] - Iterations: [X]
- Property 5 (Filter State - Analytics): [PASS/FAIL] - Iterations: [X]
- Property 5 (Filter State - Dashboard): [PASS/FAIL] - Iterations: [X]
- Property 7 (Session Detail Display): [PASS/FAIL] - Iterations: [X]
- Property 8 (Real-Time Filter): [PASS/FAIL] - Iterations: [X]
- Property 9 (Discovery Device Retrieval): [PASS/FAIL] - Iterations: [X]
- Property 10 (Preview Filter Accuracy): [PASS/FAIL] - Iterations: [X]

### Issues and Resolution

[Document any test failures and how they were resolved]

### Sign-off

- All tests passing: [ ] YES / [ ] NO
- Ready for deployment: [ ] YES / [ ] NO
```

## Continuous Integration

For CI/CD pipelines, use:

```bash
# Backend
cd backend && mvn clean verify

# Frontend
cd frontend && npm ci && npm test -- --run --code-coverage

# Combined check with exit codes
set -e
cd backend && mvn clean verify
cd ../frontend && npm ci && npm test -- --run
echo "All tests passed successfully"
```

## Troubleshooting

### Common Backend Test Issues

**Issue: Tests timeout**

```bash
# Increase timeout
mvn test -DargLine="-Dtimeout=60000"
```

**Issue: Database connection failures in tests**

```bash
# Verify H2 database is configured in test profile
# Check: backend/src/test/resources/application-test.yml
```

**Issue: Property test failures**

```bash
# Run with specific seed to reproduce failure
mvn test -Dtest=PropertyTest -DargLine="-Dproperty.seed=1234567890"

# Increase iterations to get better sample
mvn test -Dtest=PropertyTest -DargLine="-Dproperty.iterations=1000"
```

### Common Frontend Test Issues

**Issue: Tests timeout**

```bash
npm test -- --run --browsers=ChromeHeadless --timeout=60000
```

**Issue: Chrome not found**

```bash
# Use Firefox instead
npm test -- --run --browsers=Firefox
```

**Issue: Property test failures**

```bash
# Run with verbose output
npm test -- --run --reporters=verbose

# Run single property test
npm test -- --run --include="**/property*.spec.ts"
```

## Manual End-to-End Testing

See section 13.2 in tasks.md for manual end-to-end testing procedures.
