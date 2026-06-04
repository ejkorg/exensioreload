package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * Registers the OAuth2 ClientRegistrationRepository only when SSO is enabled.
 * This prevents Spring Boot from eagerly resolving the OIDC issuer URI at startup
 * when SSO is disabled (which would fail with no tenant ID configured).
 */
@Configuration
@Conditional(SsoEnabledCondition.class)
public class OAuth2ClientConfig {

    private final SsoProperties ssoProperties;

    public OAuth2ClientConfig(SsoProperties ssoProperties) {
        this.ssoProperties = ssoProperties;
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        ClientRegistration registration = ClientRegistration
                .withRegistrationId("onsemi")
                .clientId(ssoProperties.getClientId())
                .clientSecret(ssoProperties.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/onsemi")
                .scope("openid", "profile", "email", "GroupMember.Read.All")
                .authorizationUri("https://login.microsoftonline.com/" + ssoProperties.getTenantId() + "/oauth2/v2.0/authorize")
                .tokenUri("https://login.microsoftonline.com/" + ssoProperties.getTenantId() + "/oauth2/v2.0/token")
                .jwkSetUri("https://login.microsoftonline.com/" + ssoProperties.getTenantId() + "/discovery/v2.0/keys")
                .userInfoUri("https://graph.microsoft.com/oidc/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("onsemi")
                .build();

        return new InMemoryClientRegistrationRepository(registration);
    }
}
