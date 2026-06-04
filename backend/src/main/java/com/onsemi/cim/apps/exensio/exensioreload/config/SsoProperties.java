package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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
}
