# SSO Implementation Guide - Phased Approach

## Overview

This document explains the phased approach to implementing Single Sign-On (SSO) for the Exensio Reload application, addressing the "Approval required" dialog that appears during login and clarifying why certain API permissions are needed at different phases.

---

## Table of Contents

1. [Quick Summary](#quick-summary)
2. [Phase 1: Basic SSO (Current)](#phase-1-basic-sso-current)
3. [Phase 2: Centralized RBAC (Future)](#phase-2-centralized-rbac-future)
4. [Why the Approval Dialog Appears](#why-the-approval-dialog-appears)
5. [Immediate Actions Required](#immediate-actions-required)
6. [Comparison Matrix](#comparison-matrix)
7. [Implementation Timeline](#implementation-timeline)
8. [Troubleshooting](#troubleshooting)

---

## Quick Summary

### The Problem You're Seeing

When users try to log in to Exensio Reload via SSO, they see:

```
Approval required

This app requires your admin's approval to:
✓ View users' basic profile
✓ Read group memberships
✓ Maintain access to data you have given it access to
```

### Why This Happens

Your application is currently configured to request `GroupMember.Read.All` permission, which requires admin approval. However, **this permission is not needed for Phase 1 (basic SSO with local admin management)**.

### The Solution

Remove `GroupMember.Read.All` from your application's OAuth configuration immediately to allow users to log in without approval delays. This permission will be re-added in Phase 2 when you're ready to use Entra ID security groups.

---

## Phase 1: Basic SSO (Current)

### Objectives

- ✅ Enable users to log in via Microsoft SSO (Entra ID)
- ✅ Manage admin/superadmin roles in local application database
- ✅ Provide full functionality without waiting for Entra ID group setup
- ✅ Deploy to production quickly (1-2 weeks)

### Configuration

**Required OAuth Scopes:**

```
openid
profile
email
```

**NOT Required:**

```
GroupMember.Read.All (remove this)
https://graph.microsoft.com/.default (remove this)
```

### Authorization Flow

```
User Login
    ↓
Entra ID Authenticates User
    ↓
App Receives: {
  "email": "user@onsemi.com",
  "name": "John Doe",
  "id": "xxx-xxx-xxx"
}
    ↓
App Queries LOCAL Database
    ↓
Does user exist in admin_users table? YES/NO
    ↓
Grant Role (Superadmin/Admin/User)
    ↓
Allow/Deny Access
```

### What Works

| Feature                         | Status |
| ------------------------------- | ------ |
| User authentication via SSO     | ✅     |
| User profile display            | ✅     |
| Local admin assignment          | ✅     |
| Role-based access (local DB)    | ✅     |
| Basic application functionality | ✅     |

### What Doesn't Work

| Feature                          | Status |
| -------------------------------- | ------ |
| Reading Entra ID security groups | ❌     |
| Centralized role management      | ❌     |
| Automatic role sync from AD      | ❌     |
| Self-service group management    | ❌     |

### Database Schema (Phase 1)

```sql
CREATE TABLE admin_users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) UNIQUE NOT NULL,
    role VARCHAR(50) NOT NULL, -- 'SUPERADMIN' or 'ADMIN'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Example data
INSERT INTO admin_users (email, role, updated_by) VALUES
  ('junifferallan.garcia@onsemi.com', 'SUPERADMIN', 'system'),
  ('eric.alfanta@onsemi.com', 'ADMIN', 'system'),
  ('jovenk.sorallo@onsemi.com', 'ADMIN', 'system');
```

### Role Assignment Logic (Phase 1)

```java
// In your authentication service
public String getUserRole(String userEmail) {
    Optional<AdminUser> admin = adminUserRepository.findByEmail(userEmail);

    if (admin.isPresent()) {
        return admin.get().getRole(); // "SUPERADMIN" or "ADMIN"
    }

    return "USER"; // Default role for regular users
}
```

### Advantages of Phase 1

- ✅ **No Admin Approval Needed**: Users can log in immediately
- ✅ **Simple Implementation**: No Microsoft Graph API calls required
- ✅ **Fast Deployment**: Can deploy in 1-2 weeks
- ✅ **Full Control**: App controls who is admin
- ✅ **Local Database**: Easy to update and test
- ✅ **Lower Risk**: Minimal dependencies on external services

### Disadvantages of Phase 1

- ⚠️ **Manual Management**: Must update database to add/remove admins
- ⚠️ **Sync Issues**: Database can get out of sync with actual admin roster
- ⚠️ **No Audit Trail**: Only your app knows who admins are
- ⚠️ **Maintenance Burden**: Risk of forgetting to remove admin access when someone leaves
- ⚠️ **Scaling Issue**: Doesn't scale if you have multiple applications
- ⚠️ **Compliance Risk**: Security teams have limited visibility

---

## Phase 2: Centralized RBAC (Future)

### When to Implement

After Phase 1 is stable and:

- Entra ID security groups are created
- Admin approves `GroupMember.Read.All` permission
- Infrastructure for group synchronization is ready

**Timeline:** Weeks 3-4 (approximately)

### Objectives

- ✅ Manage admin/superadmin roles in Entra ID security groups
- ✅ Eliminate manual database updates
- ✅ Provide centralized, auditable role management
- ✅ Implement industry-standard enterprise SSO

### Configuration

**Required OAuth Scopes:**

```
openid
profile
email
https://graph.microsoft.com/.default
```

**Additional Admin Consent Required:**

```
GroupMember.Read.All (Application-type permission)
```

### Required Entra ID Security Groups

#### **onsemi-exensioreload-superadmins**

- **Description**: Full system access, configuration, user management
- **Members**:
  - junifferallan.garcia@onsemi.com
- **Managed By**: Entra ID admin
- **Purpose**: System-wide administrative access

#### **onsemi-exensioreload-admins**

- **Description**: Administrative operations, monitoring, bulk actions
- **Members**:
  - junifferallan.garcia@onsemi.com
  - eric.alfanta@onsemi.com
  - jovenk.sorallo@onsemi.com
  - glorymaae.llego@onsemi.com
  - gilbert.miole@onsemi.com
- **Managed By**: Entra ID admin
- **Purpose**: Operational and monitoring access

### Authorization Flow

```
User Login
    ↓
Entra ID Authenticates User
    ↓
App Receives: {
  "email": "user@onsemi.com",
  "name": "John Doe",
  "id": "xxx-xxx-xxx",
  "groups": [
    "onsemi-exensioreload-admins"
  ]
}
    ↓
App Checks Group Membership in Token
    ↓
Is user in onsemi-exensioreload-superadmins? YES/NO
Is user in onsemi-exensioreload-admins? YES/NO
    ↓
Grant Role Based on Groups
    ↓
Allow/Deny Access
```

### Role Assignment Logic (Phase 2)

```java
// In your authentication service
public String getUserRole(String userEmail, List<String> groups) {
    // Check groups in token
    if (groups != null) {
        if (groups.contains("onsemi-exensioreload-superadmins")) {
            return "SUPERADMIN";
        }
        if (groups.contains("onsemi-exensioreload-admins")) {
            return "ADMIN";
        }
    }

    return "USER"; // Default role for regular users
}
```

### What Works (Phase 2)

| Feature                                    | Status |
| ------------------------------------------ | ------ |
| User authentication via SSO                | ✅     |
| User profile display                       | ✅     |
| Reading Entra ID security groups           | ✅     |
| Centralized role management                | ✅     |
| Automatic role sync from AD                | ✅     |
| Self-service group management (by AD team) | ✅     |
| Audit trail of role changes                | ✅     |
| Compliance visibility                      | ✅     |

### Advantages of Phase 2

- ✅ **Centralized Management**: Roles managed in Entra ID, not app DB
- ✅ **No Manual Updates**: Group membership is source of truth
- ✅ **Automatic Sync**: Changes take effect on next login
- ✅ **Audit Trail**: All group changes tracked in Entra ID
- ✅ **Compliance Ready**: Security teams can audit role assignments
- ✅ **Scalable**: Works for multiple applications
- ✅ **Industry Standard**: Enterprise best practice

### Disadvantages of Phase 2

- ⚠️ **Admin Approval Needed**: Requires IT/InfoSec approval for `GroupMember.Read.All`
- ⚠️ **Entra ID Setup**: Requires group creation and configuration
- ⚠️ **Token Configuration**: Requires enabling groups claim on ID token
- ⚠️ **Dependency on AD**: Relies on Entra ID infrastructure

---

## Why the Approval Dialog Appears

### The Dialog Message

```
Approval required

ExensioReload
onsemi.com

This app requires your admin's approval to:
✓ View users' basic profile
✓ Read group memberships
✓ Maintain access to data you have given it access to

Enter justification for requesting this app
[Text field for justification]

[Cancel] [Request approval]
```

### Root Cause

Your application is configured to request `GroupMember.Read.All` permission, which is a **privileged permission** that requires explicit admin approval because it allows reading security group membership for **all users in the organization**.

### Why It Appears During Login

When a user logs in:

1. App requests OAuth token with `GroupMember.Read.All` scope
2. Entra ID checks if admin has already approved this permission
3. If NOT approved → Shows "Approval required" dialog
4. User cannot log in until admin approves

### Current Situation

**You're asking for Phase 2 permissions during Phase 1 implementation.**

This is the mismatch:

- ✅ You configured Phase 2 (with `GroupMember.Read.All`)
- ❌ But you're using Phase 1 (local admin DB, no groups)
- ❌ Result: Asking for permission you're not using yet

---

## Immediate Actions Required

### For Development Team

#### Action 1: Locate Configuration Files

Search your codebase for these patterns:

```bash
grep -r "GroupMember.Read.All" .
grep -r "graph.microsoft.com" .
grep -r "oauth.scopes" .
grep -r "authorization.scopes" .
```

**Common Locations:**

- `src/main/resources/application.yml`
- `src/main/resources/application.properties`
- `src/main/java/com/onsemi/.../config/SecurityConfig.java`
- `src/main/java/com/onsemi/.../config/OAuth2Config.java`
- `src/main/java/com/onsemi/.../config/AuthenticationConfig.java`

#### Action 2: Remove GroupMember.Read.All

**In YAML Configuration:**

```yaml
# REMOVE THIS:
spring:
  security:
    oauth2:
      client:
        registration:
          azure:
            scope: openid,profile,email,https://graph.microsoft.com/.default

# REPLACE WITH THIS:
spring:
  security:
    oauth2:
      client:
        registration:
          azure:
            scope: openid,profile,email
```

**In Java Configuration:**

```java
// REMOVE THIS:
@Bean
public OAuth2RestTemplate restTemplate(OAuth2ClientContext clientContext) {
    OAuth2RestTemplate template = new OAuth2RestTemplate(resource(), clientContext);
    template.setRequestFactory(new ClientHttpRequestFactoryImpl());
    return template;
}

// If you have Graph API calls, COMMENT THEM OUT for now:
/*
private void readUserGroups(String token) {
    // TODO: Implement in Phase 2
    // graphClient.me().memberOf().buildRequest().get();
}
*/
```

#### Action 3: Remove Graph API Calls

Search for and comment out or remove:

```java
// Remove these or mark TODO for Phase 2:
graphClient.me().memberOf().buildRequest().get();
graphClient.users(userId).memberOf().buildRequest().get();
microsoft.graph.*
```

#### Action 4: Verify Local Admin Logic

Ensure your authentication service uses local database:

```java
@Service
public class AuthenticationService {

    @Autowired
    private AdminUserRepository adminUserRepository;

    public String getUserRole(String userEmail) {
        // This is correct for Phase 1
        Optional<AdminUser> admin = adminUserRepository.findByEmail(userEmail);
        return admin.isPresent() ? admin.get().getRole() : "USER";
    }
}
```

#### Action 5: Redeploy

1. Commit changes to version control
2. Rebuild application
3. Deploy to staging environment
4. Test login flow

#### Action 6: Verify No Approval Dialog

After deployment:

1. Clear browser cache
2. Try logging in as a test user
3. **Verify**: No "Approval required" dialog appears
4. **Verify**: User successfully logs in
5. **Verify**: Regular user gets "USER" role
6. **Verify**: Admin can log in and see admin features

### For AD/InfoSec Team

#### Action 1: Hold GroupMember.Read.All Approval

Do NOT approve `GroupMember.Read.All` permission yet.

**Timeline**: We will request approval in ~2 weeks when ready for Phase 2.

#### Action 2: Prepare for Phase 2 (Optional, In Parallel)

If you have bandwidth, start planning:

- [ ] Create security groups in Entra ID
- [ ] Plan group membership structure
- [ ] Determine group naming conventions
- [ ] Schedule group creation for Week 3-4

#### Action 3: Track Status

We will notify you when ready for:

- [ ] Approving `GroupMember.Read.All` permission
- [ ] Enabling groups claim on ID token
- [ ] Final Phase 2 configuration

---

## Comparison Matrix

### Phase 1 vs Phase 2

| Aspect                    | Phase 1 (NOW)          | Phase 2 (Later)          |
| ------------------------- | ---------------------- | ------------------------ |
| **Deployment Timeline**   | 1-2 weeks              | Weeks 3-4                |
| **Required Scopes**       | openid, profile, email | + GroupMember.Read.All   |
| **Admin Approval Needed** | ❌ NO                  | ✅ YES                   |
| **Users Can Log In**      | ✅ YES                 | ✅ YES                   |
| **Role Source**           | Local database         | Entra ID groups          |
| **Manual Admin Updates**  | ✅ YES                 | ❌ NO                    |
| **Approval Dialog**       | ❌ NO                  | ✅ YES (first time only) |
| **Sync Automatic**        | ❌ NO                  | ✅ YES                   |
| **Audit Trail**           | Limited                | Full                     |
| **Compliance Ready**      | Partial                | Full                     |
| **API Quota Usage**       | Low                    | Moderate                 |
| **Response Time**         | Fast                   | Fast                     |
| **Database Dependency**   | ✅ YES                 | ⚠️ Reduced               |

---

## Implementation Timeline

### Week 1: Phase 1 Deployment

```
Monday:
  ├─ Identify and remove GroupMember.Read.All from config
  └─ Test locally without Graph API calls

Tuesday-Wednesday:
  ├─ Code review
  ├─ Deploy to staging environment
  └─ Test login flow (no approval dialog)

Thursday-Friday:
  ├─ Fix any issues
  ├─ Deploy to production
  └─ Monitor login success rate
```

### Week 2: Phase 1 Stabilization

```
Monday-Wednesday:
  ├─ Monitor user logins
  ├─ Handle any authentication issues
  └─ Gather feedback from users

Thursday-Friday:
  ├─ Document Phase 1 learnings
  └─ Begin planning Phase 2
```

### Week 3: Phase 2 Preparation (In Parallel with Week 2)

```
AD/InfoSec Team:
  ├─ Create security groups in Entra ID
  ├─ Add members to groups
  └─ Prepare approval of GroupMember.Read.All

Development Team:
  ├─ Implement group-based role checking
  ├─ Configure groups claim on token
  └─ Test with staging Entra ID groups
```

### Week 4: Phase 2 Transition

```
Monday-Tuesday:
  ├─ AD/InfoSec approves GroupMember.Read.All
  ├─ Enable groups claim on ID token
  └─ Configure group IDs in application

Wednesday-Thursday:
  ├─ Deploy Phase 2 to staging
  ├─ Test with real Entra ID groups
  └─ Validate role assignments

Friday:
  ├─ Deploy Phase 2 to production
  ├─ Monitor for issues
  └─ Begin migration of local admins to groups
```

### Week 5+: Phase 2 Stabilization

```
Monday-Wednesday:
  ├─ Monitor group-based authorization
  ├─ Validate automatic role sync
  └─ Handle edge cases

Thursday-Friday:
  ├─ Archive local admin database
  └─ Document Phase 2 completion
```

---

## Troubleshooting

### Problem 1: "Approval required" Dialog Still Appears

#### Symptom

Users see approval dialog even after removing `GroupMember.Read.All`.

#### Causes

1. Configuration change not deployed
2. Browser cache still has old config
3. Old Docker image still running

#### Solution

1. Verify configuration file was changed:

   ```bash
   grep -n "GroupMember\|graph.microsoft" application.yml
   # Should return nothing
   ```

2. Verify deployment succeeded:

   ```bash
   # Check deployed artifact
   docker inspect <image-id> | grep OAUTH_SCOPE
   # Should show only: openid profile email
   ```

3. Clear browser cache:
   - Chrome: Ctrl+Shift+Delete → Clear browsing data
   - Firefox: Ctrl+Shift+Delete → Clear Recent History
   - Edge: Ctrl+Shift+Delete → Clear browsing data

4. Restart application:
   ```bash
   docker-compose restart exensio-app
   ```

### Problem 2: Users Get "USER" Role When They Should Be Admin

#### Symptom

Admin user logs in but gets regular user permissions.

#### Causes

1. Admin email not in database
2. Email case mismatch
3. Database query issue

#### Solution

```sql
-- Check admin users table
SELECT * FROM admin_users WHERE email = 'user@onsemi.com';

-- Should return admin record, if not:
INSERT INTO admin_users (email, role, updated_by)
VALUES ('user@onsemi.com', 'ADMIN', 'migration');

-- Verify case sensitivity
SELECT DISTINCT email FROM admin_users;
SELECT LOWER(?) AS search_email; -- Check case
```

### Problem 3: Login Fails After Configuration Change

#### Symptom

Users cannot log in after removing `GroupMember.Read.All`.

#### Causes

1. Other required scopes were accidentally removed
2. Token endpoint misconfigured
3. Redirect URI mismatch

#### Solution

Verify configuration has exactly these scopes:

```yaml
scope: openid,profile,email
# NOT: openid,profile,email,https://graph.microsoft.com/.default
# NOT: openid (missing profile and email)
```

### Problem 4: Database Not Working for Admin Check

#### Symptom

Login works but admin roles not assigned correctly.

#### Causes

1. `admin_users` table doesn't exist
2. `AdminUserRepository` not autowired
3. Database connection issue

#### Solution

```sql
-- Create table if missing
CREATE TABLE IF NOT EXISTS admin_users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) UNIQUE NOT NULL,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Verify table exists
SHOW TABLES LIKE 'admin_users';

-- Check contents
SELECT * FROM admin_users;
```

### Problem 5: Phase 2 Migration Issues (Later)

When transitioning to Phase 2:

#### Users Can't Find Groups in Token

```java
// Verify groups claim is enabled in Entra ID
// Check token contents at jwt.ms
// Ensure "groups" key exists in decoded token
```

#### Group IDs Don't Match

```java
// Get actual group IDs from Entra ID
// Update application configuration:
ADMIN_GROUP_ID=<actual-uuid>
SUPERADMIN_GROUP_ID=<actual-uuid>
```

---

## Quick Reference

### Phase 1 Checklist

- [ ] Remove `GroupMember.Read.All` from OAuth scopes
- [ ] Remove `https://graph.microsoft.com/.default` from config
- [ ] Comment out all Graph API calls
- [ ] Verify local admin database logic
- [ ] Create/populate `admin_users` table
- [ ] Test login (no approval dialog)
- [ ] Deploy to staging
- [ ] Deploy to production
- [ ] Monitor user logins
- [ ] Document Phase 1 setup

### Phase 2 Checklist (Future)

- [ ] Entra ID security groups created
- [ ] Request admin approval for `GroupMember.Read.All`
- [ ] Receive approval from admin
- [ ] Enable groups claim on ID token
- [ ] Update application to read groups from token
- [ ] Configure group IDs in application
- [ ] Test with real groups in staging
- [ ] Deploy Phase 2 to production
- [ ] Migrate local admins to Entra ID groups
- [ ] Archive local `admin_users` table
- [ ] Document Phase 2 completion

---

## Additional Resources

### Microsoft Documentation

- [OAuth 2.0 and OpenID Connect](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-protocols-oidc)
- [GroupMember.Read.All Permission](https://learn.microsoft.com/en-us/graph/permissions-reference#directoryobject)
- [Configure Groups Claims](https://learn.microsoft.com/en-us/azure/active-directory/hybrid/how-to-connect-fed-group-claims)

### Application Documentation

- [API Permissions Request - Business Justification](./API_PERMISSIONS_JUSTIFICATION.md)
- [Entra ID Configuration Guide](./ENTRA_ID_CONFIG.md)
- [Security Groups Setup](./SECURITY_GROUPS_SETUP.md)

---

## Contact & Support

**For SSO Issues:**

- Development Team: [Your email]
- AD/InfoSec: [Roman or AD team email]

**Escalation Path:**

1. Check this troubleshooting guide
2. Review logs at `logs/authentication.log`
3. Contact development team
4. Escalate to AD/InfoSec if needed

---

**Last Updated:** [Current Date]  
**Document Version:** 1.0  
**Status:** Current (Phase 1 Implementation)
