# SSO Phase 2 Implementation Plan - Entra ID Group-Based Admin Management

**Status:** FUTURE PLANNING DOCUMENT  
**Phase:** Phase 2 - To Be Implemented Later  
**Current Phase:** Phase 1 (Local Admin Database)  
**Estimated Timeline:** Weeks 5-6 (if approved by organization)

---

## Overview

This document outlines the **FUTURE** implementation of Phase 2, which will migrate from local database admin management to centralized Entra ID security group-based RBAC.

**Current State:** Phase 1 in production (Basic SSO, local admin DB)  
**Future State:** Phase 2 planned (Entra ID groups for admin management)

---

## Why Phase 2? (Business Case)

### Current Issues with Phase 1

- ❌ Manual admin assignment (requires SQL or admin UI)
- ❌ No automatic sync when users join/leave organization
- ❌ Limited audit trail (only in app logs)
- ❌ Doesn't scale if multiple applications need admin management
- ❌ No visibility for IT/Security teams

### Phase 2 Benefits

- ✅ Centralized admin management in Entra ID
- ✅ Automatic role sync (on next login)
- ✅ Full Entra ID audit trail
- ✅ Single source of truth
- ✅ IT/Security can manage admin groups
- ✅ Scales across multiple applications
- ✅ Compliance-ready (governance trail)

---

## Phase 2 Architecture

### Current Phase 1 Architecture

```
User Logs In (SSO)
    ↓
Entra ID Authenticates
    ↓
Returns: email only (no groups)
    ↓
App checks LOCAL database
    ↓
Role assigned from LOCAL table
    ↓
User can access app
```

### Proposed Phase 2 Architecture

```
User Logs In (SSO)
    ↓
Entra ID Authenticates
    ↓
Returns: email + groups claim (embedded in token)
    ↓
App reads groups from token
    ↓
Groups mapped to local roles (ADMIN, SUPERADMIN)
    ↓
Role assigned based on group membership
    ↓
User can access app
```

---

## Entra ID Security Groups (Phase 2)

### Groups to Create

#### Group 1: onsemi-exensioreload-superadmins

```
Display Name: onsemi-exensioreload-superadmins
Email: onsemi-exensioreload-superadmins@onsemi.com
Description: ExensioReload system superadministrators
Type: Security Group
Membership: Manual

Initial Members:
  - junifferallan.garcia@onsemi.com (PM/Owner)

Application Role: ROLE_SUPER_ADMIN
Permissions: Full system access, user management, config changes
```

#### Group 2: onsemi-exensioreload-admins

```
Display Name: onsemi-exensioreload-admins
Email: onsemi-exensioreload-admins@onsemi.com
Description: ExensioReload administrators
Type: Security Group
Membership: Manual

Initial Members:
  - junifferallan.garcia@onsemi.com (PM/Owner)
  - eric.alfanta@onsemi.com (Operations)
  - jovenk.sorallo@onsemi.com (Operations)
  - glorymaae.llego@onsemi.com (Quality Assurance)
  - gilbert.miole@onsemi.com (Operations)

Application Role: ROLE_ADMIN
Permissions: Admin operations, bulk actions, monitoring
```

---

## Phase 2 Configuration Changes

### 1. Add GroupMember.Read.All Permission

**File:** `OAuth2ClientConfig.java`

```java
// PHASE 1 (Current):
.scope("openid", "profile", "email")

// PHASE 2 (Future):
.scope("openid", "profile", "email", "https://graph.microsoft.com/.default")
```

**Why:** Need elevated permission to read user's group memberships

---

### 2. Enable Groups Claim on ID Token

**In Entra ID Portal:**

1. App registration → Token configuration
2. Enable "Groups" claim
3. Set group threshold (optional)

**Result:** ID token will include groups claim:

```json
{
  "email": "eric.alfanta@onsemi.com",
  "groups": [
    "12345678-1234-1234-1234-123456789012", // superadmins group ID
    "87654321-4321-4321-4321-210987654321" // admins group ID
  ]
}
```

---

### 3. Implement Group-to-Role Mapping

**Create:** `SsoGroupRoleMapper.java`

```java
@Service
public class SsoGroupRoleMapper {

    @Value("${app.sso.group-id.superadmins:}")
    private String superadminGroupId;

    @Value("${app.sso.group-id.admins:}")
    private String adminGroupId;

    /**
     * Map Entra ID groups to application roles
     */
    public Set<String> mapGroupsToRoles(Collection<String> entraIdGroups) {
        Set<String> roles = new HashSet<>();
        roles.add("ROLE_USER");  // Default role

        if (entraIdGroups != null) {
            if (entraIdGroups.contains(superadminGroupId)) {
                roles.add("ROLE_SUPER_ADMIN");
                roles.remove("ROLE_USER");  // Superadmin implies admin
            }
            if (entraIdGroups.contains(adminGroupId)) {
                roles.add("ROLE_ADMIN");
                roles.remove("ROLE_USER");  // Admin implies user
            }
        }

        return roles;
    }
}
```

**Configuration:**

```yaml
app:
  sso:
    group-id:
      superadmins: '12345678-1234-1234-1234-123456789012'
      admins: '87654321-4321-4321-4321-210987654321'
```

---

### 4. Update SSO Success Handler

**File:** `SsoAuthenticationSuccessHandler.java`

```java
@Override
public void onAuthenticationSuccess(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Authentication authentication) throws IOException {

    OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
    String email = oidcUser.getEmail();

    // PHASE 2: Extract groups from token
    Collection<String> groupIds = extractGroupsFromToken(oidcUser);

    // PHASE 2: Map groups to roles
    Set<String> roles = groupRoleMapper.mapGroupsToRoles(groupIds);

    // Provision user with mapped roles
    AppUser user = provisioningService.provisionOrLoad(email, roles);

    // Generate JWT with roles from Entra ID groups
    String accessToken = jwtUtil.generateToken(user.getUsername(), roles);

    // ... rest of flow
}

private Collection<String> extractGroupsFromToken(OidcUser oidcUser) {
    // Read groups claim from ID token
    Object groupsClaim = oidcUser.getAttributes().get("groups");

    if (groupsClaim instanceof Collection) {
        return (Collection<String>) groupsClaim;
    }

    return Collections.emptyList();
}
```

---

## Phase 2 Implementation Steps

### Step 1: Request Admin Approval (Week 1)

```
To: Jayasree / AD Team
Subject: Phase 2 SSO Enhancement - GroupMember.Read.All Approval

We are planning Phase 2 enhancement to use Entra ID security groups
for admin management.

Requested:
- Approve GroupMember.Read.All permission
- Enable Groups claim on ID token
- Create security groups (provided in attached list)

Timeline: Implementation in weeks 5-6 (after Phase 1 stabilization)
```

### Step 2: Create Security Groups (Week 2)

**AD Team Actions:**

1. Create `onsemi-exensioreload-superadmins` group
2. Create `onsemi-exensioreload-admins` group
3. Add initial members to groups
4. Provide group object IDs to development team

---

### Step 3: Configure Entra ID (Week 2)

**AD Team Actions:**

1. Enable `GroupMember.Read.All` permission in app registration
2. Enable "Groups" claim on ID token
3. Configure group claim filtering (if needed)
4. Provide group object IDs

---

### Step 4: Development Implementation (Week 3)

**Development Team:**

**4.1 Update OAuth2ClientConfig.java**

```java
.scope("openid", "profile", "email", "https://graph.microsoft.com/.default")
```

**4.2 Create Group-to-Role Mapper**

```
Create: SsoGroupRoleMapper.java
Map Entra ID group IDs to application roles
```

**4.3 Update SSO Success Handler**

```
Extract groups from ID token
Map groups to roles using mapper
Provision user with group-based roles
```

**4.4 Update Configuration**

```yaml
app.sso.group-id.superadmins: <actual-group-id>
app.sso.group-id.admins: <actual-group-id>
```

**4.5 Database Migration (Optional)**

```sql
-- Option A: Keep local admin table as fallback
-- Option B: Archive local admin table
-- Option C: Hybrid - Check Entra ID groups first, fall back to local DB

-- Migration approach to decide:
-- Should we keep local DB for users not in any group?
-- Should we deprecate local admin table entirely?
```

---

### Step 5: Testing (Week 3)

**Staging Environment:**

1. Add test users to Entra ID groups
2. Test SSO with group membership
3. Verify roles assigned correctly
4. Test admin-only features
5. Test superadmin-only features
6. Verify group claim in token

**Test Cases:**

```
Test 1: User in superadmin group
  - Login
  - Verify ROLE_SUPER_ADMIN assigned
  - Verify all admin features accessible

Test 2: User in admin group (not superadmin)
  - Login
  - Verify ROLE_ADMIN assigned
  - Verify admin features accessible
  - Verify superadmin features NOT accessible

Test 3: User in neither group
  - Login
  - Verify ROLE_USER assigned
  - Verify only user features accessible

Test 4: User removed from group
  - User in admin group
  - Remove from Entra ID group
  - User logs out
  - User logs back in
  - Verify ROLE_USER assigned (role revoked)
```

---

### Step 6: Production Deployment (Week 4)

1. Deploy Phase 2 code to production
2. Monitor role assignments
3. Verify group-based access control
4. Monitor for errors
5. Compare Phase 1 and Phase 2 role assignments

---

### Step 7: Migration & Cleanup (Week 5+)

**Option 1: Hybrid Approach** (Recommended)

```
- Keep local admin table as fallback
- Check Entra ID groups first
- If user not in groups, check local DB
- Allows gradual migration
```

**Option 2: Full Migration**

```
- Archive local admin table
- Entra ID groups become source of truth
- All admin assignment via Entra ID
- Cleaner, simpler long-term
```

---

## Phase 2 Implementation Code

### Updated OAuth2ClientConfig.java

```java
@Bean
public ClientRegistrationRepository clientRegistrationRepository() {
    ClientRegistration registration = ClientRegistration
            .withRegistrationId("onsemi")
            .clientId(ssoProperties.getClientId())
            .clientSecret(ssoProperties.getClientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/onsemi")
            // PHASE 2: Include GroupMember.Read.All
            .scope("openid", "profile", "email", "https://graph.microsoft.com/.default")
            .authorizationUri("https://login.microsoftonline.com/" + ssoProperties.getTenantId() + "/oauth2/v2.0/authorize")
            .tokenUri("https://login.microsoftonline.com/" + ssoProperties.getTenantId() + "/oauth2/v2.0/token")
            .jwkSetUri("https://login.microsoftonline.com/" + ssoProperties.getTenantId() + "/discovery/v2.0/keys")
            .userInfoUri("https://graph.microsoft.com/oidc/userinfo")
            .userNameAttributeName(IdTokenClaimNames.SUB)
            .clientName("onsemi")
            .build();

    return new InMemoryClientRegistrationRepository(registration);
}
```

### New SsoGroupRoleMapper.java

```java
@Service
public class SsoGroupRoleMapper {

    private static final Logger logger = LoggerFactory.getLogger(SsoGroupRoleMapper.class);

    @Value("${app.sso.group-id.superadmins:}")
    private String superadminGroupId;

    @Value("${app.sso.group-id.admins:}")
    private String adminGroupId;

    /**
     * Map Entra ID groups to application roles
     */
    public Set<String> mapGroupsToRoles(Collection<String> entraIdGroups) {
        Set<String> roles = new HashSet<>();
        roles.add("ROLE_USER");  // Default role

        if (entraIdGroups == null || entraIdGroups.isEmpty()) {
            logger.debug("No Entra ID groups found; user gets default ROLE_USER");
            return roles;
        }

        logger.debug("Mapping Entra ID groups to roles: {}", entraIdGroups);

        if (entraIdGroups.contains(superadminGroupId)) {
            logger.info("User is in superadmin group");
            roles.add("ROLE_SUPER_ADMIN");
            roles.remove("ROLE_USER");
        } else if (entraIdGroups.contains(adminGroupId)) {
            logger.info("User is in admin group");
            roles.add("ROLE_ADMIN");
            roles.remove("ROLE_USER");
        }

        logger.debug("Final roles assigned: {}", roles);
        return roles;
    }
}
```

### Updated SsoAuthenticationSuccessHandler.java (Phase 2 portion)

```java
@Override
public void onAuthenticationSuccess(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Authentication authentication) throws IOException {
    try {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String email = oidcUser.getEmail();

        // PHASE 2: Extract groups from ID token
        Collection<String> groupIds = extractGroupsFromToken(oidcUser);

        // PHASE 2: Map groups to roles
        Set<String> localRoles = groupRoleMapper.mapGroupsToRoles(groupIds);

        // Provision or load user
        AppUser user = provisioningService.provisionOrLoad(email, localRoles);

        // ... rest remains the same
    } catch (Exception e) {
        logger.error("Error in SSO success handler", e);
        throw new IOException("SSO authentication failed", e);
    }
}

private Collection<String> extractGroupsFromToken(OidcUser oidcUser) {
    try {
        Object groupsClaim = oidcUser.getAttributes().get("groups");

        if (groupsClaim instanceof Collection) {
            Collection<?> groups = (Collection<?>) groupsClaim;
            return groups.stream()
                .map(Object::toString)
                .collect(Collectors.toList());
        }
    } catch (Exception e) {
        logger.warn("Failed to extract groups from token", e);
    }

    return Collections.emptyList();
}
```

---

## Phase 2 Deployment Checklist

### Pre-Deployment

- [ ] Admin approval for GroupMember.Read.All received
- [ ] Entra ID security groups created
- [ ] Groups claim enabled on ID token
- [ ] Group object IDs obtained
- [ ] Configuration updated with group IDs
- [ ] Code changes reviewed
- [ ] Staging testing completed
- [ ] All test cases pass

### Deployment

- [ ] Build Phase 2 code
- [ ] Deploy to staging
- [ ] Verify group-based roles
- [ ] Deploy to production
- [ ] Monitor role assignments

### Post-Deployment

- [ ] Verify all admin users in groups
- [ ] Compare Phase 1 and Phase 2 assignments
- [ ] Monitor for errors
- [ ] Document final configuration
- [ ] Plan local DB cleanup

---

## Rollback Plan (Phase 2)

If Phase 2 fails:

```bash
# Revert to Phase 1
git checkout <phase1-commit>
mvn clean package -DskipTests
docker-compose restart exensio-reload
```

**Recovery Steps:**

1. Remove GroupMember.Read.All scope
2. Revert to local database role checking
3. Users continue working with Phase 1
4. Investigate Phase 2 issues
5. Plan retry

---

## Success Metrics (Phase 2)

- ✅ Users automatically get admin role when added to Entra ID group
- ✅ Users automatically lose admin role when removed from Entra ID group
- ✅ Admin-only features work correctly with Entra ID groups
- ✅ Audit trail shows all group changes in Entra ID
- ✅ No manual database updates needed for role assignment
- ✅ Zero errors related to group mapping

---

## Questions for Future Phase 2 Planning

1. **Hybrid or Full Migration?**
   - Keep local DB as fallback?
   - Or fully commit to Entra ID groups?

2. **Timeline?**
   - After how long should Phase 2 be considered?
   - Should we have Phase 2 in mind during Phase 1?

3. **Other Applications?**
   - Will other applications also use these groups?
   - Should groups be general or app-specific?

4. **Escalation Path?**
   - How quickly should admins be added to groups?
   - Who approves admin group membership?

---

## References

- Phase 1: `SSO_IMPLEMENTATION_GUIDE.md`
- Phase 1 Backup: `SSO_DEPLOYMENT_CHECKLIST_PHASE1_BACKUP.md`
- Current Status: `SSO_DEPLOYMENT_CHECKLIST.md`
- Admin Checking: `SSO_ADMIN_ROLE_CHECKING.md`

---

**Document Status:** FUTURE PLANNING  
**Version:** 1.0  
**Last Updated:** [Current Date]  
**Estimated Implementation:** Weeks 5-6 (if approved)  
**Current Focus:** Phase 1 Stabilization
