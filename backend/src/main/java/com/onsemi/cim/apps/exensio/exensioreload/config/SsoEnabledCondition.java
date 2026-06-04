package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition to enable SSO automatically when the SSO Client ID, Client Secret,
 * and Tenant ID environment variables/properties are configured, even if
 * {@code reloader.sso.enabled} (or {@code ONSEMI_SSO_ENABLED}) is not explicitly set to true.
 */
public class SsoEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // First check ONSEMI_SSO_ENABLED environment variable explicitly
        String ssoEnabledEnv = context.getEnvironment().getProperty("ONSEMI_SSO_ENABLED");
        if ("false".equalsIgnoreCase(ssoEnabledEnv)) {
            return false;
        }
        if ("true".equalsIgnoreCase(ssoEnabledEnv)) {
            return true;
        }

        // Also check reloader.sso.enabled property
        String ssoEnabledProp = context.getEnvironment().getProperty("reloader.sso.enabled");
        if ("false".equalsIgnoreCase(ssoEnabledProp)) {
            // Since application.yml defaults reloader.sso.enabled to ${ONSEMI_SSO_ENABLED:false},
            // it will resolve to "false" when ONSEMI_SSO_ENABLED is not set.
            // Therefore, we only treat it as explicitly disabled if ONSEMI_SSO_ENABLED was explicitly set to "false".
            if ("false".equalsIgnoreCase(ssoEnabledEnv)) {
                return false;
            }
        } else if ("true".equalsIgnoreCase(ssoEnabledProp)) {
            return true;
        }

        // Auto-enable if client-id, client-secret, and tenant-id are all configured
        String clientId = context.getEnvironment().getProperty("reloader.sso.client-id");
        String clientSecret = context.getEnvironment().getProperty("reloader.sso.client-secret");
        String tenantId = context.getEnvironment().getProperty("reloader.sso.tenant-id");

        return clientId != null && !clientId.trim().isEmpty() &&
               clientSecret != null && !clientSecret.trim().isEmpty() &&
               tenantId != null && !tenantId.trim().isEmpty();
    }
}
