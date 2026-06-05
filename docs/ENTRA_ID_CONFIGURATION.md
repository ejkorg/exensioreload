# Entra ID Configuration - ExensioReload SSO

**Status:** Configured  
**Application Name:** ExensioReload  
**Date Configured:** [Current Date]  
**Configured By:** Jayasree (AD/InfoSec)

---

## ⚠️ SECURITY WARNING

This document contains **sensitive credentials**.

**DO NOT:**

- ❌ Commit to version control
- ❌ Share via email or chat
- ❌ Store in public repositories
- ❌ Include in logs or error messages

**MUST:**

- ✅ Store in secure vault (Azure Key Vault, HashiCorp Vault, etc.)
- ✅ Rotate secrets regularly
- ✅ Audit access logs
- ✅ Use environment variables in production
- ✅ Restrict file permissions (chmod 600)

---

## Application Registration Details

### Basic Information

| Property                       | Value                                  |
| ------------------------------ | -------------------------------------- |
| **Application Name**           | ExensioReload                          |
| **Application ID (Client ID)** | `6e0e3995-263c-4511-a5fb-8b3db9ce4ed2` |
| **Object ID**                  | `64dc67a4-a8d8-4462-be6b-074d4c9ea36b` |
| **Directory (Tenant) ID**      | `04e1674b-7af5-4d13-a082-64fc6e42384c` |

### Client Secret

| Property         | Value                                     |
| ---------------- | ----------------------------------------- |
| **Secret Value** | `.LJ8Q~94VRAnn3tgCcAS8e2K-aPiOU3nG.fYva7` |
| **Secret ID**    | `0b837776-005e-4ca5-96fd-c799cea891ef`    |
| **Expiration**   | [To be confirmed with AD]                 |

---

## Configuration Files

### 1. Application Properties (application.yml)

```yaml
# =====================================================
# Entra ID / Azure AD OAuth2 Configuration
# =====================================================
spring:
  security:
    oauth2:
      client:
        registration:
          azure:
            # Application credentials
            client-id: 6e0e3995-263c-4511-a5fb-8b3db9ce4ed2
            client-secret: ${AZURE_CLIENT_SECRET} # Store in environment variable
            client-name: ExensioReload

            # Authentication provider
            provider: azure

            # OAuth scopes - BASIC SSO ONLY
            # (No GroupMember.Read.All - local admin management)
            scope: openid,profile,email

            # Authorization code flow
            authorization-grant-type: authorization_code

            # Redirect URI (must match Entra ID app registration)
            redirect-uri: '{baseUrl}/login/oauth2/code/azure'

        provider:
          azure:
            # Token endpoint
            token-uri: https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/oauth2/v2.0/token

            # Authorization endpoint
            authorization-uri: https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/oauth2/v2.0/authorize

            # User info endpoint
            user-info-uri: https://graph.microsoft.com/oidc/userinfo

            # JWK Set URI for token validation
            jwk-set-uri: https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/discovery/v2.0/keys

            # User name attribute
            user-name-attribute: email
```

### 2. Environment Variables (.env / .env.production)

```bash
# =====================================================
# ENTRA ID CONFIGURATION - PRODUCTION
# =====================================================

# Application credentials
AZURE_TENANT_ID=04e1674b-7af5-4d13-a082-64fc6e42384c
AZURE_CLIENT_ID=6e0e3995-263c-4511-a5fb-8b3db9ce4ed2
AZURE_CLIENT_SECRET=.LJ8Q~94VRAnn3tgCcAS8e2K-aPiOU3nG.fYva7

# Application URLs
AZURE_REDIRECT_URI=https://exensio-app.onsemi.com/login/oauth2/code/azure
AZURE_LOGOUT_URI=https://exensio-app.onsemi.com/

# Security settings
AZURE_AUTHORITY=https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c
AZURE_GRAPH_API_ENDPOINT=https://graph.microsoft.com/v1.0

# Admin management (LOCAL DATABASE)
ADMIN_ROLE_SOURCE=LOCAL_DATABASE  # Do NOT use ENTRA_AD_GROUPS
```

### 3. Java Configuration Class

```java
package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Entra ID OAuth2 Configuration
 *
 * Configuration for Microsoft Entra ID (Azure AD) SSO integration.
 * Users authenticate via Microsoft corporate accounts.
 *
 * Admin/Superadmin roles are managed LOCALLY in application database.
 * No Entra ID security groups are used.
 *
 * Application Details:
 * - Name: ExensioReload
 * - Client ID: 6e0e3995-263c-4511-a5fb-8b3db9ce4ed2
 * - Tenant ID: 04e1674b-7af5-4d13-a082-64fc6e42384c
 */
@Configuration
public class EntraIdOAuth2Config {

    @Value("${spring.security.oauth2.client.registration.azure.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.azure.client-secret}")
    private String clientSecret;

    @Value("${spring.security.oauth2.client.registration.azure.redirect-uri}")
    private String redirectUri;

    /**
     * OAuth2 Authorized Client Manager bean.
     * Handles token management and refresh.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository) {

        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()  // Authorization code flow
                        .refreshToken()       // Token refresh
                        .build();

        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(
                        clientRegistrationRepository,
                        authorizedClientRepository);

        manager.setAuthorizedClientProvider(authorizedClientProvider);

        return manager;
    }

    /**
     * Log configuration for verification (remove in production if verbose logging not needed)
     */
    public void logConfiguration() {
        // DO NOT log actual secrets
        System.out.println("=== ENTRA ID CONFIGURATION ===");
        System.out.println("Client ID: " + clientId);
        System.out.println("Tenant: 04e1674b-7af5-4d13-a082-64fc6e42384c");
        System.out.println("Redirect URI: " + redirectUri);
        System.out.println("Admin Management: LOCAL DATABASE");
        System.out.println("Security Groups: DISABLED");
        System.out.println("=============================");
    }
}
```

### 4. Spring Security Configuration

```java
package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security Configuration with Entra ID OAuth2
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeRequests()
                .antMatchers("/").permitAll()
                .antMatchers("/login").permitAll()
                .antMatchers("/health").permitAll()
                .antMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
                .and()
            .oauth2Login()
                .loginPage("/login")
                .defaultSuccessUrl("/dashboard")
                .failureUrl("/login?error=true")
                .and()
            .logout()
                .logoutUrl("/logout")
                .logoutSuccessUrl("https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/oauth2/v2.0/logout");

        return http.build();
    }
}
```

---

## Setting Entra ID Redirect URIs

### In Entra ID Portal Configuration

The following redirect URIs must be configured in the Entra ID application registration:

```
Development:
  http://localhost:8080/login/oauth2/code/azure

Staging:
  https://exensio-staging.onsemi.com/login/oauth2/code/azure

Production:
  https://exensio-app.onsemi.com/login/oauth2/code/azure
```

**Note:** These URIs must exactly match what's configured in your application properties.

---

## Authentication Flow

### SSO Login Flow

```
1. User visits application
   └─ Redirect to /login/oauth2/code/azure

2. Spring Security redirects to Entra ID
   ├─ Client ID: 6e0e3995-263c-4511-a5fb-8b3db9ce4ed2
   ├─ Scope: openid profile email
   └─ Redirect URI: https://exensio-app.onsemi.com/login/oauth2/code/azure

3. User authenticates with Microsoft credentials
   └─ Entra ID verifies identity

4. Entra ID redirects back to app with authorization code
   └─ URL: https://exensio-app.onsemi.com/login/oauth2/code/azure?code=xxxx

5. Spring Security exchanges code for tokens
   ├─ Client ID: 6e0e3995-263c-4511-a5fb-8b3db9ce4ed2
   ├─ Client Secret: (securely stored)
   └─ Authorization Code: xxxx

6. Entra ID returns ID token + Access token
   ├─ ID Token contains: {email, name, id, ...}
   └─ Access Token for Graph API (if needed)

7. Spring Security creates session
   └─ User logged in

8. Application checks LOCAL database
   ├─ Query: Is user@onsemi.com an admin?
   ├─ Local DB response: YES/NO
   └─ Assign role: ADMIN or USER

9. User redirected to dashboard
   └─ Logged in with assigned role
```

---

## User Authentication Details

### What's Retrieved from Entra ID

When a user logs in, the ID token contains:

```json
{
  "aud": "6e0e3995-263c-4511-a5fb-8b3db9ce4ed2",
  "iss": "https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/v2.0",
  "iat": 1234567890,
  "exp": 1234571490,
  "email": "john.doe@onsemi.com",
  "email_verified": true,
  "name": "John Doe",
  "oid": "user-object-id-in-entra-id",
  "sub": "user-subject",
  "tid": "04e1674b-7af5-4d13-a082-64fc6e42384c"
}
```

### Role Assignment (Local Database)

```java
@Service
public class AuthenticationService {

    @Autowired
    private AdminUserRepository adminUserRepository;

    public String getUserRole(String userEmail) {
        // Check LOCAL database only
        Optional<AdminUser> admin = adminUserRepository.findByEmail(userEmail);

        if (admin.isPresent()) {
            return admin.get().getRole();  // "SUPERADMIN" or "ADMIN"
        }

        return "USER";  // Default role
    }
}
```

---

## Database Setup (Local Admin Management)

### SQL Schema

```sql
-- Create admin users table
CREATE TABLE admin_users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) UNIQUE NOT NULL,
    role VARCHAR(50) NOT NULL CHECK (role IN ('SUPERADMIN', 'ADMIN')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Initial admin users
INSERT INTO admin_users (email, role, updated_by) VALUES
    ('junifferallan.garcia@onsemi.com', 'SUPERADMIN', 'system'),
    ('eric.alfanta@onsemi.com', 'ADMIN', 'system'),
    ('jovenk.sorallo@onsemi.com', 'ADMIN', 'system'),
    ('glorymaae.llego@onsemi.com', 'ADMIN', 'system'),
    ('gilbert.miole@onsemi.com', 'ADMIN', 'system');

-- Check configuration
SELECT email, role FROM admin_users ORDER BY created_at;
```

### JPA Entity

```java
@Entity
@Table(name = "admin_users")
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AdminRole role;  // SUPERADMIN, ADMIN

    @CreationTimestamp
    private Instant createdAt;

    @Column(length = 255)
    private String updatedBy;

    @UpdateTimestamp
    private Instant updatedAt;

    // Getters and setters
    public enum AdminRole {
        SUPERADMIN("Full system access"),
        ADMIN("Administrative operations");

        private final String description;

        AdminRole(String description) {
            this.description = description;
        }
    }
}
```

### Repository

```java
@Repository
public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {
    Optional<AdminUser> findByEmail(String email);
    List<AdminUser> findByRole(AdminUser.AdminRole role);
}
```

---

## Verification Checklist

### Pre-Deployment

- [ ] Client ID configured: `6e0e3995-263c-4511-a5fb-8b3db9ce4ed2`
- [ ] Client Secret stored in secure vault (NOT in code)
- [ ] Tenant ID configured: `04e1674b-7af5-4d13-a082-64fc6e42384c`
- [ ] Redirect URIs added to Entra ID app registration
- [ ] OAuth scopes set to: `openid profile email` ONLY
- [ ] Database table `admin_users` created
- [ ] Initial admin users inserted
- [ ] Spring Security configured for OAuth2
- [ ] Environment variables set correctly

### Testing

- [ ] User can click "Sign in with Microsoft"
- [ ] User redirected to Entra ID login
- [ ] User enters credentials
- [ ] User redirected back to app
- [ ] User logged in successfully
- [ ] Admin user can access admin features
- [ ] Regular user cannot access admin features
- [ ] No "Approval required" dialog appears
- [ ] Logout works correctly

### Logs to Check

```
Looking for (should see):
✅ "OAuth2 Login successful"
✅ "User authenticated: john.doe@onsemi.com"
✅ "Role assigned: ADMIN"
✅ "User redirected to /dashboard"

NOT looking for (should NOT see):
❌ "GroupMember.Read.All"
❌ "Microsoft Graph API error"
❌ "Approval required"
❌ "Security groups"
```

---

## Secrets Management

### DO NOT

```bash
# ❌ DON'T do this
export AZURE_CLIENT_SECRET=".LJ8Q~94VRAnn3tgCcAS8e2K-aPiOU3nG.fYva7"
java -jar app.jar

# ❌ DON'T do this
client-secret: .LJ8Q~94VRAnn3tgCcAS8e2K-aPiOU3nG.fYva7  # In application.yml
```

### DO

```bash
# ✅ DO this - Use environment variables
export AZURE_CLIENT_SECRET=".LJ8Q~94VRAnn3tgCcAS8e2K-aPiOU3nG.fYva7"
java -jar app.jar

# ✅ DO this - Use secure vault
# Store in Azure Key Vault, HashiCorp Vault, or similar

# ✅ DO this - Reference in config
client-secret: ${AZURE_CLIENT_SECRET}
```

### Docker Setup Example

```dockerfile
FROM openjdk:11-slim

COPY app.jar /app.jar

# Secrets passed at runtime, NOT in image
ENV AZURE_TENANT_ID=04e1674b-7af5-4d13-a082-64fc6e42384c
ENV AZURE_CLIENT_ID=6e0e3995-263c-4511-a5fb-8b3db9ce4ed2
ENV AZURE_CLIENT_SECRET=${AZURE_CLIENT_SECRET}  # Passed at container start

ENTRYPOINT ["java", "-jar", "/app.jar"]
```

---

## Troubleshooting

### Issue: "Approval required" dialog appears

**Cause:** Application is requesting GroupMember.Read.All permission  
**Solution:** Verify scopes in application.yml contain ONLY `openid,profile,email`

```yaml
# Correct:
scope: openid,profile,email

# Wrong:
scope: openid,profile,email,https://graph.microsoft.com/.default
```

---

### Issue: Users get "USER" role when should be "ADMIN"

**Cause:** Email not in admin_users table or case mismatch  
**Solution:** Check database

```sql
-- Check admin table
SELECT * FROM admin_users WHERE LOWER(email) = 'john.doe@onsemi.com';

-- Add missing admin
INSERT INTO admin_users (email, role, updated_by)
VALUES ('john.doe@onsemi.com', 'ADMIN', 'migration');
```

---

### Issue: "Invalid client secret"

**Cause:** Secret value incorrect or expired  
**Solution:**

1. Verify secret value matches exactly: `.LJ8Q~94VRAnn3tgCcAS8e2K-aPiOU3nG.fYva7`
2. Check expiration date with Jayasree
3. If expired, request new secret from AD/InfoSec

---

## Important Notes

### Admin Role Management

- ✅ Admin roles managed in LOCAL database only
- ✅ No Entra ID security groups used
- ✅ Full application control over role assignment
- ✅ Easy to add/remove admins: Update database

### Authentication vs Authorization

- **Authentication:** Via Entra ID SSO ✅
- **Authorization:** Via local database ✅
- No dependency on Entra ID group membership

### Future Considerations

If the organization later decides to use Entra ID security groups:

1. Create security groups in Entra ID
2. Request `GroupMember.Read.All` permission approval
3. Enable groups claim on ID token
4. Update application to read groups from token
5. Retire local admin database

But this is **not planned** at this time.

---

## Support & References

### Microsoft Documentation

- [OAuth 2.0 Authorization Code Flow](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-auth-code-flow)
- [OpenID Connect Protocol](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-protocols-oidc)
- [Redirect URI restrictions](https://learn.microsoft.com/en-us/azure/active-directory/develop/reply-url)

### Application Contacts

- **Entra ID Setup:** Jayasree (AD/InfoSec)
- **Application Development:** [Your Development Team]

### Secret Rotation Schedule

- **Initial Secret Expiration:** [To be confirmed with AD]
- **Rotation Frequency:** Annual (recommended) or as per org policy
- **Next Review Date:** [Set reminder]

---

**Configuration Status:** ✅ Ready for Implementation  
**Last Updated:** [Current Date]  
**Version:** 1.0  
**Next Review:** After successful production deployment
