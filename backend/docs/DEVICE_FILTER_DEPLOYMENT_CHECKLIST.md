# Device Filter Reporting - Deployment Checklist

**Feature:** Device Filter Reporting  
**Version:** 1.0.0  
**Deployment Date:** [TO BE FILLED]  
**Deployment Engineer:** [TO BE FILLED]

---

## Pre-Deployment Verification

### Code Review

- [ ] All code changes reviewed and approved
- [ ] No security vulnerabilities identified
- [ ] Code follows project style guidelines
- [ ] All TODO comments addressed or documented
- [ ] No hardcoded credentials or sensitive data
- [ ] Dependencies are properly declared and pinned

### Test Results

- [ ] All backend unit tests passing (100%)
- [ ] All frontend unit tests passing (100%)
- [ ] All property-based tests passing (minimum 100 iterations each):
  - [ ] Property 1: Device Persistence Round-Trip
  - [ ] Property 2: NULL Device Handling
  - [ ] Property 3: Device Filter Correctness
  - [ ] Property 4: Unfiltered Default Behavior
  - [ ] Property 5: Filter State Persistence (Analytics)
  - [ ] Property 5: Filter State Persistence (Dashboard)
  - [ ] Property 6: Distinct Devices Accuracy
  - [ ] Property 7: Session Detail Device Display
  - [ ] Property 8: Real-Time Filter Application
  - [ ] Property 9: Discovery Device Retrieval
  - [ ] Property 10: Preview Filter Accuracy
  - [ ] Property 11: Paginated Filter Consistency
  - [ ] Property 12: Multi-Filter Composition
- [ ] E2E testing completed and all scenarios passed:
  - [ ] Discovery → Staging → Analytics flow
  - [ ] Analytics device filtering
  - [ ] My Sessions device filtering
  - [ ] Dashboard device filtering
  - [ ] API backward compatibility
  - [ ] Metadata preservation
  - [ ] Performance verification
- [ ] Code coverage acceptable:
  - [ ] Backend coverage: >85% overall
  - [ ] Frontend coverage: >80% overall
  - [ ] Critical paths: >95% coverage
- [ ] No regressions detected in existing functionality
- [ ] Database migration tested in dev environment

### Documentation Review

- [ ] Testing guide complete and accurate
- [ ] E2E testing procedures documented
- [ ] API documentation updated and reviewed
- [ ] Deployment checklist prepared
- [ ] Rollback procedure documented
- [ ] All code comments added where needed
- [ ] README/changelog updated

---

## Database Deployment

### Pre-Deployment Database Tasks

- [ ] Database backup taken (all environments)
- [ ] Backup location documented: ********\_\_\_********
- [ ] Backup verification completed
- [ ] Migration script tested in dev environment
- [ ] Migration estimated runtime calculated: **\_** minutes
- [ ] No active long-running transactions during migration window
- [ ] Maintenance window scheduled
- [ ] Stakeholders notified of maintenance window

### Database Migration Steps

**Environment: DEV**

```bash
# Test migration in development
mvn liquibase:update -Denv=dev

# Verify migration
mysql -u root -p < verify_migration.sql

# Verify device column exists and is indexed
SHOW COLUMNS FROM load_session_payload WHERE Field = 'device';
SHOW INDEX FROM load_session_payload WHERE Key_name = 'idx_load_session_payload_device';
```

- [ ] Migration successful in DEV
- [ ] Device column created
- [ ] Index created
- [ ] Data integrity verified
- [ ] No errors in application logs

**Environment: STAGING**

```bash
# Apply migration to staging
mvn liquibase:update -Denv=staging

# Verify migration
mysql -u staging_user -p < verify_migration.sql
```

- [ ] Migration successful in STAGING
- [ ] Device column created
- [ ] Index created
- [ ] Data integrity verified
- [ ] Application logs monitored

**Environment: PRODUCTION**

```bash
# Apply migration to production (during maintenance window)
mvn liquibase:update -Denv=prod

# Verify migration
mysql -u prod_user -p < verify_migration.sql
```

- [ ] Database backup taken before migration
- [ ] Migration successful in PROD
- [ ] Device column created
- [ ] Index created
- [ ] Data integrity verified
- [ ] No errors in application logs
- [ ] Performance monitoring shows normal queries
- [ ] Migration time logged: **\_** minutes

### Post-Migration Verification

- [ ] Existing records have NULL device values (backward compat)
- [ ] Device index is being used in queries
- [ ] Query performance maintained or improved
- [ ] NULL device queries work correctly
- [ ] No deadlocks or lock timeouts

---

## Backend Deployment

### Pre-Deployment Backend Tasks

- [ ] Backend build successful locally
- [ ] Build artifact generated: ********\_\_\_********
- [ ] Build logs checked for warnings/errors
- [ ] Artifact versioning correct (1.0.0)
- [ ] Docker image built (if applicable)
- [ ] Docker image tagged correctly
- [ ] All dependencies resolved

### Backend Deployment Steps

**Environment: DEV**

```bash
# Build and deploy to dev
mvn clean package
docker build -t exensio-reload:1.0.0 .
docker tag exensio-reload:1.0.0 dev-registry/exensio-reload:1.0.0
docker push dev-registry/exensio-reload:1.0.0

# Deploy
kubectl apply -f deployment-dev.yaml
kubectl rollout status deployment/exensio-reload -n dev
```

- [ ] Build successful
- [ ] Deployment to DEV successful
- [ ] Pods started and healthy
- [ ] Service endpoints responding
- [ ] No startup errors
- [ ] Database migrations applied automatically

**Environment: STAGING**

```bash
# Deploy to staging
docker tag exensio-reload:1.0.0 staging-registry/exensio-reload:1.0.0
docker push staging-registry/exensio-reload:1.0.0

kubectl apply -f deployment-staging.yaml
kubectl rollout status deployment/exensio-reload -n staging
```

- [ ] Deployment to STAGING successful
- [ ] Pods started and healthy
- [ ] Service endpoints responding
- [ ] Health checks passing
- [ ] Application logs normal

**Environment: PRODUCTION**

```bash
# Deploy to production (during maintenance window)
docker tag exensio-reload:1.0.0 prod-registry/exensio-reload:1.0.0
docker push prod-registry/exensio-reload:1.0.0

# Blue-green deployment (recommended)
kubectl apply -f deployment-prod-green.yaml
kubectl wait --for=condition=available --timeout=300s deployment/exensio-reload-green -n prod
# Test green environment
curl https://exensio-reload-green.example.com/health

# Switch traffic
kubectl patch service exensio-reload -p '{"spec":{"selector":{"version":"green"}}}'

# Monitor
kubectl logs -f deployment/exensio-reload-green -n prod
```

- [ ] Build pushed to production registry
- [ ] Green environment deployed successfully
- [ ] Health checks passing in green
- [ ] Smoke tests pass in green
- [ ] Traffic switched to green (blue-green deployment)
- [ ] Blue environment kept online for quick rollback (if needed)
- [ ] Application logs monitored for errors
- [ ] Performance metrics normal
- [ ] No alerts triggered

---

## Frontend Deployment

### Pre-Deployment Frontend Tasks

- [ ] Frontend build successful locally: `npm run build:prod`
- [ ] Build artifact location: `frontend/dist/exensio-reload/`
- [ ] Build output size verified (no bloat): **\_** MB
- [ ] Source maps generated for debugging
- [ ] No console errors or warnings
- [ ] All assets optimized (CSS, JS minified)
- [ ] Service worker/cache updated if applicable

### Frontend Deployment Steps

**Environment: DEV**

```bash
# Build and deploy to dev CDN/server
npm run build:prod
aws s3 cp dist/exensio-reload/ s3://exensio-reload-dev/ --recursive
aws cloudfront create-invalidation --distribution-id DEV_DIST_ID --paths "/*"
```

- [ ] Build successful
- [ ] Artifacts uploaded to CDN
- [ ] Cache invalidation successful
- [ ] Frontend loads without errors
- [ ] Device filter component visible
- [ ] All pages functional

**Environment: STAGING**

```bash
# Build and deploy to staging
npm run build:prod
aws s3 cp dist/exensio-reload/ s3://exensio-reload-staging/ --recursive
aws cloudfront create-invalidation --distribution-id STAGING_DIST_ID --paths "/*"
```

- [ ] Build successful
- [ ] Artifacts uploaded to CDN
- [ ] Cache invalidation successful
- [ ] Frontend loads correctly
- [ ] Device filter functional
- [ ] All pages work correctly
- [ ] No console errors

**Environment: PRODUCTION**

```bash
# Build and deploy to production (during maintenance window)
npm run build:prod
aws s3 cp dist/exensio-reload/ s3://exensio-reload-prod/ --recursive
aws cloudfront create-invalidation --distribution-id PROD_DIST_ID --paths "/*"
```

- [ ] Build successful
- [ ] Artifacts uploaded to production CDN
- [ ] Cache invalidation successful
- [ ] Frontend loads correctly
- [ ] Device filter component visible
- [ ] All pages load and function
- [ ] No console errors in browser
- [ ] No 404 errors for assets
- [ ] Performance metrics normal (Lighthouse/WebVitals)

### Post-Deployment Frontend Verification

- [ ] Application loads without errors
- [ ] Device filter dropdown populated
- [ ] Analytics page filters work
- [ ] My Sessions page filters work
- [ ] Dashboard filters work
- [ ] Discovery preview shows device column
- [ ] Real-time updates work (SSE)
- [ ] No network errors in developer console

---

## API Verification

### Backend API Tests

```bash
# Test device filter endpoints with backend running
# 1. Get distinct devices
curl -X GET http://localhost:8080/api/sessions/devices \
  -H "Authorization: Bearer $TOKEN" | jq .

# 2. Filter sessions by device
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&limit=5" \
  -H "Authorization: Bearer $TOKEN" | jq '.items[0]'

# 3. Filter analytics by device
curl -X GET "http://localhost:8080/api/analytics/summary?devices=IR71939&startDate=2026-01-01&endDate=2026-12-31" \
  -H "Authorization: Bearer $TOKEN" | jq '.metrics.totalPayloads'

# 4. Get dashboard metrics with device filter
curl -X GET "http://localhost:8080/api/dashboard/metrics?devices=IR71939" \
  -H "Authorization: Bearer $TOKEN" | jq '.summary'

# 5. Test backward compatibility (no device param)
curl -X GET "http://localhost:8080/api/sessions?limit=5" \
  -H "Authorization: Bearer $TOKEN" | jq '.items | length'
```

- [ ] All endpoints responding (200 status)
- [ ] Response format correct
- [ ] Device filter parameter accepted
- [ ] Backward compatibility verified
- [ ] No error responses

---

## Smoke Tests

### Critical Path Testing

```
Discovery Preview
  ↓
Select Device
  ↓
Stage Payloads
  ↓
View in My Sessions
  ↓
Filter by Device
  ↓
View in Analytics
  ↓
Apply Device Filter
  ↓
View in Dashboard
  ↓
Apply Device Filter to Real-Time Metrics
```

- [ ] Discovery preview shows devices ✓
- [ ] Can select device and stage ✓
- [ ] Sessions stored with device ✓
- [ ] My Sessions shows device ✓
- [ ] My Sessions device filter works ✓
- [ ] Analytics loads with device ✓
- [ ] Analytics device filter works ✓
- [ ] Dashboard loads with devices ✓
- [ ] Dashboard device filter works ✓
- [ ] Real-time updates respect filter ✓

---

## Performance Verification

### Query Performance Tests

```bash
# Test with production-like data volumes
# 1. Distinct devices query
time curl -s "http://localhost:8080/api/sessions/devices" \
  -H "Authorization: Bearer $TOKEN" | wc -c

# 2. Sessions with device filter
time curl -s "http://localhost:8080/api/sessions?devices=IR71939&limit=100" \
  -H "Authorization: Bearer $TOKEN" | wc -c

# 3. Analytics with device filter
time curl -s "http://localhost:8080/api/analytics/summary?devices=IR71939" \
  -H "Authorization: Bearer $TOKEN" | wc -c

# 4. Dashboard metrics with filter
time curl -s "http://localhost:8080/api/dashboard/metrics?devices=IR71939" \
  -H "Authorization: Bearer $TOKEN" | wc -c
```

**Performance Targets:**

- [ ] Distinct devices: <1 second
- [ ] Sessions with filter: <2 seconds
- [ ] Analytics with filter: <500ms
- [ ] Dashboard metrics: <1 second

### Database Performance

- [ ] Device index being used (EXPLAIN ANALYZE)
- [ ] No full table scans for device queries
- [ ] Query plans optimized
- [ ] Query response times acceptable

---

## Monitoring Setup

### Application Monitoring

- [ ] Device filter metrics being tracked
- [ ] API endpoint latency monitored
- [ ] Device filter error rates monitored
- [ ] Database query performance monitored
- [ ] Error logs configured for device-related issues

### Alerts Configured

- [ ] Alert for high device filter query latency (>2s)
- [ ] Alert for device filter errors (5xx responses)
- [ ] Alert for database index corruption
- [ ] Alert for NULL device handling errors

### Logging

- [ ] Device filter operations logged
- [ ] API requests logged with device parameter
- [ ] Database queries logged (audit trail)
- [ ] Errors logged with context

---

## Rollback Plan

### Rollback Triggers

- [ ] Critical functionality broken
- [ ] Performance degradation (>50%)
- [ ] Data corruption detected
- [ ] Security vulnerability discovered
- [ ] High error rate (>1%)

### Rollback Steps

**1. Frontend Rollback (fastest)**

```bash
# Switch CDN back to previous version
aws cloudfront create-invalidation --distribution-id PROD_DIST_ID --paths "/*"
# Previous frontend artifacts pre-uploaded

# Estimated time: 5-10 minutes
```

**2. Backend Rollback**

```bash
# Switch back to blue environment (if using blue-green)
kubectl patch service exensio-reload -p '{"spec":{"selector":{"version":"blue"}}}'

# Or redeploy previous version
docker pull prod-registry/exensio-reload:0.9.0
kubectl set image deployment/exensio-reload exensio-reload=prod-registry/exensio-reload:0.9.0

# Estimated time: 10-15 minutes
```

**3. Database Rollback**

```bash
# Liquibase rollback (remove device column)
mvn liquibase:rollback -Dliquibase.rollbackCount=1

# Verify schema reverted
mysql -u prod_user -p < verify_migration.sql

# Estimated time: 5-10 minutes
```

### Rollback Verification

- [ ] Frontend loads previous version
- [ ] Backend running previous version
- [ ] Database schema reverted
- [ ] All APIs responding normally
- [ ] No errors in application logs
- [ ] Users can access system normally
- [ ] Post-incident analysis scheduled

---

## Post-Deployment Handoff

### Operations Handoff

- [ ] Deployment procedures documented: DEVICE_FILTER_TESTING_GUIDE.md
- [ ] Rollback procedures documented: (this checklist)
- [ ] Monitoring dashboards set up
- [ ] Alerts configured and tested
- [ ] Runbook for common issues prepared
- [ ] On-call team briefed
- [ ] Support team briefed

### Documentation Handoff

- [ ] API documentation provided: API_DEVICE_FILTER_DOCUMENTATION.md
- [ ] Admin guide prepared: DEVICE_FILTER_DEPLOYMENT_CHECKLIST.md
- [ ] User guide/FAQ prepared
- [ ] Architecture documentation updated
- [ ] System diagram updated
- [ ] Changelog updated

### Sign-Off and Release

- [ ] All checklist items completed ✓
- [ ] Release notes prepared
- [ ] Release to production authorized
- [ ] Deployment window scheduled: Date: ****\_\_**** Time: ****\_\_****
- [ ] Maintenance window communicated
- [ ] Post-deployment support plan ready

---

## Deployment Day Timeline

### Pre-Deployment (T-2 hours)

- [ ] Final verification of all artifacts
- [ ] Team standup completed
- [ ] Database backup completed
- [ ] Maintenance window begins
- [ ] Traffic routed away from primary (if applicable)

### Deployment (T-1 to T+30 min)

- [ ] Database migration executed: **:**
- [ ] Backend deployed: **:**
- [ ] Frontend deployed: **:**
- [ ] Health checks passed: **:**
- [ ] Smoke tests executed: **:**
- [ ] Monitoring verified: **:**

### Post-Deployment (T+30 to T+2 hours)

- [ ] Monitor logs for errors
- [ ] Monitor performance metrics
- [ ] Respond to any issues
- [ ] Gradual traffic increase to new version
- [ ] Final verification complete
- [ ] Release communication sent

### End of Deployment

- [ ] Maintenance window ends
- [ ] All systems operational
- [ ] Post-incident review scheduled
- [ ] Deployment status reported: **SUCCESS** / **FAILURE**

---

## Sign-Off

**Deployment Engineer:** ************\_************ **Date:** ****\_****

**Deployment Manager:** ************\_************ **Date:** ****\_****

**Operations Lead:** ************\_************ **Date:** ****\_****

**Quality Assurance:** ************\_************ **Date:** ****\_****

---

## Deployment Results

### Status: **[ ] SUCCESS** / **[ ] PARTIAL SUCCESS** / **[ ] ROLLED BACK**

### Issues Encountered

```
[Describe any issues and resolution]
```

### Performance Impact

```
Query latency before: __________ after: __________
API response time before: __________ after: __________
Database load before: __________ after: __________
```

### Post-Deployment Notes

```
[Additional notes and observations]
```

---

_End of Deployment Checklist_
