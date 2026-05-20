package com.onsemi.cim.apps.exensio.resender.config;

import com.onsemi.cim.apps.exensio.resender.controller.SsoController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom {@link OAuth2AuthorizationRequestResolver} that appends {@code prompt=none}
 * to the Azure AD authorization URL when the HTTP session contains the
 * {@link SsoController#SESSION_SILENT_FLAG} attribute.
 *
 * <p>This enables the silent OIDC check: Azure AD will authenticate the user silently
 * using an existing browser SSO session cookie, or return {@code error=login_required}
 * if no session exists (handled by {@link SsoAuthenticationFailureHandler}).
 *
 * <p>Requirements: 8.1, 8.4
 */
public class PromptNoneAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public PromptNoneAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest authRequest = delegate.resolve(request);
        return customize(request, authRequest);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest authRequest = delegate.resolve(request, clientRegistrationId);
        return customize(request, authRequest);
    }

    /**
     * If the session contains the silent flag, adds {@code prompt=none} to the
     * additional parameters and clears the flag so it only applies once.
     */
    private OAuth2AuthorizationRequest customize(HttpServletRequest request, OAuth2AuthorizationRequest authRequest) {
        if (authRequest == null) {
            return null;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            return authRequest;
        }

        Boolean silentFlag = (Boolean) session.getAttribute(SsoController.SESSION_SILENT_FLAG);
        if (!Boolean.TRUE.equals(silentFlag)) {
            return authRequest;
        }

        // Clear the flag — it must only apply to this single authorization request
        session.removeAttribute(SsoController.SESSION_SILENT_FLAG);

        // Append prompt=none to the additional parameters
        Map<String, Object> additionalParams = new LinkedHashMap<>(authRequest.getAdditionalParameters());
        additionalParams.put("prompt", "none");

        return OAuth2AuthorizationRequest.from(authRequest)
                .additionalParameters(additionalParams)
                .build();
    }
}
