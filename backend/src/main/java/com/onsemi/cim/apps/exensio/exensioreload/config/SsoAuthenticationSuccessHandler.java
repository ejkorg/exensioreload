package com.onsemi.cim.apps.exensio.exensioreload.config;

import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.entity.RefreshToken;
import com.onsemi.cim.apps.exensio.exensioreload.service.RefreshTokenService;
import com.onsemi.cim.apps.exensio.exensioreload.service.SsoRoleMapper;
import com.onsemi.cim.apps.exensio.exensioreload.service.SsoUserProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Handles a successful OIDC authentication by bridging Spring Security's OAuth2 login
 * into the existing JWT + HTTP-only refresh-cookie session model.
 *
 * <p>Flow:
 * <ol>
 *   <li>Extract {@code email} claim from the {@link OidcUser}.</li>
 *   <li>Extract group claims (claim name from {@link SsoProperties#getGroupClaimName()}).</li>
 *   <li>Map IdP groups to local roles via {@link SsoRoleMapper}.</li>
 *   <li>JIT-provision or load the {@link AppUser} via {@link SsoUserProvisioningService}.</li>
 *   <li>Issue a JWT access token via {@link JwtUtil}.</li>
 *   <li>Create and persist a {@link RefreshToken}; set the HTTP-only cookie.</li>
 *   <li>Retrieve {@code returnUrl} from the HTTP session; sanitize it.</li>
 *   <li>Redirect to {@code /sso-callback?token=<JWT>&returnUrl=<safeUrl>}.</li>
 * </ol>
 *
 * <p>Requirements: 2.1, 2.2, 2.4, 2.5, 7.6
 */
@Component
public class SsoAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger logger = LoggerFactory.getLogger(SsoAuthenticationSuccessHandler.class);

    /** Session attribute key where {@code SsoController} stores the requested returnUrl. */
    public static final String SESSION_RETURN_URL_KEY = "sso_return_url";

    /** Default landing page when no valid returnUrl is available. */
    private static final String DEFAULT_RETURN_URL = "/exensioreload";

    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;
    private final SsoUserProvisioningService provisioningService;
    private final SsoRoleMapper roleMapper;
    private final SsoProperties ssoProperties;

    @Value("${reloader.refresh.cookie-secure:false}")
    private boolean refreshCookieSecure;

    @Value("${reloader.refresh.cookie-sameSite:None}")
    private String refreshCookieSameSite;

    @Value("${reloader.refresh.cookie-max-age:0}")
    private int refreshCookieMaxAge;

    public SsoAuthenticationSuccessHandler(JwtUtil jwtUtil,
                                           RefreshTokenService refreshTokenService,
                                           SsoUserProvisioningService provisioningService,
                                           SsoRoleMapper roleMapper,
                                           SsoProperties ssoProperties) {
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
        this.provisioningService = provisioningService;
        this.roleMapper = roleMapper;
        this.ssoProperties = ssoProperties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

            // 1. Extract email claim (Requirement 2.2)
            String email = oidcUser.getEmail();
            if (email == null || email.isBlank()) {
                logger.warn("SSO callback: OidcUser has no email claim; falling back to subject '{}'", oidcUser.getSubject());
                email = oidcUser.getSubject();
            }

            // 2. Extract group claims (configurable claim name, Requirement 4.1)
            Collection<String> idpGroups = extractGroupClaims(oidcUser);

            // 3. Map IdP groups → local roles (Requirements 4.1, 4.2)
            Set<String> localRoles = roleMapper.mapRoles(idpGroups);
            logger.debug("SSO callback: email='{}' idpGroups={} localRoles={}", email, idpGroups, localRoles);

            // 4. JIT provision or load user (Requirements 3.1–3.4)
            AppUser user = provisioningService.provisionOrLoad(email, localRoles);

            // 5. Issue JWT access token (Requirement 2.4)
            String accessToken = jwtUtil.generateToken(user.getUsername(), user.getRoles());

            // 6. Create and persist refresh token; set HTTP-only cookie (Requirement 2.4)
            RefreshToken rt = new RefreshToken();
            rt.setToken("refresh:" + System.currentTimeMillis());
            rt.setUsername(user.getUsername());
            rt.setExpiresAt(Instant.now().plusSeconds(60L * 60 * 24 * 7)); // 7 days
            refreshTokenService.save(rt);
            setRefreshCookie(response, rt.getToken());

            // 7. Retrieve and sanitize returnUrl (Requirement 7.6)
            String returnUrl = getReturnUrlFromSession(request);
            String safeReturnUrl = sanitizeReturnUrl(returnUrl);

            // 8. Redirect to /sso-callback (Requirement 2.5)
            String encodedToken = URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
            String encodedReturn = URLEncoder.encode(safeReturnUrl, StandardCharsets.UTF_8);
            String redirectUrl = "/sso-callback?token=" + encodedToken + "&returnUrl=" + encodedReturn;

            logger.info("SSO callback success: user='{}' redirecting to sso-callback", user.getUsername());
            response.sendRedirect(redirectUrl);

        } catch (Exception e) {
            logger.error("SSO callback: provisioning or token issuance failed", e);
            response.sendRedirect("/login?reason=sso-error");
        }
    }

    // --- helpers ---

    /**
     * Extracts IdP group claim values from the OIDC token.
     * The claim name is configurable via {@link SsoProperties#getGroupClaimName()}.
     */
    @SuppressWarnings("unchecked")
    private Collection<String> extractGroupClaims(OidcUser oidcUser) {
        String claimName = ssoProperties.getGroupClaimName();
        Object raw = oidcUser.getClaim(claimName);
        if (raw instanceof Collection<?>) {
            try {
                return (Collection<String>) raw;
            } catch (ClassCastException e) {
                logger.warn("SSO callback: group claim '{}' is not a Collection<String>; ignoring", claimName);
            }
        } else if (raw instanceof String) {
            return List.of((String) raw);
        }
        return Collections.emptyList();
    }

    /**
     * Reads the {@code sso_return_url} attribute stored in the HTTP session by {@code SsoController}.
     */
    private String getReturnUrlFromSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object val = session.getAttribute(SESSION_RETURN_URL_KEY);
        session.removeAttribute(SESSION_RETURN_URL_KEY);
        return val instanceof String ? (String) val : null;
    }

    /**
     * Validates that {@code returnUrl} is a relative internal path.
     * Mirrors the logic in the Angular {@code LoginComponent.getSafeReturnUrl}.
     *
     * <p>A URL is considered safe if it:
     * <ul>
     *   <li>starts with {@code /}</li>
     *   <li>does not start with {@code //} (protocol-relative)</li>
     *   <li>does not contain {@code ://} (absolute URL)</li>
     * </ul>
     *
     * <p>All other values fall back to {@value #DEFAULT_RETURN_URL} (Requirement 7.6).
     */
    static String sanitizeReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return DEFAULT_RETURN_URL;
        }
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(returnUrl, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return DEFAULT_RETURN_URL;
        }
        boolean startsWithSlash = decoded.startsWith("/");
        boolean isProtocolRelative = decoded.startsWith("//");
        boolean hasAbsoluteProtocol = decoded.contains("://");

        if (!startsWithSlash || isProtocolRelative || hasAbsoluteProtocol) {
            return DEFAULT_RETURN_URL;
        }
        return decoded;
    }

    /**
     * Writes the HTTP-only refresh token cookie, reusing the same attribute logic as
     * {@code AuthController} (Requirement 2.4).
     */
    private void setRefreshCookie(HttpServletResponse response, String tokenValue) {
        StringBuilder sc = new StringBuilder();
        sc.append("refresh_token=").append(tokenValue).append("; Path=/; HttpOnly");
        if (refreshCookieSecure) sc.append("; Secure");
        if (refreshCookieMaxAge > 0) sc.append("; Max-Age=").append(refreshCookieMaxAge);
        if (refreshCookieSameSite != null && !refreshCookieSameSite.isBlank()) {
            String s = refreshCookieSameSite.trim();
            if ("None".equalsIgnoreCase(s) && !refreshCookieSecure) {
                logger.warn("SsoAuthenticationSuccessHandler: SameSite=None configured but refreshCookieSecure=false; omitting SameSite attribute");
            } else {
                sc.append("; SameSite=").append(s);
            }
        }
        response.addHeader("Set-Cookie", sc.toString());
    }
}
