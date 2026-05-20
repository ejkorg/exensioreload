# Auth and Admin UI Plan

## Objective
- Fix JWT authentication so `/exensioreload/api/auth/me` returns user info (200) after login/refresh.
- Expose the existing Admin user-management UI and verify admin APIs.

## Current state
- Login and refresh flows succeed (cookies set and rotated), but `/auth/me` returns 401.
- `JwtAuthenticationFilter` was added to `SecurityConfig` but backend still rejects tokens when presented.
- Nginx now forwards `Authorization`, and SPA is served under `/exensioreload`.
- `UserAdminController` and `AdminDashboardComponent` exist but `admin` routes are redirected to `exensioreload`.

## High-level plan (steps)

1. Confirm backend JWT validation behavior
   - Tail backend logs while reproducing login -> refresh -> `/auth/me`.
   - Run `backend/tests/auth_test.sh <username> <password>` and capture token + backend logs.
   - Look for Jwt validation errors or AuthenticationExceptions in logs.
   - If logs show no JWT attempts, ensure the `JwtAuthenticationFilter` is being registered (see `SecurityConfig`).

2. Verify `reloader.jwt.secret` consistency
   - Inspect `application-*.yml` files for `reloader.jwt.secret` across active profiles.
   - Ensure the secret used to sign tokens at login is identical to the secret used by `JwtUtil` to validate.
   - If secrets differ or are malformed (base64 vs raw), update config and restart backend.

3. Restart and validate SecurityConfig changes
   - Rebuild/restart the Spring Boot app so `SecurityConfig` changes take effect.
   - Confirm the `JwtAuthenticationFilter` is present by adding a temporary log in the filter or checking startup logs.

4. Confirm token acceptance locally
   - Use `curl` against backend directly with `Authorization: Bearer <accessToken>` to `/exensioreload/api/auth/me`.
   - If still 401, decode the JWT locally (jwt.io or `openssl`/`jq`) and verify claims and signature.

5. Nginx / proxy checks
   - Confirm nginx includes `proxy_set_header Authorization $http_authorization;` and `proxy_pass` mapping.
   - Verify `Set-Cookie` attributes are preserved (SameSite=None requires `Secure` over TLS).

6. Add Admin UI routes + nav
   - Update `frontend/src/app/app.routes.ts` to map `/admin` and `/admin/users` to `AdminDashboardComponent`.
   - Update `frontend/src/app/app.ts` `navItems` to include `{ label: 'Users', icon: 'people', path: '/admin', admin: true }`.
   - Build and open SPA to verify admin-only visibility for users with `ROLE_ADMIN`/`ROLE_SUPER_ADMIN`.

7. Functional tests
   - Use the Admin endpoints to list users: GET `/exensioreload/api/admin/users`.
   - Use POST `/exensioreload/api/admin/users/seed-admin` or `/create-test-admin/{username}` to ensure an admin exists.
   - From SPA, log in as admin and verify the Users page loads and role toggles persist.

8. Deployment notes
   - For SameSite=None cookies ensure TLS is used (cookie Secure=true). If using nginx proxy in front, terminate TLS there.
   - Ensure nginx sets `proxy_set_header X-Forwarded-Proto $scheme;` and forwards `Host` and `X-Forwarded-*` headers.
   - Monitor logs for cookie warnings (SameSite) and adjust `reloader.refresh.cookie-secure` in `application-*.yml` as needed.

## Quick verification commands

- Rebuild backend:

```powershell
cd backend
mvn -DskipTests package
# restart the service (platform-specific)
```

- Run auth test script (example):

```bash
backend/tests/auth_test.sh admin admin123
```

- Tail logs while testing:

```bash
# on the backend host
tail -n 200 logs/exensioreload.log
```

- Curl `/auth/me` directly with token:

```bash
curl -v -H "Authorization: Bearer <accessToken>" http://127.0.0.1:8004/exensioreload/api/auth/me
```

## If token validation fails (common causes)
- Secret mismatch between issuer and validator: synchronize `reloader.jwt.secret`.
- Token signed with different algorithm: ensure `JwtUtil` uses HS256 and tokens are created likewise.
- Token claims missing expected fields (e.g., `sub` or `exp`): ensure issuer sets required claims.
- Filter not registered: ensure `http.addFilterBefore(new JwtAuthenticationFilter(jwtUtil), BasicAuthenticationFilter.class);` is present and backend restarted.

## Next actions I can take for you
- Inspect backend logs after you run `backend/tests/auth_test.sh` and paste them here.
- Apply the frontend patches to expose the admin routes and nav item and verify locally.
- Check `application-*.yml` for `reloader.jwt.secret` and suggest a fix.

---
Created by automated plan generator. Refer to `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/SecurityConfig.java` for the JWT filter registration.
