package com.onsemi.cim.apps.exensio.resender.service;

import com.onsemi.cim.apps.exensio.resender.config.SsoProperties;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author fg8n8x
 */

/**
 * Maps IdP group claim strings to local application roles using the configured
 * {@link SsoProperties#getRoleMappings()} table.
 *
 * <p>The result always contains at least {@link SsoProperties#getDefaultRole()},
 * satisfying Requirements 4.1 and 4.2.
 */
@Component
public class SsoRoleMapper {

    private final SsoProperties ssoProperties;

    public SsoRoleMapper(SsoProperties ssoProperties) {
        this.ssoProperties = ssoProperties;
    }

    /**
     * Converts a collection of IdP group claim strings into a set of local role names.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Start with a result set containing {@code defaultRole}.</li>
     *   <li>For each IdP group in {@code idpGroups}, look it up in {@code roleMappings}.
     *       If a mapping exists, add the mapped local role to the result.</li>
     * </ol>
     *
     * @param idpGroups the IdP group/role claim values from the OIDC token (may be null or empty)
     * @return a non-empty {@link Set} of local role names, always containing {@code defaultRole}
     */
    public Set<String> mapRoles(Collection<String> idpGroups) {
        Set<String> result = new HashSet<>();
        // Requirement 4.2: always include the default role
        result.add(ssoProperties.getDefaultRole());

        if (idpGroups == null || idpGroups.isEmpty()) {
            return result;
        }

        Map<String, String> mappings = ssoProperties.getRoleMappings();
        // Requirement 4.1 & 4.3: apply configured mappings at every SSO login
        for (String group : idpGroups) {
            String localRole = mappings.get(group);
            if (localRole != null) {
                result.add(localRole);
            }
        }

        return result;
    }
}

