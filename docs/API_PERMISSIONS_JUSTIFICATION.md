# API Permissions Request - Business Justification

**Status:** For AD/InfoSec Review  
**Application:** Exensio Reload  
**Requested By:** [Development Team]  
**Date:** [Current Date]

---

## Executive Summary

The Exensio Reload application requires specific Microsoft Entra ID (Azure AD) API permissions to implement role-based access control (RBAC) for SSO authentication. This document provides the complete business rationale for each requested permission and explains why certain permission types are necessary at different implementation phases.

**Key Points:**

- Phase 1 (Immediate): Only basic SSO permissions needed
- Phase 2 (Future): Group-based RBAC permissions required
- Admin approval not needed until Phase 2

---

## Table of Contents

1. [API Permissions Summary](#api-permissions-summary)
2. [Phase 1 Permissions (Immediate)](#phase-1-permissions-immediate)
3. [Phase 2 Permissions (Future)](#phase-2-permissions-future)
4. [Why Application-Type Permission is Required](#why-application-type-permission-is-required)
5. [Security Groups Configuration](#security-groups-configuration)
6. [Implementation Timeline](#implementation-timeline)
7. [Approval Process](#approval-process)
8. [Compliance & Audit](#compliance--audit)

---

## API Permissions Summary

### Permissions Requested

| Permission                | Type          | Phase   | Approval Status | Business Justification                     |
| ------------------------- | ------------- | ------- | --------------- | ------------------------------------------ |
| **openid**                | Delegated     | Phase 1 | Auto-granted    | Enable OIDC authentication protocol        |
| **profile**               | Delegated     | Phase 1 | Auto-granted    | Retrieve user name and profile information |
| **email**                 | Delegated     | Phase 1 | Auto-granted    | Retrieve user email for identification     |
| **GroupMember.Read.All**  | Application   | Phase 2 | Pending         | Read security group memberships for RBAC   |
| **Groups Claim on Token** | Configuration | Phase 2 | Pending         | Embed group membership in ID token         |

---

## Phase 1 Permissions (Immediate)

### Scope: openid, profile, email

These are standard OpenID Connect permissions that enable basic SSO functionality.

#### Permission 1: `openid`

**Purpose:** Enable OpenID Connect protocol for authentication

**Business Justification:**

- Allows users to authenticate using their Microsoft corporate account
- Enables single sign-on without storing passwords in the application
- Reduces security risk by delegating authentication to Entra ID
- Requirement for modern enterprise authentication standards

**User Consent Flow:**

- First login: User consents to "Sign you in"
- Auto-granted: No admin approval needed
- Scope: User data only (openid connect protocol)

**Technical Details:**

- Uses OAuth 2.0 implicit or authorization code flow
- Returns ID token for user identification
- No sensitive data requires consent

---

#### Permission 2: `profile`

**Purpose:** Retrieve user profile information for identification

**Business Justification:**

- Application displays user's display name in UI
- Required for personalized user experience
- Shows "Logged in as: John Doe" in application header
- Used for audit logs and activity tracking

**Data Retrieved:**

```json
{
  "name": "John Doe",
  "given_name": "John",
  "family_name": "Doe",
  "picture": "https://...",
  "locale": "en-US"
}
```

**User Consent Flow:**

- First login: User consents to "See your profile"
- Auto-granted: No admin approval needed
- Scope: User's own profile only

**Technical Details:**

- Standard OpenID Connect profile scope
- User has full control over their profile information
- No sensitive data beyond basic identification

---

#### Permission 3: `email`

**Purpose:** Retrieve user's email address for identification and communication

**Business Justification:**

- Email used as unique identifier for user accounts
- Required for matching with company directory
- Used for notifications and communications
- Enables user-specific role assignment (admin identification)

**Data Retrieved:**

```json
{
  "email": "john.doe@onsemi.com",
  "email_verified": true
}
```

**User Consent Flow:**

- First login: User consents to "See your email addresses"
- Auto-granted: No admin approval needed
- Scope: User's own email only

**Technical Details:**

- Standard OpenID Connect email scope
- Only user's own email is returned
- Email verified flag indicates trustworthiness

**Usage in Application:**

- Admin role assignment: Lookup `john.doe@onsemi.com` in admin database
- Audit logging: Record `john.doe@onsemi.com` performed action X
- User identification: Display `john.doe@onsemi.com` in logs

---

### Phase 1 Summary

**Permissions Needed NOW:**

```
openid profile email
```

**Admin Approval Required:** ❌ NO

**Approval Dialog Shown:** ❌ NO

**Timeline:** Deploy immediately (Week 1)

**User Impact:** None - standard SSO experience

---

## Phase 2 Permissions (Future)

### Scope: openid, profile, email + GroupMember.Read.All

#### Permission 4: `GroupMember.Read.All` (Application-Type)

**Purpose:** Enable application to read security group memberships for any user

**When Needed:** Phase 2 (Weeks 3-4), when using Entra ID security groups for role management

**Business Justification:**

1. **Centralized Role Management**
   - Currently: Admins defined in local application database
   - Phase 2: Admins defined in Entra ID security groups
   - Benefit: Single source of truth for organizational roles

2. **Authorization Verification**
   - Application must verify: "Is user alice in onsemi-exensioreload-admins group?"
   - User alice doesn't have permission to query group memberships
   - Application needs elevated permission to verify this

3. **Compliance & Governance**
   - Audit trail: All group changes tracked in Entra ID
   - Centralized control: AD team manages group membership
   - Compliance ready: Security teams can audit role assignments

4. **Enterprise Standard**
   - Industry best practice for multi-application role management
   - Enables IT/Security to control app permissions organization-wide
   - Scalable solution for growing application portfolio

---

### Why Application-Type Permission (Not Delegated)

#### The Problem with Delegated Permissions

**Delegated Permission Definition:**

- Operates within context of logged-in user
- Can only access resources that user has permission to access
- Scoped to user's personal privileges

**Example - Why This Doesn't Work:**

```
Scenario: Alice logs in, we want to verify if she's an admin

With DELEGATED GroupMember.Read.All:
  1. Alice logs in
  2. App requests: "Alice, what groups are you in?"
  3. Alice can answer: "I'm in Sales and onsemi-exensioreload-admins"
  4. BUT: Alice cannot query Bob's groups (she's not Bob)
  5. This only works for reading the current user's groups

Result: ❌ Works only for current user, NOT for general queries
```

#### The Solution with Application-Type Permission

**Application Permission Definition:**

- Operates using application's own identity (service principal)
- Can access resources up to permission level granted
- Scoped to organizational needs (not user privileges)

**Example - How This Works:**

```
Scenario: Alice logs in, we want to verify if she's an admin

With APPLICATION GroupMember.Read.All:
  1. Alice logs in
  2. App receives Alice's ID token with embedded groups
  3. App backend uses APPLICATION credential (not Alice's)
  4. App backend queries: "What groups is user abc-123 in?"
  5. Entra ID responds: "User is in onsemi-exensioreload-admins"
  6. App verifies: Alice IS an admin
  7. Grant admin permissions

Result: ✅ Works for any user, anywhere in org
```

#### Key Difference Table

| Aspect                      | Delegated          | Application                           |
| --------------------------- | ------------------ | ------------------------------------- |
| **Auth Context**            | User's credentials | App's credentials (service principal) |
| **Can Read Own Groups**     | ✅ YES             | ✅ YES                                |
| **Can Read Others' Groups** | ❌ NO              | ✅ YES                                |
| **Works Server-Side**       | ⚠️ Limited         | ✅ YES                                |
| **Requires Admin Consent**  | ❌ NO              | ✅ YES                                |
| **When Used**               | Delegated to app   | Standalone app operations             |

---

### Token Configuration: Groups Claim

#### What is a Groups Claim?

**Without Groups Claim:**

```
User Login
    ↓
App receives ID token: {email, name, id}
    ↓
App must call Microsoft Graph API
    ↓
Query: "What groups is user in?"
    ↓
Graph API response: [onsemi-exensioreload-admins]
    ↓
App decides permissions based on response
```

**With Groups Claim:**

```
User Login
    ↓
App receives ID token: {email, name, id, groups: [onsemi-exensioreload-admins]}
    ↓
App immediately knows groups from token
    ↓
No additional API call needed
    ↓
App decides permissions immediately
```

#### Why Groups Claim is Important

**Performance Benefits:**

- Eliminates additional API call on every login
- Reduces latency: 200-500ms faster
- Reduces quota usage: One fewer call per login

**Reliability Benefits:**

- Independent of Microsoft Graph API availability
- Reduces cascading failures if Graph API is slow
- Better user experience during peak times

**Cost Benefits:**

- Reduces API quota usage
- Allows more users with same quota
- Scales better for large organizations

**Configuration Required:**

1. Enable "Groups" claim in Entra ID app registration
2. Set group threshold (default: all groups)
3. Configure group filter (optional)

---

## Why Application-Type Permission is Required

### The Technical Reason

**Question:** "Can we use delegated GroupMember.Read.All instead?"

**Answer:** No, because:

1. **User Context Limitation**

   ```
   Delegated permission = permission of current user

   Scenario: Alice (user) logs in
   Can query: Only groups Alice is in (via user context)
   Cannot query: Groups any other user is in

   Problem: We need to verify Alice's groups without needing Alice's permission
   ```

2. **Background Operations**

   ```
   Token refresh happens server-side (no user login)
   Delegated permissions cannot be used (no active user)
   Application permissions required for backend operations
   ```

3. **Service-to-Service Communication**
   ```
   App backend needs to verify user groups independently
   Cannot depend on user's permissions for this check
   Must use app's own credentials
   ```

### Security Implications

**Why Admin Approval is Required:**

Application-type permissions are powerful because:

- App can read ANY user's group membership
- Not limited to current user's privileges
- Affects entire organization

Therefore:

- ✅ Requires explicit admin approval
- ✅ Creates audit trail
- ✅ Enables organizational control
- ✅ Follows principle of least privilege

**Approval is a Good Thing Because:**

- Admin knows exactly what permission is granted
- Only approved apps get this elevated permission
- Creates audit log: "Admin approved ExensioReload for GroupMember.Read.All"
- IT can revoke permission if needed

---

## Security Groups Configuration

### Security Groups Required

#### Group 1: onsemi-exensioreload-superadmins

```
Display Name: onsemi-exensioreload-superadmins
Description: Exensio Reload system superadministrators
Mail Nickname: onsemi-exensioreload-superadmins
Mail Enabled: YES (for Azure AD distribution group)
Security Group: YES

Members:
  - junifferallan.garcia@onsemi.com

Permissions in Application:
  - Full system access
  - User management
  - System configuration
  - Role assignment
  - System monitoring and diagnostics
```

**Business Justification:**

- Single point of control for system access
- Audit trail for who has superadmin access
- Can quickly revoke/grant access by updating group
- Follows principle of least privilege

---

#### Group 2: onsemi-exensioreload-admins

```
Display Name: onsemi-exensioreload-admins
Description: Exensio Reload administrators
Mail Nickname: onsemi-exensioreload-admins
Mail Enabled: YES
Security Group: YES

Members:
  - junifferallan.garcia@onsemi.com (PM/Owner)
  - eric.alfanta@onsemi.com (Operations)
  - jovenk.sorallo@onsemi.com (Operations)
  - glorymaae.llego@onsemi.com (Quality Assurance)
  - gilbert.miole@onsemi.com (Operations)

Permissions in Application:
  - Bulk operations (bulk resend)
  - System monitoring
  - Report generation
  - Record management
  - Performance tracking
```

**Business Justification:**

- Distributes operational responsibility
- Prevents bottleneck on single admin
- Enables specialized roles (QA, Operations, PM)
- Each member can be individually audited
- Easy to add/remove members without code changes

---

### Group Member Justification

| Member               | Email                           | Role          | Justification                     |
| -------------------- | ------------------------------- | ------------- | --------------------------------- |
| **Junif F. Garcia**  | junifferallan.garcia@onsemi.com | PM/Superadmin | Product owner and primary contact |
| **Eric Alfanta**     | eric.alfanta@onsemi.com         | Admin         | Operations support                |
| **Joven K. Sorallo** | jovenk.sorallo@onsemi.com       | Admin         | Operations support                |
| **GloryMae Llego**   | glorymaae.llego@onsemi.com      | Admin         | Quality assurance                 |
| **Gilbert Miole**    | gilbert.miole@onsemi.com        | Admin         | Operations support                |

---

## Implementation Timeline

### Phase 1: Immediate (Week 1)

**What:** Deploy SSO with basic authentication
**When:** Immediately (no approval needed)
**Permissions Used:** openid, profile, email

```
Timeline:
  Week 1: Deploy Phase 1
    Monday: Remove GroupMember.Read.All from config
    Tuesday: Deploy to staging
    Wednesday: Deploy to production
    Thursday-Friday: Monitor and stabilize
```

**User Impact:**

- ✅ Users can log in with corporate credentials
- ✅ No approval dialog
- ✅ Full application functionality
- ✅ Admin roles assigned from local database

**Dependencies:**

- None - can proceed independently

---

### Phase 2: Future (Weeks 3-4)

**What:** Migrate to Entra ID group-based role management
**When:** After Phase 1 is stable
**Permissions Needed:** GroupMember.Read.All (application-type)

```
Timeline:
  Week 1-2: Phase 1 deployment and stabilization
  Week 3: Phase 2 preparation
    Monday: Request admin approval for GroupMember.Read.All
    Tuesday-Wednesday: AD/InfoSec creates security groups
    Thursday-Friday: Application development team implements group reading

  Week 4: Phase 2 deployment
    Monday-Tuesday: Test in staging with real groups
    Wednesday: Final testing
    Thursday-Friday: Production deployment
```

**AD/InfoSec Activities:**

1. Create security groups (onsemi-exensioreload-superadmins, onsemi-exensioreload-admins)
2. Add members to groups
3. Approve GroupMember.Read.All permission request
4. Enable groups claim on ID token

**Development Team Activities:**

1. Implement group-based role checking
2. Update authentication service
3. Configure group IDs
4. Test with real groups
5. Deploy Phase 2

---

## Approval Process

### Current Status

| Item                                         | Status           | Approval By               |
| -------------------------------------------- | ---------------- | ------------------------- |
| Phase 1 Permissions (openid, profile, email) | ✅ Ready         | Auto (no approval needed) |
| Phase 2 Permission (GroupMember.Read.All)    | ⏳ Future        | Admin approval required   |
| Security Groups                              | ⏳ To be created | AD/InfoSec                |
| Groups Claim Configuration                   | ⏳ Future        | AD/InfoSec                |

### Phase 1 Approval Process

**For Phase 1 (Immediate):**

```
Step 1: Confirm authorization
  → These permissions require NO admin approval
  → Users will consent on first login (automatic)
  → No special approval needed

Step 2: Deploy application
  → Proceed with Phase 1 implementation
  → Deploy to production
  → Begin user acceptance testing

Step 3: Monitor
  → Track login success rate
  → Gather user feedback
  → Plan Phase 2 transition
```

**Approval Status:** ✅ Ready to proceed

---

### Phase 2 Approval Process (Future)

**For Phase 2 (Weeks 3-4):**

```
Step 1: Formal Request
  → Development team submits formal approval request
  → Provides this document as justification
  → Explains business need for GroupMember.Read.All

Step 2: Admin Review
  → Admin (Roman) reviews permission details
  → Confirms security posture
  → Verifies organizational need

Step 3: Approval
  → Admin approves GroupMember.Read.All permission
  → Creates audit log entry
  → Notifies development team

Step 4: Configuration
  → Enable groups claim on ID token
  → Configure group object IDs in application
  → Set up group membership validation

Step 5: Testing
  → Test with real Entra ID groups
  → Validate role assignments
  → Verify audit trail

Step 6: Deployment
  → Deploy Phase 2 to staging
  → Validate in production-like environment
  → Deploy to production
```

**Expected Timeline:** Weeks 3-4

**Approval Request Template (for future use):**

```
To: Roman / AD/InfoSec
Cc: Development Team

Subject: Permission Approval Request - ExensioReload - GroupMember.Read.All

Application: ExensioReload
Permission: GroupMember.Read.All (Application-type)

Business Justification: [Include Phase 2 details from this document]
Security Consideration: [Explain audit trail and control mechanisms]
Timeline: [Expected deployment window]
Rollback Plan: [Plan to revert if needed]

Requested By: [Development Team Lead]
Date: [Date]
```

---

## Compliance & Audit

### Compliance Considerations

#### Data Protection

- ✅ Only reading group membership (not sensitive data)
- ✅ No personally identifiable information (PII) collected
- ✅ No data stored beyond session
- ✅ GDPR compliant (group membership is organizational data)

#### Audit Trail

- ✅ All group membership queries logged
- ✅ Entra ID logs all group changes
- ✅ Application logs all role assignments
- ✅ Full audit trail for compliance review

#### Principle of Least Privilege

- ✅ Only requesting necessary permissions
- ✅ Admin approval enforces organizational control
- ✅ Group-based (not user-based direct permissions)
- ✅ Easy to revoke if needed

---

### Audit Requirements

**For Phase 1:**

- No special audit requirements
- Standard SSO authentication logging
- User login timestamps recorded

**For Phase 2:**

- Group membership queries logged
- Authorization decisions documented
- Admin role changes tracked
- Failed authorization attempts logged

**Audit Log Format:**

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "user_email": "john.doe@onsemi.com",
  "action": "AUTHORIZATION_CHECK",
  "groups_checked": ["onsemi-exensioreload-admins"],
  "result": "AUTHORIZED_AS_ADMIN",
  "source_ip": "10.0.0.100",
  "user_agent": "Mozilla/5.0..."
}
```

---

### Security Monitoring

**Alerts to Configure:**

1. Failed authorization attempts (> 5 in 1 hour)
2. Unauthorized admin access attempts
3. Group membership changes
4. Permission approval changes
5. Suspicious token generation

**Review Frequency:**

- Daily: Check failed authorization attempts
- Weekly: Review group membership changes
- Monthly: Audit trail review for compliance
- Quarterly: Security posture assessment

---

## Summary & Recommendation

### Quick Answer to Key Questions

**Q: Do we need all these permissions NOW?**  
A: No. Only openid, profile, email are needed for Phase 1 (immediate). GroupMember.Read.All is needed only for Phase 2 (weeks 3-4).

**Q: Is admin approval needed for Phase 1?**  
A: No. Users will automatically consent to openid, profile, email permissions on first login.

**Q: Why do we need Application-type permission?**  
A: To allow the application (not individual users) to read any user's group membership for authorization verification.

**Q: Can we use Delegated permission instead?**  
A: No. Delegated permissions only work for the current user and cannot be used for background operations.

**Q: Is this secure?**  
A: Yes. Application-type permissions are the enterprise standard for centralized role management. Admin approval creates an audit trail and enables organizational control.

**Q: What's the timeline?**  
A: Phase 1 (SSO) in 1-2 weeks. Phase 2 (group-based RBAC) in weeks 3-4.

---

### Recommendation

**Approve for Immediate Deployment:**
✅ Phase 1 permissions (openid, profile, email)
✅ Users can log in immediately
✅ No admin approval needed

**Prepare for Future Approval:**
⏳ Phase 2 permissions (GroupMember.Read.All)
⏳ To be requested in ~2 weeks
⏳ When Phase 1 is stable

**Parallel Actions:**

- AD/InfoSec: Create security groups in Entra ID
- Development: Implement Phase 2 group-based authorization
- Plan Phase 2 transition for production deployment

---

## Next Steps

### For Development Team

1. **Immediate (This Week):**
   - [ ] Remove GroupMember.Read.All from application config
   - [ ] Test Phase 1 authentication flow
   - [ ] Deploy to staging
   - [ ] Deploy to production
   - [ ] Monitor user logins

2. **Week 2-3:**
   - [ ] Gather feedback from users
   - [ ] Implement Phase 2 group-based logic
   - [ ] Prepare for Phase 2 deployment

3. **Week 4:**
   - [ ] Deploy Phase 2 to staging
   - [ ] Test with real Entra ID groups
   - [ ] Deploy Phase 2 to production

### For AD/InfoSec Team

1. **Immediate:**
   - [ ] No action needed for Phase 1

2. **Week 2-3 (When development team requests):**
   - [ ] Create security groups in Entra ID
   - [ ] Add members to groups
   - [ ] Prepare to approve GroupMember.Read.All

3. **Week 4:**
   - [ ] Approve GroupMember.Read.All permission
   - [ ] Enable groups claim on ID token
   - [ ] Complete Phase 2 configuration

---

## Appendix

### A. Permission Details Reference

**OpenID Connect Scopes:**

- `openid` - Enable OIDC protocol
- `profile` - Access to: name, given_name, family_name, picture, locale
- `email` - Access to: email, email_verified

**Microsoft Graph Permissions:**

- `GroupMember.Read.All` - Read all group memberships

### B. Entra ID Configuration Checklist

**Phase 1 Configuration:**

- [ ] OAuth application registration created
- [ ] Redirect URIs configured
- [ ] Scopes: openid, profile, email
- [ ] Token configuration: Standard (no groups claim)
- [ ] Application deployed

**Phase 2 Configuration (Future):**

- [ ] GroupMember.Read.All permission added
- [ ] Admin approval granted
- [ ] Groups claim enabled on ID token
- [ ] Group object IDs configured in application
- [ ] Application updated to read groups from token

### C. Deployment Checklist

**Pre-deployment:**

- [ ] Code review completed
- [ ] Security review completed
- [ ] Configuration tested
- [ ] Documentation updated

**Deployment:**

- [ ] Deploy to staging
- [ ] Execute test cases
- [ ] Verify no approval dialog
- [ ] Verify user roles assigned
- [ ] Deploy to production

**Post-deployment:**

- [ ] Monitor login success rate
- [ ] Check error logs
- [ ] Gather user feedback
- [ ] Document any issues

---

**Document Status:** Ready for Review  
**Last Updated:** [Current Date]  
**Version:** 1.0  
**Next Review:** After Phase 1 deployment (Week 2)
