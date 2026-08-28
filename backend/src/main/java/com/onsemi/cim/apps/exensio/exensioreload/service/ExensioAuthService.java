package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages per-schema Exensio API session tokens.
 *
 * <p>Acquires a Bearer token via {@code POST /v1/session/login} and caches it
 * per schema. Re-authenticates automatically when the token is missing or a
 * downstream call returns HTTP 401. Logs out all schemas on application shutdown.</p>
 *
 * <p>Uses its own {@link HttpClient} with {@code NEVER} redirect policy so that
 * 3xx responses (e.g. HTTP→HTTPS redirect or wrong base URL) are surfaced as
 * errors rather than silently followed.</p>
 *
 * <p>Thread-safe: a {@link ReentrantLock} prevents concurrent login storms.</p>
 *
 * <p>Implements {@link ExensioTokenProvider} so that callers depend on the interface
 * and require no changes when {@code AUTH_MODE} switches to OAUTH (Requirement 4.4).</p>
 */
@Service
@ConditionalOnProperty(name = "exensio.auth-mode", havingValue = "SESSION", matchIfMissing = true)
public class ExensioAuthService implements ExensioTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ExensioAuthService.class);

    private final ExensioProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final ReentrantLock loginLock = new ReentrantLock();
    private final ConcurrentHashMap<String, String> cachedTokens = new ConcurrentHashMap<>();

    public ExensioAuthService(ExensioProperties props, ObjectMapper objectMapper) {
        this.props = props;
        // NEVER_REDIRECT: a 3xx from the login endpoint means something is wrong with the
        // URL or server config (e.g. HTTP→HTTPS redirect, or a proxy login page).
        // We want to see and report the actual status code, not silently follow the redirect.
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a valid Bearer token for the given schema, logging in if necessary.
     *
     * @throws ExensioAuthException if login fails
     */
    public String getToken(String schema) {
        if (schema == null) return null;
        String token = cachedTokens.get(schema);
        if (token != null) return token;
        return login(schema);
    }

    /**
     * Invalidates the cached token for the given schema, forcing a fresh login on the next call.
     * Called by {@link ExensioClient} when it receives HTTP 401.
     */
    public void invalidateToken(String schema) {
        if (schema != null) cachedTokens.remove(schema);
    }

    /**
     * Performs a fresh login for the given schema and caches the resulting token.
     */
    public String login(String schema) {
        if (schema == null) throw new IllegalArgumentException("Schema must not be null");
        loginLock.lock();
        try {
            // Double-check after acquiring lock
            String cached = cachedTokens.get(schema);
            if (cached != null) return cached;

            String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/session/login";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("username", props.getUsername());
            body.put("password", props.getPassword());
            body.put("dbname", props.resolvedDbname());
            body.put("dbschema", schema);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String location = response.headers().firstValue("Location").orElse(null);
                String detail = location != null
                        ? "HTTP " + response.statusCode() + " → redirected to: " + location
                          + " (check exensio.qa-url/prod-url — may need HTTPS or different path)"
                        : "HTTP " + response.statusCode() + " → " + response.body();
                log.warn("Exensio login failed for schema={}: {}", schema, detail);
                throw new ExensioAuthException("Exensio login failed: " + detail);
            }

            JsonNode json = objectMapper.readTree(response.body());
            String token = json.path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw new ExensioAuthException("Exensio login response missing 'token' field for schema " + schema);
            }

            cachedTokens.put(schema, token);
            log.info("Exensio session established (env={}, schema={})", props.getEnv(), schema);
            return token;

        } catch (ExensioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new ExensioAuthException("Exensio login error on schema " + schema + ": " + e.getMessage(), e);
        } finally {
            loginLock.unlock();
        }
    }

    /**
     * Implements {@link ExensioTokenProvider#shutdown()}.
     * Delegates to {@link #logout()} which closes all Exensio sessions.
     */
    @Override
    public void shutdown() {
        logout();
    }

    @PreDestroy
    public void logout() {
        if (!props.isConfigured()) return;
        cachedTokens.forEach((schema, token) -> {
            try {
                String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/session/logout";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                log.info("Exensio session closed for schema={}", schema);
            } catch (Exception e) {
                log.debug("Exensio logout failed for schema={} (non-critical): {}", schema, e.getMessage());
            }
        });
        cachedTokens.clear();
    }

    public static class ExensioAuthException extends RuntimeException {
        public ExensioAuthException(String message) { super(message); }
        public ExensioAuthException(String message, Throwable cause) { super(message, cause); }
    }
}
