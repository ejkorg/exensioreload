# SSO Deployment Checklist - Phase 1 (BACKUP/ARCHIVE)

**Status:** ARCHIVED - Reference for Phase 1 Implementation  
**Phase:** Phase 1 - Basic SSO Testing (Local Admin Management)  
**Date Created:** [Current Date]  
**Superseded By:** SSO_DEPLOYMENT_CHECKLIST.md (current version)

---

## ⚠️ IMPORTANT

This is a **BACKUP COPY** of the Phase 1 deployment checklist with local admin database management.

**Current Phase:** Phase 1 (Basic SSO, Local Admin DB)  
**Future Phase:** Phase 2 (Entra ID Groups-based Admin Management)

This document is archived for reference when implementing Phase 2 enhancements.

---

## Summary of Phase 1 Changes

### ✅ Change Made

**File:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/OAuth2ClientConfig.java`

**Change:**

```java
// BEFORE (with GroupMember.Read.All):
.scope("openid", "profile", "email", "GroupMember.Read.All")

// AFTER (Phase 1 - Basic SSO only):
.scope("openid", "profile", "email")
```

**Reason:** Simplified implementation for Phase 1 testing. Admin roles managed locally in database, not via Entra ID groups.

---

## Configuration Details (Phase 1)

### OAuth2 Scopes

```
✅ openid     - OIDC authentication
✅ profile    - User profile info
✅ email      - User email
❌ GroupMember.Read.All - NOT requested (Phase 1)
❌ Groups claim on token - NOT enabled (Phase 1)
```

### Admin Role Management

```
✅ Local database (users, user_roles tables)
✅ Manual assignment via SQL or Admin UI
❌ Entra ID security groups (Phase 2 feature)
❌ Automatic sync from Entra ID (Phase 2 feature)
```

### Database Setup (Phase 1)

```sql
-- Users table
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN DEFAULT true,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- User roles (separate table for role assignment)
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE KEY unique_user_role (user_id, role)
);

-- New SSO users get ROLE_USER by default
-- Admins manually assigned: ROLE_ADMIN
-- Superadmins manually assigned: ROLE_SUPER_ADMIN
```

---

## Pre-Deployment Checklist (Phase 1)

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

- [ ] users table created
- [ ] user_roles table created
- [ ] Initial admin users inserted
- [ ] Database connection verified

### Build & Test

- [ ] Application builds successfully
- [ ] No compilation errors
- [ ] Application starts without errors
- [ ] No "GroupMember.Read.All" in logs

---

## Deployment Steps (Phase 1)

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

### Step 3: Test Login Flow (Phase 1)

#### Test Case 1: Basic SSO Login

**Precondition:** Application running

**Steps:**

1. Navigate to application login page
2. Click "Sign in with Microsoft"
3. **CRITICAL:** Verify NO "Approval required" dialog appears
4. User enters Microsoft credentials
5. User redirected back to application
6. User logged in successfully

**Expected Result:**

- ✅ No approval dialog
- ✅ User authenticated
- ✅ User session created
- ✅ Dashboard displayed
- ✅ Default role: USER (from database)

---

#### Test Case 2: Admin User Login (After Manual Role Assignment)

**Setup:**

1. New user logs in (role = USER assigned automatically)
2. Admin manually adds ROLE_ADMIN to user_roles table
3. User logs out and logs back in

```sql
-- Step 1: Find user ID
SELECT id FROM users WHERE email = 'test.user@onsemi.com';
-- Result: 1

-- Step 2: Add admin role
INSERT INTO user_roles (user_id, role) VALUES (1, 'ROLE_ADMIN');

-- Step 3: User logs in again with new role
```

**Steps:**

1. User logs in again
2. Verify admin role is loaded from database
3. Verify admin features are accessible

**Expected Result:**

- ✅ Admin role loaded from database
- ✅ Admin permissions granted
- ✅ Admin features accessible
- ✅ Log shows: "Role assigned: ADMIN"

---

#### Test Case 3: Regular User (No Admin)

**Steps:**

1. User logs in (no admin role assigned)
2. Attempt to access admin features
3. Verify access denied

**Expected Result:**

- ✅ User logged in as USER
- ✅ Admin features NOT accessible
- ✅ Access denied returned

---

#### Test Case 4: Logout

**Steps:**

1. Click logout button
2. Verify session destroyed
3. Verify cannot access protected resources

**Expected Result:**

- ✅ Session destroyed
- ✅ Redirected to login
- ✅ Cannot access protected resources

---

## Verification Logs (Phase 1)

```bash
# Check for successful SSO entries
grep "OAuth2 Login successful" logs/application.log
grep "User authenticated" logs/application.log
grep "Role assigned" logs/application.log

# Verify NO errors
grep "GroupMember.Read.All" logs/application.log  # Should return nothing
grep "Approval required" logs/application.log     # Should return nothing
grep "Microsoft Graph" logs/application.log       # Should return nothing
```

---

## Assigning Admin Roles (Phase 1 Process)

### Via SQL (Direct Database Update)

```sql
-- Find new SSO user
SELECT u.id, u.username, u.email
FROM users u
LEFT JOIN user_roles ur ON u.id = ur.user_id
WHERE u.email = 'eric.alfanta@onsemi.com';

-- Assign ADMIN role
INSERT INTO user_roles (user_id, role) VALUES (
    (SELECT id FROM users WHERE email = 'eric.alfanta@onsemi.com'),
    'ROLE_ADMIN'
);

-- Verify
SELECT r.role
FROM user_roles r
JOIN users u ON r.user_id = u.id
WHERE u.email = 'eric.alfanta@onsemi.com';
-- Should show: ROLE_USER, ROLE_ADMIN
```

### Via Application Admin UI (If Available)

```
1. Admin logs in
2. Navigate to User Management
3. Find user by email
4. Assign role: ADMIN or SUPER_ADMIN
5. Save
6. User has new role on next login
```

---

## Testing Results (Phase 1)

### ✅ Success Indicators

- [ ] Login page loads without errors
- [ ] "Sign in with Microsoft" button works
- [ ] Entra ID redirect works
- [ ] **NO "Approval required" dialog**
- [ ] User authenticated successfully
- [ ] User session created
- [ ] Default role: USER (from database)
- [ ] Admin role works after manual assignment
- [ ] Logout works correctly
- [ ] Logs show successful auth (no errors)

### ❌ Failure Indicators

- [ ] "Approval required" dialog appears → GroupMember.Read.All still in scope
- [ ] Login fails → Configuration issue
- [ ] All users get ADMIN role → Database query issue
- [ ] "Microsoft Graph API error" → Still calling Graph API

---

## Troubleshooting (Phase 1)

### Issue 1: "Approval required" Dialog Appears

**Solution:** GroupMember.Read.All not removed properly

```bash
# Verify config
grep "GroupMember" OAuth2ClientConfig.java
# Should return: nothing

# Rebuild and redeploy
mvn clean package -DskipTests
docker-compose restart exensio-reload
```

### Issue 2: Users Get Wrong Role

**Solution:** Database role assignment issue

```sql
-- Check user roles
SELECT * FROM user_roles WHERE user_id = (
    SELECT id FROM users WHERE email = 'user@onsemi.com'
);

-- Add missing role
INSERT INTO user_roles (user_id, role) VALUES (?, 'ROLE_ADMIN');
```

---

## Phase 1 vs Phase 2 Comparison

| Feature                 | Phase 1  | Phase 2         |
| ----------------------- | -------- | --------------- |
| SSO Authentication      | ✅ Yes   | ✅ Yes          |
| Admin Roles             | Local DB | Entra ID Groups |
| GroupMember.Read.All    | ❌ No    | ✅ Yes          |
| Approval Required       | ❌ No    | ✅ Yes          |
| Manual Admin Assignment | ✅ Yes   | ❌ No           |
| Automatic Role Sync     | ❌ No    | ✅ Yes          |
| Groups Claim on Token   | ❌ No    | ✅ Yes          |

---

## Notes for Phase 2 Transition

When implementing Phase 2 (Entra ID Groups):

1. **Create Entra ID Security Groups:**
   - onsemi-exensioreload-superadmins
   - onsemi-exensioreload-admins

2. **Request Admin Approval:**
   - GroupMember.Read.All permission

3. **Enable Groups Claim:**
   - On ID token

4. **Update Application:**
   - Read groups from token
   - Map groups to roles
   - Remove local admin database logic (or keep as fallback)

5. **Migration Plan:**
   - Map existing local admins to Entra ID groups
   - Test in staging
   - Deploy Phase 2
   - Monitor role sync
   - Eventually retire local admin table

---

## Rollback Plan (Phase 1)

If critical issues occur:

```bash
# Quick rollback to previous version
git revert <commit-hash>
mvn clean package -DskipTests
docker-compose restart exensio-reload
```

---

## Communication Template

**Subject:** Exensio Reload SSO Phase 1 Deployment

```
Hello Team,

Exensio Reload now supports Single Sign-On (SSO) authentication
using your Microsoft corporate account.

🔓 How to Login:
1. Visit: https://exensio-reload.onsemi.com
2. Click "Sign in with Microsoft"
3. Enter your @onsemi.com credentials
4. You're logged in!

👥 Admin Access:
If you need admin access, contact: [Your Admin Contact]

❓ Having Issues?
Contact: [Support Email]

Thank you,
Development Team
```

---

## Archived Configuration Reference

### OAuth2ClientConfig.java (Phase 1)

```java
.scope("openid", "profile", "email")  // NO GroupMember.Read.All
.redirectUri("{baseUrl}/login/oauth2/code/onsemi")
```

### Database Schema (Phase 1)

```
users table - User accounts
user_roles table - Role assignments
ROLE_USER - Default for new SSO users
ROLE_ADMIN - Manually assigned
ROLE_SUPER_ADMIN - Manually assigned
```

### RoleService (Phase 1)

```java
roleService.isAdmin()  // Checks ROLE_ADMIN or ROLE_SUPER_ADMIN
roleService.isSuperAdmin()  // Checks ROLE_SUPER_ADMIN
```

---

**Document Status:** ARCHIVED  
**Version:** 1.0  
**Archive Date:** [Current Date]  
**Next Phase:** SSO Phase 2 with Entra ID Groups (TBD)
