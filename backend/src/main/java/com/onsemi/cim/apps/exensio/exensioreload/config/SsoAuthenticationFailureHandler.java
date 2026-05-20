package com.onsemi.cim.apps.exensio.exensioreload.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Handles OIDC / OAuth2 authentication failures by distinguishing between
 * "silent SSO fallback" errors and genuine authentication failures.
 *
 * <ul>
 *   <li>{@code login_required} / {@code interaction_required} — Azure AD signals that no
 *       active SSO session exists. Redirect silently to {@code /login} so the user can
 *       choose their login method without seeing an error (Requirement 8.3).</li>
 *   <li>All other failures — redirect to {@code /login?reason=sso-error} so the
 *       {@code LoginComponent} can display an appropriate error message (Requirement 6.4).</li>
 * </ul>
 *
 * <p>Requirements: 6.4, 8.3
 */
@Component
public class SsoAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger logger = LoggerFactory.getLogger(SsoAuthenticationFailureHandler.class);

    /**
     * OAuth2 error codes that indicate the user simply has no active SSO session.
     * These are expected during the silent {@code prompt=none} flow and must not
     * surface as errors to the end user.
     */
    private static final Set<String> SILENT_FALLBACK_ERRORS = Set.of(
            "login_required",
            "interaction_required"
    );

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {

        String errorCode = extractOAuth2ErrorCode(exception);

        if (errorCode != null && SILENT_FALLBACK_ERRORS.contains(errorCode)) {
            // Silent SSO fallback — no active Azure AD session; show login page without error
            logger.debug("SSO silent check: no active session (error={}); redirecting to /login", errorCode);
            response.sendRedirect("/login");
        } else {
            // Genuine failure — log it and surface an error on the login page
            logger.warn("SSO authentication failed (error={}): {}", errorCode, exception.getMessage());
            response.sendRedirect("/login?reason=sso-error");
        }
    }

    /**
     * Extracts the OAuth2 error code from the exception if it is an
     * {@link OAuth2AuthenticationException}; returns {@code null} otherwise.
     */
    private String extractOAuth2ErrorCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2Ex) {
            OAuth2Error error = oauth2Ex.getError();
            if (error != null) {
                return error.getErrorCode();
            }
        }
        return null;
    }
}
