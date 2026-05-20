package com.onsemi.cim.apps.exensio.resender.controller;

import com.onsemi.cim.apps.exensio.resender.config.SsoAuthenticationSuccessHandler;
import com.onsemi.cim.apps.exensio.resender.config.SsoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;

/**
 * Handles SSO initiation endpoints.
 *
 * <ul>
 *   <li>{@code GET /api/auth/sso/initiate?returnUrl=...} — sanitizes returnUrl, stores it in the
 *       HTTP session, then redirects to {@code /oauth2/authorization/onsemi} for interactive login
 *       (Requirements 1.2, 1.3, 7.3).</li>
 *   <li>{@code GET /api/auth/sso/silent?returnUrl=...} — same as initiate but appends
 *       {@code &prompt=none} to the Azure AD authorization URL via a session flag, enabling the
 *       silent OIDC check (Requirements 8.1, 8.4).</li>
 * </ul>
 *
 * <p>Both endpoints return HTTP 404 when {@code reloader.sso.enabled=false} (Requirement 6.3).
 */
@RestController
@RequestMapping("/api/auth/sso")
public class SsoController {

    private static final Logger logger = LoggerFactory.getLogger(SsoController.class);

    /**
     * Session attribute key used by {@link com.onsemi.cim.apps.exensio.resender.config.PromptNoneAuthorizationRequestResolver}
     * to detect that the current authorization request should include {@code prompt=none}.
     */
    public static final String SESSION_SILENT_FLAG = "sso_silent_prompt_none";

    /** Default landing page when no valid returnUrl is available. */
    private static final String DEFAULT_RETURN_URL = "/resender";

    private final SsoProperties ssoProperties;

    public SsoController(SsoProperties ssoProperties) {
        this.ssoProperties = ssoProperties;
    }

    /**
     * Interactive SSO initiation.
     *
     * <p>Sanitizes {@code returnUrl}, stores it in the HTTP session so
     * {@link SsoAuthenticationSuccessHandler} can retrieve it after the OIDC callback,
     * then redirects to Spring Security's OAuth2 authorization endpoint.
     *
     * <p>Requirements: 1.2, 1.3, 7.3
     */
    @GetMapping("/initiate")
    public void initiate(@RequestParam(value = "returnUrl", required = false) String returnUrl,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        if (!ssoProperties.isEnabled()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        String safeReturnUrl = sanitizeReturnUrl(returnUrl);
        storeReturnUrl(request, safeReturnUrl);

        logger.debug("SSO initiate: returnUrl='{}' -> safeReturnUrl='{}'", returnUrl, safeReturnUrl);
        response.sendRedirect("/oauth2/authorization/onsemi");
    }

    /**
     * Silent SSO check (prompt=none).
     *
     * <p>Sets a session flag that instructs
     * {@link com.onsemi.cim.apps.exensio.resender.config.PromptNoneAuthorizationRequestResolver}
     * to append {@code &prompt=none} to the Azure AD authorization URL, enabling a silent
     * authentication attempt using the existing browser SSO session.
     *
     * <p>If Azure AD has no active session it returns {@code error=login_required} or
     * {@code error=interaction_required}, which {@link com.onsemi.cim.apps.exensio.resender.config.SsoAuthenticationFailureHandler}
     * handles by redirecting to {@code /login} without showing an error.
     *
     * <p>Requirements: 8.1, 8.4
     */
    @GetMapping("/silent")
    public void silent(@RequestParam(value = "returnUrl", required = false) String returnUrl,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        if (!ssoProperties.isEnabled()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        String safeReturnUrl = sanitizeReturnUrl(returnUrl);
        HttpSession session = request.getSession(true);
        session.setAttribute(SsoAuthenticationSuccessHandler.SESSION_RETURN_URL_KEY, safeReturnUrl);
        // Signal the custom resolver to add prompt=none
        session.setAttribute(SESSION_SILENT_FLAG, Boolean.TRUE);

        logger.debug("SSO silent: returnUrl='{}' -> safeReturnUrl='{}'", returnUrl, safeReturnUrl);
        response.sendRedirect("/oauth2/authorization/onsemi");
    }

    // --- helpers ---

    /**
     * Stores the sanitized returnUrl in the HTTP session for retrieval by
     * {@link SsoAuthenticationSuccessHandler} after the OIDC callback.
     */
    private void storeReturnUrl(HttpServletRequest request, String safeReturnUrl) {
        HttpSession session = request.getSession(true);
        session.setAttribute(SsoAuthenticationSuccessHandler.SESSION_RETURN_URL_KEY, safeReturnUrl);
    }

    /**
     * Validates that {@code returnUrl} is a safe relative internal path.
     * Mirrors the logic in {@link SsoAuthenticationSuccessHandler#sanitizeReturnUrl}.
     *
     * <p>A URL is safe if it starts with {@code /}, does not start with {@code //},
     * and does not contain {@code ://}. All other values fall back to {@value #DEFAULT_RETURN_URL}.
     *
     * <p>Requirement 7.6
     */
    static String sanitizeReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return DEFAULT_RETURN_URL;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(returnUrl, StandardCharsets.UTF_8);
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
}
