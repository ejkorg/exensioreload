package com.onsemi.cim.apps.exensio.exensioreload.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "reloader.sso")
public class SsoProperties {

    @Autowired
    private Environment env;

    private boolean enabled = false;
    private String clientId;
    private String clientSecret;
    private String tenantId;
    private String defaultRole = "USER";
    private Map<String, String> roleMappings = new HashMap<>();
    /** Name of the OIDC claim that carries IdP group memberships (e.g. "groups" for Azure AD). */
    private String groupClaimName = "groups";
    /**
     * Trusted cross-application callback URL prefixes. When an SSO initiation request includes
     * a {@code callbackApp} parameter whose value starts with one of these prefixes, the
     * success handler will redirect to that URL instead of the local /sso-callback.
     * Example: https://usaz15ls088:8080/xfcs-reloader/sso-callback
     */
    private java.util.List<String> trustedCallbackApps = new java.util.ArrayList<>();

    public boolean isEnabled() {
        if (env != null) {
            String ssoEnabledEnv = env.getProperty("ONSEMI_SSO_ENABLED");
            if ("false".equalsIgnoreCase(ssoEnabledEnv)) {
                return false;
            }
            if ("true".equalsIgnoreCase(ssoEnabledEnv)) {
                return true;
            }
        }

        if (enabled) {
            return true;
        }

        return clientId != null && !clientId.trim().isEmpty() &&
               clientSecret != null && !clientSecret.trim().isEmpty() &&
               tenantId != null && !tenantId.trim().isEmpty();
    }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDefaultRole() { return defaultRole; }
    public void setDefaultRole(String defaultRole) { this.defaultRole = defaultRole; }

    public Map<String, String> getRoleMappings() { return roleMappings; }
    public void setRoleMappings(Map<String, String> roleMappings) { this.roleMappings = roleMappings; }

    public String getGroupClaimName() { return groupClaimName; }
    public void setGroupClaimName(String groupClaimName) { this.groupClaimName = groupClaimName; }

    public java.util.List<String> getTrustedCallbackApps() { return trustedCallbackApps; }
    public void setTrustedCallbackApps(java.util.List<String> trustedCallbackApps) { this.trustedCallbackApps = trustedCallbackApps; }

    /**
     * Returns true if the given callbackApp URL is trusted (starts with one of the configured prefixes).
     */
    public boolean isTrustedCallbackApp(String callbackApp) {
        if (callbackApp == null || callbackApp.isBlank() || trustedCallbackApps == null) return false;
        return trustedCallbackApps.stream().anyMatch(prefix -> callbackApp.startsWith(prefix));
    }
}
