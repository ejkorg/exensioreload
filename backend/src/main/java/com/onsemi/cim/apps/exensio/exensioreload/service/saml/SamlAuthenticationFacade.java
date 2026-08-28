package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioAuthService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Facade that implements tiered fallback SAML authentication strategy selection.
 *
 * <p>Encapsulates the logic of attempting multiple authentication strategies in order and
 * collecting errors from all failures. This keeps the main {@link ExensioSamlAuthService}
 * focused on token lifecycle management and caching, while this class handles the
 * authentication flow orchestration.</p>
 *
 * <p>Strategy order (tried in sequence):</p>
 * <ol>
 *   <li>{@link FormPostSamlStrategy} — Direct form-POST of service account credentials</li>
 *   <li>{@link WsFederationSamlStrategy} — WS-Federation headless SAML endpoint</li>
 *   <li>{@link SeleniumSamlStrategy} — Headless Chromium browser automation</li>
 * </ol>
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>Tries strategies in order</li>
 *   <li>On {@link UnsupportedOperationException}: silently skip to next strategy
 *       (strategy is not available in this runtime)</li>
 *   <li>On other exceptions: collect error message, log at WARN, try next strategy</li>
 *   <li>On success: log strategy name at DEBUG, return assertion immediately
 *       (short-circuit remaining strategies)</li>
 *   <li>On all-failure: throw {@link ExensioAuthService.ExensioAuthException} with
 *       message containing all three failure descriptions</li>
 * </ul>
 *
 * <p>Satisfies Requirements 9.1 (three-strategy fallback), 9.2 (short-circuit on success),
 * 9.3 (all-failure error aggregation), 9.5 (Selenium availability handling).</p>
 */
public class SamlAuthenticationFacade {

    private static final Logger logger = LoggerFactory.getLogger(SamlAuthenticationFacade.class);

    private final List<SamlAuthStrategy> strategies;

    /**
     * Creates a facade with the standard three-strategy fallback order.
     *
     * @param credentials the SAML credentials loaded from Secrets Manager
     */
    public SamlAuthenticationFacade(SamlCredentials credentials) {
        this.strategies = List.of(
            new FormPostSamlStrategy(credentials),
            new WsFederationSamlStrategy(credentials),
            new SeleniumSamlStrategy(credentials)
        );
    }

    /**
     * Attempts to acquire a SAML assertion using the tiered fallback strategy.
     *
     * <p>Tries strategies in order. On success, returns the assertion immediately without
     * trying remaining strategies. On all-failure, aggregates all error messages and throws
     * an {@link ExensioAuthService.ExensioAuthException}.</p>
     *
     * @return a base64-encoded SAML assertion from Azure AD
     * @throws ExensioAuthService.ExensioAuthException if all strategies fail
     */
    public String acquireSamlAssertion() {
        List<String> failureMessages = new ArrayList<>();

        for (SamlAuthStrategy strategy : strategies) {
            try {
                String assertion = strategy.authenticate();
                logger.debug("SAML assertion acquired via strategy: {}", strategy.name());
                return assertion;
            } catch (UnsupportedOperationException e) {
                logger.debug(
                    "Strategy {} skipped (unavailable): {}",
                    strategy.name(),
                    e.getMessage()
                );
                // Silently skip strategies that are unavailable (e.g., Selenium not on classpath)
            } catch (Exception e) {
                String failureDescription = strategy.name() + ": " + e.getMessage();
                failureMessages.add(failureDescription);
                logger.warn(
                    "SAML strategy {} failed, trying next strategy: {}",
                    strategy.name(),
                    e.getMessage()
                );
            }
        }

        // All strategies failed — throw with aggregated error messages
        String aggregatedErrors = String.join("; ", failureMessages);
        throw new ExensioAuthService.ExensioAuthException(
            "All SAML authentication strategies failed — " + aggregatedErrors
        );
    }
}
