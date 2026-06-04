# ExensioReload — Entra ID SSO Credentials

**Status:** Application registered and credentials received from AD Team  
**Date Received:** June 4, 2026

---

## Application Registration Details

| Field                                | Value                                                                                                                    |
| :----------------------------------- | :----------------------------------------------------------------------------------------------------------------------- |
| **Application Name**                 | ExensioReload                                                                                                            |
| **Application ID (Client ID)**       | `6e0e3995-263c-4511-a5fb-8b3db9ce4ed2`                                                                                   |
| **Object ID**                        | `64dc67a4-a8d8-4462-be6b-074d4c9ea36b`                                                                                   |
| **Directory (Tenant) ID**            | `04e1674b-7af5-4d13-a082-64fc6e42384c`                                                                                   |
| **Client Secret**                    | `[REDACTED — see deployment notes below]`                                                                                |
| **Secret ID**                        | `0b837776-005e-4ca5-96fd-c799cea891ef`                                                                                   |
| **Federation Metadata Document**     | https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/federationmetadata/2007-06/federationmetadata.xml |
| **OpenID Connect Metadata Document** | https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/v2.0/.well-known/openid-configuration             |

---

## Environment Variables for Production Deployment

These credentials must be set on the production server (e.g., in `docker-compose.yml`, Kubernetes secrets, or system environment):

```bash
ONSEMI_SSO_ENABLED=true
ONSEMI_SSO_CLIENT_ID=6e0e3995-263c-4511-a5fb-8b3db9ce4ed2
ONSEMI_SSO_TENANT_ID=04e1674b-7af5-4d13-a082-64fc6e42384c
ONSEMI_SSO_CLIENT_SECRET=[CLIENT_SECRET_VALUE]
```

> **SECURITY NOTE:** The client secret value was provided separately by the AD team. Store it securely in a secrets manager (e.g., HashiCorp Vault, AWS Secrets Manager, or Kubernetes Secrets). Never commit it to version control.

---

## Configuration Checklist

- [ ] Environment variables set on production server
- [ ] Backend application started and SSO endpoints verified
- [ ] Test login with corporate credentials
- [ ] Verify group memberships are received in token
- [ ] Confirm role mapping works (`SUPER_ADMIN`, `ADMIN`, `USER`)
- [ ] Test logout and token refresh
- [ ] Confirm with AD team once testing complete

---

## Related Documentation

- [SSO Onboarding Details](./SSO_ONBOARDING_DETAILS.md) — Technical integration guide
- `backend/src/.../config/OAuth2ClientConfig.java` — OIDC client configuration
- `backend/src/.../config/SsoAuthenticationSuccessHandler.java` — JIT provisioning logic
