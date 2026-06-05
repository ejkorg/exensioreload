# How SSO Authenticated Accounts Check for Admin/Superadmin Roles

**Document Purpose:** Explains the complete flow of how the application validates admin and superadmin roles for SSO-authenticated users.

**Updated Configuration:** With GroupMember.Read.All removed, roles are managed via LOCAL DATABASE.

---

## Overview

When a user logs in via SSO (Microsoft Entra ID), the application performs these steps to assign and verify admin/superadmin roles:

```
1. User authenticates via Entra ID
2. App receives email from ID token
3. App checks LOCAL database for user record
4. If not found → Create new user with default role (USER)
5. If found → Load existing user with their assigned role
6. Generate JWT token with user's roles
7. On each request → Check roles from JWT/SecurityContext
8. Grant/deny access based on role
```

---

## Complete Authentication & Authorization Flow

### Step 1: User Initiates SSO Login

```
User clicks "Sign in with Microsoft"
    ↓
Spring Security redirects to Entra ID login
    ↓
URL: https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/authorize
    ?client_id=6e0e3995-263c-4511-a5fb-8b3db9ce4ed2
    &scope=openid+profile+email
    &redirect_uri=https://app.onsemi.com/login/oauth2/code/onsemi
```

**Configuration File:** `OAuth2ClientConfig.java`

```java
.scope("openid", "profile", "email")  // NO GroupMember.Read.All
.redirectUri("{baseUrl}/login/oauth2/code/onsemi")
```

---

### Step 2: Entra ID Authenticates & Returns ID Token

```
User enters Microsoft credentials
    ↓
Entra ID verifies identity
    ↓
Entra ID redirects back to app with authorization code
    ↓
App exchanges code for tokens
    ↓
ID Token received:
{
  "email": "john.doe@onsemi.com",
  "name": "John Doe",
  "oid": "user-object-id",
  "sub": "user-subject",
  "tid": "tenant-id"
  // NO groups claim (because GroupMember.Read.All not requested)
}
```

---

### Step 3: SSO Success Handler Processes Authentication

**File:** `SsoAuthenticationSuccessHandler.java`

```
onAuthenticationSuccess() called
    ↓
Extract email from OidcUser
    Email = "john.doe@onsemi.com"
    ↓
Extract group claims (if present)
    Groups = [] (empty, because no GroupMember.Read.All)
    ↓
Map IdP groups to local roles
    SsoRoleMapper.mapRoles([])
    Result: [] (no groups → no predefined roles)
    ↓
Provision or load user
    SsoUserProvisioningService.provisionOrLoad(email, roles)
```

**Code:**

```java
@Override
public void onAuthenticationSuccess(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Authentication authentication) throws IOException {
    // 1. Extract email from ID token
    OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
    String email = oidcUser.getEmail();

    // 2. Extract group claims (will be empty since no GroupMember.Read.All)
    Collection<String> idpGroups = extractGroupClaims(oidcUser);

    // 3. Map IdP groups to local roles
    Set<String> localRoles = roleMapper.mapRoles(idpGroups);  // Empty set

    // 4. Provision or load user from database
    AppUser user = provisioningService.provisionOrLoad(email, localRoles);

    // 5. Issue JWT with user's roles
    String accessToken = jwtUtil.generateToken(user.getUsername(), user.getRoles());
}
```

---

### Step 4: User Provisioning Service Handles User Creation/Loading

**File:** `SsoUserProvisioningService.java`

```java
public AppUser provisionOrLoad(String email, Set<String> idpRoles) {

    // Check if user exists in LOCAL database
    Optional<AppUser> existingUser = repository.findByEmailIgnoreCase(email);

    if (existingUser.isPresent()) {
        // User already exists → load existing user with their assigned roles
        AppUser user = existingUser.get();
        logger.info("SSO: Loaded existing user: {} with roles: {}", email, user.getRoles());
        return user;
    } else {
        // New SSO user → create new user
        AppUser newUser = new AppUser();
        newUser.setUsername(email);
        newUser.setEmail(email);

        // Default role: USER (not admin)
        // Admin/Superadmin roles must be manually assigned in database
        Set<String> defaultRoles = new HashSet<>();
        defaultRoles.add("ROLE_USER");  // Default role for new users

        newUser.setRoles(defaultRoles);
        newUser.setEnabled(true);
        newUser.setStatus(AppUser.UserStatus.ACTIVE);

        AppUser saved = repository.save(newUser);
        logger.info("SSO: Created new user: {} with default roles: {}", email, defaultRoles);
        return saved;
    }
}
```

**Key Points:**

- New SSO users get `ROLE_USER` role by default
- They are NOT automatically admin or superadmin
- Admins must be manually assigned in database (see below)
- Existing users' roles are preserved

---

### Step 5: Database Schema - Where Roles Are Stored

**Tables:**

```sql
-- Main users table
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN DEFAULT true,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    last_login_at TIMESTAMP
);

-- Roles are stored in a separate table (JPA @ElementCollection)
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE KEY unique_user_role (user_id, role)
);

-- Example data
INSERT INTO users (username, email, password_hash, enabled, status, created_at, updated_at)
VALUES ('john.doe@onsemi.com', 'john.doe@onsemi.com', 'not-used-for-sso', true, 'ACTIVE', NOW(), NOW());

-- Assign roles to users
INSERT INTO user_roles (user_id, role) VALUES (1, 'ROLE_USER');       -- Regular user
INSERT INTO user_roles (user_id, role) VALUES (2, 'ROLE_ADMIN');      -- Admin
INSERT INTO user_roles (user_id, role) VALUES (3, 'ROLE_SUPER_ADMIN'); -- Superadmin
```

---

### Step 6: JWT Token Generation with Roles

**File:** `JwtUtil.java`

```java
public String generateToken(String username, Set<String> roles) {
    // Create JWT with user's roles
    Map<String, Object> claims = new HashMap<>();
    claims.put("roles", roles);
    claims.put("email", getEmailForUsername(username));

    // Token format:
    // {
    //   "username": "john.doe@onsemi.com",
    //   "roles": ["ROLE_USER"],  // or ["ROLE_ADMIN"] or ["ROLE_SUPER_ADMIN"]
    //   "iat": 1234567890,
    //   "exp": 1234571490
    // }

    return Jwts.builder()
        .setClaims(claims)
        .setSubject(username)
        .setIssuedAt(new Date())
        .setExpiration(new Date(System.currentTimeMillis() + JWT_EXPIRATION_MS))
        .signWith(getSigningKey(), SignatureAlgorithm.HS256)
        .compact();
}
```

---

### Step 7: Request Processing - Checking Admin Status

When a user makes a request to the application:

**File:** `RoleService.java`

```java
@Service
public class RoleService {

    /**
     * Check if current user is Admin (including Superadmin)
     */
    public boolean isAdmin() {
        AppUser currentUser = getCurrentUser();
        return currentUser != null && currentUser.isAdmin();
    }

    /**
     * Check if current user is Superadmin
     */
    public boolean isSuperAdmin() {
        AppUser currentUser = getCurrentUser();
        return currentUser != null && currentUser.isSuperAdmin();
    }

    /**
     * Get current authenticated user from SecurityContext
     */
    public AppUser getCurrentUser() {
        try {
            // Get authentication from Spring Security context
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth != null && auth.getName() != null) {
                // Username is the email (for SSO users)
                String email = auth.getName();

                // Load user from database using email
                Optional<AppUser> user = userRepository.findByUsername(email);

                return user.orElse(null);
            }
        } catch (Exception e) {
            logger.error("Error getting current user", e);
        }
        return null;
    }
}
```

**AppUser.java convenience methods:**

```java
public class AppUser {
    private Set<String> roles;  // e.g., ["ROLE_USER", "ROLE_ADMIN"]

    /**
     * Check if user is admin (includes both ADMIN and SUPER_ADMIN)
     */
    public boolean isAdmin() {
        return hasRole("ROLE_ADMIN") || hasRole("ROLE_SUPER_ADMIN");
    }

    /**
     * Check if user is superadmin
     */
    public boolean isSuperAdmin() {
        return hasRole("ROLE_SUPER_ADMIN");
    }

    /**
     * Generic role check
     */
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
```

---

### Step 8: Using Admin Checks in Controllers

**Example Controller Usage:**

```java
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private RoleService roleService;

    /**
     * Only accessible to admins
     */
    @GetMapping("/dashboard")
    public ResponseEntity<?> getAdminDashboard() {
        // Check if current user is admin
        if (!roleService.isAdmin()) {
            return ResponseEntity.status(403).body("Access denied. Admin role required.");
        }

        // Admin-only logic here
        return ResponseEntity.ok(Map.of("message", "Admin dashboard data"));
    }

    /**
     * Only accessible to superadmins
     */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest req) {
        // Check if current user is superadmin
        if (!roleService.isSuperAdmin()) {
            return ResponseEntity.status(403).body("Access denied. Superadmin role required.");
        }

        // Superadmin-only logic here
        return ResponseEntity.ok(Map.of("message", "User created"));
    }
}
```

---

## How to Assign Admin/Superadmin Roles

Since roles are stored in the LOCAL database and NOT synced from Entra ID, you must manually manage them:

### Option 1: Manually via SQL

```sql
-- Find the user
SELECT * FROM users WHERE email = 'john.doe@onsemi.com';
-- Result: id = 1

-- Check current roles
SELECT * FROM user_roles WHERE user_id = 1;
-- Result: ROLE_USER

-- Add admin role
INSERT INTO user_roles (user_id, role) VALUES (1, 'ROLE_ADMIN');

-- Verify
SELECT * FROM user_roles WHERE user_id = 1;
-- Result: ROLE_USER, ROLE_ADMIN

-- User now has both USER and ADMIN roles!
```

### Option 2: Via Application Admin UI (if available)

```
1. Admin logs into application
2. Navigate to User Management
3. Find user: john.doe@onsemi.com
4. Assign role: ADMIN or SUPER_ADMIN
5. Save
6. User next login will have new role
```

### Option 3: Programmatically via Service

```java
@Service
public class UserManagementService {

    public void assignAdminRole(String email) {
        AppUser user = repository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        user.getRoles().add("ROLE_ADMIN");
        repository.save(user);

        logger.info("Assigned ADMIN role to user: {}", email);
    }

    public void assignSuperadminRole(String email) {
        AppUser user = repository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        user.getRoles().add("ROLE_SUPER_ADMIN");
        // Usually only one superadmin, but multiple are allowed
        repository.save(user);

        logger.info("Assigned SUPER_ADMIN role to user: {}", email);
    }
}
```

---

## Complete Role Assignment Example

### Scenario: Make eric.alfanta@onsemi.com an Admin

**Step 1: User logs in first time via SSO**

```
1. eric.alfanta@onsemi.com clicks "Sign in with Microsoft"
2. Entra ID authenticates
3. Email returned: eric.alfanta@onsemi.com
4. App checks database: User NOT found
5. App creates new user:
   {
     username: "eric.alfanta@onsemi.com",
     email: "eric.alfanta@onsemi.com",
     roles: ["ROLE_USER"]
   }
6. JWT generated with ROLE_USER
7. User logged in as regular USER
```

**Step 2: Admin assigns admin role (Database update)**

```sql
-- Find the user
SELECT id FROM users WHERE email = 'eric.alfanta@onsemi.com';
-- Result: id = 5

-- Add ADMIN role
INSERT INTO user_roles (user_id, role) VALUES (5, 'ROLE_ADMIN');

-- User now has: ROLE_USER, ROLE_ADMIN
```

**Step 3: User logs in again**

```
1. eric.alfanta@onsemi.com clicks "Sign in with Microsoft"
2. Entra ID authenticates
3. Email returned: eric.alfanta@onsemi.com
4. App checks database: User FOUND
5. App loads existing user with roles: ["ROLE_USER", "ROLE_ADMIN"]
6. JWT generated with ROLE_USER and ROLE_ADMIN
7. User logged in as ADMIN
8. Admin-only features now accessible
```

---

## Role Hierarchy & Permissions

### Role Definitions

```
ROLE_USER (Regular User)
  └─ Permissions:
     • VIEW_DASHBOARD
     • CREATE_RESEND_REQUEST

ROLE_ADMIN (Admin)
  └─ Permissions:
     • VIEW_DASHBOARD
     • CREATE_RESEND_REQUEST
     • ADMIN_ACCESS
     • Bulk operations

ROLE_SUPER_ADMIN (Superadmin)
  └─ Permissions:
     • ALL (includes all ADMIN permissions plus:)
     • MANAGE_USERS
     • DATE_RANGE_OVERRIDE
     • VIEW_AUDIT_LOGS
     • System configuration
```

**Code Reference:**

```java
public List<String> getUserPermissions(AppUser user) {
    if (user.isSuperAdmin()) {
        return List.of(
            "VIEW_DASHBOARD",
            "CREATE_RESEND_REQUEST",
            "MANAGE_USERS",
            "DATE_RANGE_OVERRIDE",
            "VIEW_AUDIT_LOGS",
            "ADMIN_ACCESS"
        );
    }

    if (user.isAdmin()) {
        return List.of(
            "VIEW_DASHBOARD",
            "CREATE_RESEND_REQUEST",
            "ADMIN_ACCESS"
        );
    }

    // ROLE_USER
    return List.of(
        "VIEW_DASHBOARD",
        "CREATE_RESEND_REQUEST"
    );
}
```

---

## Security Considerations

### ✅ What's Secure

1. **No automatic admin assignment** - New SSO users are regular users by default
2. **Local control** - Admins are explicitly assigned in database
3. **No external sync** - Doesn't depend on Entra ID group changes
4. **Audit trail** - All role assignments logged
5. **JWT validation** - Tokens validated on every request

### ⚠️ What to Be Careful About

1. **Database consistency** - Keep user_roles table in sync with users
2. **Stale JWT** - Old tokens won't reflect recent role changes (until token refresh)
3. **Manual management** - Must remember to remove admin when user leaves company
4. **Multiple roles** - Users can have multiple roles (e.g., USER + ADMIN)

### 🔐 Best Practices

```java
// ✅ DO check auth before every sensitive operation
if (!roleService.isSuperAdmin()) {
    throw new AccessDeniedException("Superadmin required");
}

// ✅ DO log all role assignments
logger.info("User {} assigned ADMIN role by {}", email, admin.getUsername());

// ✅ DO verify role on each request (don't cache)
AppUser currentUser = roleService.getCurrentUser();

// ❌ DON'T cache roles in-memory without refresh
// ❌ DON'T trust client-side role indicators
// ❌ DON'T assign admin to every SSO user
```

---

## Debugging Admin Role Issues

### Problem: User Says They Should Be Admin But Aren't

**Troubleshooting:**

```sql
-- Step 1: Check if user exists in database
SELECT * FROM users WHERE email = 'user@onsemi.com';
-- If no result → User never logged in yet

-- Step 2: Check user's current roles
SELECT r.role
FROM user_roles r
JOIN users u ON r.user_id = u.id
WHERE u.email = 'user@onsemi.com';
-- Should show: ROLE_USER, ROLE_ADMIN (or ROLE_SUPER_ADMIN)

-- Step 3: Verify roles were added correctly
INSERT INTO user_roles (user_id, role) VALUES (
    (SELECT id FROM users WHERE email = 'user@onsemi.com'),
    'ROLE_ADMIN'
);

-- Step 4: User logs in again and JWT is refreshed
-- User now has admin role
```

---

## Summary: How It Works

| Step | What Happens                    | Where Checked                     |
| ---- | ------------------------------- | --------------------------------- |
| 1    | User logs in via SSO            | Entra ID                          |
| 2    | Email extracted from ID token   | `SsoAuthenticationSuccessHandler` |
| 3    | User looked up in database      | LOCAL `users` table               |
| 4    | If new → created with USER role | LOCAL database                    |
| 5    | If existing → roles loaded      | LOCAL `user_roles` table          |
| 6    | JWT issued with roles           | `JwtUtil`                         |
| 7    | JWT validated on requests       | `JwtAuthenticationFilter`         |
| 8    | Admin checks performed          | `RoleService.isAdmin()`           |
| 9    | Access granted/denied           | Spring Security                   |

---

## Next Steps

1. **Deploy Phase 1 (Basic SSO)** with this role-checking mechanism
2. **Manually assign admin roles** via SQL after users first login
3. **Monitor role assignments** to ensure correct users have admin access
4. **Consider Phase 2** (centralized Entra ID groups) only if manual management becomes burdensome

---

**Document Version:** 1.0  
**Last Updated:** [Current Date]  
**Related Docs:** SSO_IMPLEMENTATION_GUIDE.md, ENTRA_ID_CONFIGURATION.md, SSO_DEPLOYMENT_CHECKLIST.md
