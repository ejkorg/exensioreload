# SSO Deployment Checklist - Basic SSO Implementation

**Date:** [Current Date]  
**Phase:** Phase 1 - Basic SSO Testing  
**Status:** Ready for Deployment

---

## Summary of Changes

### ✅ Change Made

**File:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/OAuth2ClientConfig.java`

**Change:**

```java
// BEFORE (with GroupMember.Read.All):
.scope("openid", "profile", "email", "GroupMember.Read.All")

// AFTER (Basic SSO only):
.scope("openid", "profile", "email")
```

**Reason:** Simplified implementation for Phase 1 testing. Admin roles managed locally in database, not via Entra ID groups.

---

## Pre-Deployment Checklist

### Code Changes

- [x] GroupMember.Read.All removed from OAuth2ClientConfig.java
- [x] OAuth scopes now: openid, profile, email ONLY
- [x] No Graph API calls for group membership
- [x] Configuration validated

### Configuration Verification

- [ ] Application ID (Client ID): `6e0e3995-263c-4511-a5fb-8b3db9ce4ed2`
- [ ] Tenant ID: `04e1674b-7af5-4d13-a082-64fc6e42384c`
- [ ] Client Secret stored in environment variables (NOT in code)
- [ ] Redirect URIs configured in Entra ID

### Database Setup

- [ ] admin_users table created
- [ ] Initial admin users inserted
- [ ] Database connection verified

### Build & Test

- [ ] Application builds successfully
- [ ] No compilation errors
- [ ] Application starts without errors
- [ ] No "GroupMember.Read.All" in logs

---

## Deployment Steps

### Step 1: Build the Application

```bash
# Navigate to project directory
cd backend

# Clean previous build
mvn clean

# Build with basic SSO configuration
mvn clean package -DskipTests

# Verify build success
# Should see: BUILD SUCCESS
```

### Step 2: Deploy to Staging

```bash
# Copy artifact to staging environment
docker build -t exensio-reload:latest .

# Run container with environment variables
docker run -e AZURE_TENANT_ID=04e1674b-7af5-4d13-a082-64fc6e42384c \
           -e AZURE_CLIENT_ID=6e0e3995-263c-4511-a5fb-8b3db9ce4ed2 \
           -e AZURE_CLIENT_SECRET=${AZURE_CLIENT_SECRET} \
           -p 8080:8080 \
           exensio-reload:latest

# Or using docker-compose
docker-compose up -d exensio-reload
```

### Step 3: Test Login Flow

#### Test Case 1: Basic SSO Login

**Precondition:** Application running at `http://localhost:8080`

**Steps:**

1. Navigate to application login page
2. Click "Sign in with Microsoft"
3. User should be redirected to Entra ID login
4. No "Approval required" dialog should appear
5. User enters Microsoft credentials
6. User should be redirected back to application
7. User should be logged in successfully

**Expected Result:**

- ✅ No approval dialog
- ✅ User successfully authenticated
- ✅ User session created
- ✅ Dashboard displayed

**Logs to Check:**

```
✅ "OAuth2 Login successful"
✅ "User authenticated: user@onsemi.com"
✅ "Session created for user@onsemi.com"

❌ Should NOT see:
"GroupMember.Read.All"
"Approval required"
"Microsoft Graph API error"
```

---

#### Test Case 2: Admin User Login

**Precondition:**

- Admin user exists in admin_users table
- Email: eric.alfanta@onsemi.com
- Role: ADMIN

**Steps:**

1. Login as admin user
2. Navigate to admin features (if any)
3. Verify admin permissions are granted

**Expected Result:**

- ✅ Admin user logged in
- ✅ Admin role assigned from local database
- ✅ Admin features accessible
- ✅ Log shows: "Role assigned: ADMIN"

---

#### Test Case 3: Regular User Login

**Precondition:**

- User is NOT in admin_users table
- Email: test.user@onsemi.com

**Steps:**

1. Login as regular user
2. Attempt to access admin features
3. Verify access denied for admin-only features

**Expected Result:**

- ✅ Regular user logged in
- ✅ User role assigned (default)
- ✅ Admin features NOT accessible
- ✅ Log shows: "Role assigned: USER"

---

#### Test Case 4: Logout

**Steps:**

1. Click logout button
2. User should be logged out
3. Session should be destroyed
4. User should be redirected to login page

**Expected Result:**

- ✅ User session destroyed
- ✅ Redirected to login page
- ✅ Cannot access protected resources

---

### Step 4: Verify Logs

Check application logs for SSO activity:

```bash
# Tail logs
tail -f logs/application.log

# Look for successful SSO entries
grep "OAuth2 Login" logs/application.log
grep "User authenticated" logs/application.log
grep "Role assigned" logs/application.log

# Verify NO errors
grep "GroupMember.Read.All" logs/application.log  # Should return nothing
grep "Approval required" logs/application.log     # Should return nothing
grep "Microsoft Graph" logs/application.log       # Should return nothing
```

---

### Step 5: Database Verification

```sql
-- Verify admin users table
SELECT * FROM admin_users;

-- Expected output:
-- +-----+-----------------------------------+-----------+
-- | id  | email                             | role      |
-- +-----+-----------------------------------+-----------+
-- | 1   | junifferallan.garcia@onsemi.com   | SUPERADMIN|
-- | 2   | eric.alfanta@onsemi.com           | ADMIN     |
-- | 3   | jovenk.sorallo@onsemi.com         | ADMIN     |
-- +-----+-----------------------------------+-----------+
```

---

## Testing Results

### ✅ Successful Indicators

- [ ] Login page loads without errors
- [ ] "Sign in with Microsoft" button works
- [ ] Entra ID login redirect works
- [ ] **NO "Approval required" dialog appears**
- [ ] User redirected back to application
- [ ] User successfully authenticated
- [ ] User session created
- [ ] Admin users can access admin features
- [ ] Regular users cannot access admin features
- [ ] Logout works correctly
- [ ] Logs show successful authentication (no errors)

### ❌ Failed Indicators

If you see any of these, there's an issue:

- [ ] "Approval required" dialog appears → GroupMember.Read.All still in scope
- [ ] Login redirects to error page → Configuration issue (Client ID, Tenant ID)
- [ ] "Invalid client secret" error → Client secret incorrect or expired
- [ ] "Microsoft Graph API error" → Still trying to call Graph API
- [ ] User stuck on Entra ID login → Redirect URI mismatch
- [ ] Session not created → Authentication service issue
- [ ] All users get ADMIN role → Database query issue

---

## Troubleshooting

### Issue 1: "Approval required" Dialog Still Appears

**Cause:** GroupMember.Read.All not removed or old version deployed

**Solution:**

```bash
# Verify config was changed
grep -n "GroupMember" backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/OAuth2ClientConfig.java
# Should return: nothing

# Rebuild and redeploy
mvn clean package -DskipTests
docker-compose restart exensio-reload

# Clear browser cache
# Try login again
```

---

### Issue 2: "Invalid client secret"

**Cause:** Client secret incorrect or expired

**Solution:**

```bash
# Verify environment variable is set
echo $AZURE_CLIENT_SECRET

# Should show: .LJ8Q~94VRAnn3tgCcAS8e2K-aPiOU3nG.fYva7

# If not set, export it
export AZURE_CLIENT_SECRET=.LJ8Q~94VRAnn3tgCcAS8e2K-aPiOU3nG.fYva7

# Restart application
docker-compose restart exensio-reload
```

---

### Issue 3: Users Get "USER" Role When Should Be Admin

**Cause:** Admin email not in database or email case mismatch

**Solution:**

```sql
-- Check database
SELECT * FROM admin_users WHERE LOWER(email) = LOWER('eric.alfanta@onsemi.com');

-- If not found, insert
INSERT INTO admin_users (email, role, updated_by)
VALUES ('eric.alfanta@onsemi.com', 'ADMIN', 'migration');

-- Verify
SELECT * FROM admin_users;
```

---

### Issue 4: Application Won't Start

**Cause:** Configuration missing or incorrect

**Solution:**

```bash
# Check environment variables
env | grep AZURE

# Should show:
# AZURE_TENANT_ID=04e1674b-7af5-4d13-a082-64fc6e42384c
# AZURE_CLIENT_ID=6e0e3995-263c-4511-a5fb-8b3db9ce4ed2
# AZURE_CLIENT_SECRET=...

# Check logs
docker logs exensio-reload

# Look for configuration errors
```

---

## Deployment Sign-Off

### Development Team

- [ ] Code review completed
- [ ] Changes verified in repository
- [ ] Build successful
- [ ] Staging deployment successful
- [ ] All test cases passed
- [ ] Logs verified (no errors)
- [ ] Ready for production deployment

**Tested By:** ********\_******** **Date:** **\_\_\_**

---

### Operations Team

- [ ] Application deployed to production
- [ ] Health check passed
- [ ] Logs monitored
- [ ] No error alerts
- [ ] Ready for user access

**Deployed By:** ********\_******** **Date:** **\_\_\_**

---

## Post-Deployment Monitoring

### Monitor These Metrics

```
- Login success rate (target: >99%)
- Login failure rate (target: <1%)
- Average login time (target: <5 seconds)
- Error rate (target: 0%)
- Application availability (target: 100%)
```

### Check Logs Daily

```bash
# Daily health check
tail -100 logs/application.log | grep -E "ERROR|WARN|OAuth2"

# Weekly summary
grep "User authenticated" logs/application.log | wc -l
# Shows: Total successful logins this week
```

### Alert Thresholds

- ⚠️ Alert if: Login failure rate > 5%
- ⚠️ Alert if: "Approval required" appears in logs
- ⚠️ Alert if: Microsoft Graph errors appear
- ⚠️ Alert if: Application errors increase

---

## Rollback Plan

If critical issues occur after production deployment:

### Rollback Steps

1. **Immediate (if approval dialog appears):**

   ```bash
   # This should NOT happen - but if it does:
   git revert <commit-hash>
   mvn clean package -DskipTests
   docker-compose restart exensio-reload
   ```

2. **If authentication fails:**

   ```bash
   # Verify environment variables
   docker inspect exensio-reload | grep AZURE

   # Restart with correct secrets
   docker-compose restart exensio-reload
   ```

3. **Full rollback to previous version:**
   ```bash
   docker-compose down
   docker pull exensio-reload:previous
   docker-compose up -d
   ```

---

## Phase 2 Planning (Future)

### When to Consider Phase 2

- After 2-4 weeks of Phase 1 stability
- If organization requires centralized admin management
- If admin roster becomes large (>10 admins)
- If need for audit trail of role changes

### Phase 2 Requirements (To Be Determined Later)

- [ ] GroupMember.Read.All permission approval
- [ ] Entra ID security groups creation
- [ ] Groups claim configuration
- [ ] Group-based role assignment implementation
- [ ] Migration plan from local to group-based

### Phase 2 Timeline

- Week 1-2: Phase 1 stability monitoring
- Week 3-4: Evaluate if Phase 2 needed
- If yes: Schedule Phase 2 for week 5-6

---

## Communication

### Notify Users After Deployment

**Subject:** Exensio Reload SSO Deployment Complete

**Message:**

```
Hello Team,

We're excited to announce that Exensio Reload now supports Single Sign-On (SSO)
authentication using your Microsoft corporate account.

Starting today, you can:
✅ Sign in using your @onsemi.com email address
✅ No need to remember application-specific passwords
✅ Faster and more secure authentication

How to Login:
1. Visit: https://exensio-reload.onsemi.com
2. Click "Sign in with Microsoft"
3. Enter your @onsemi.com credentials
4. You're logged in!

No action required from you - just start using SSO.

If you experience any issues, please contact: [Support Email]

Thank you,
Development Team
```

---

## Next Steps

1. ✅ Code changes completed (GroupMember.Read.All removed)
2. → Build application
3. → Deploy to staging
4. → Execute test cases
5. → Deploy to production
6. → Monitor for 24 hours
7. → Notify users
8. → Plan Phase 2 (if needed in future)

---

**Document Version:** 1.0  
**Last Updated:** [Current Date]  
**Status:** Ready for Deployment
