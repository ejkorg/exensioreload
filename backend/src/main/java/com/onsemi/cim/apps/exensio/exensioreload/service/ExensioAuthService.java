package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the Exensio API session token.
 *
 * <p>Acquires a Bearer token via {@code POST /v1/session/login} and caches it
 * in memory. Re-authenticates automatically when the token is missing or a
 * downstream call returns HTTP 401. Logs out on application shutdown.</p>
 *
 * <p>Thread-safe: a {@link ReentrantLock} prevents concurrent login storms.</p>
 */
@Service
public class ExensioAuthService {

    private static final Logger log = LoggerFactory.getLogger(ExensioAuthService.class);

    private final ExensioProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final ReentrantLock loginLock = new ReentrantLock();
    private volatile String cachedToken = null;

    public ExensioAuthService(ExensioProperties props,
                              HttpClient elasticsearchHttpClient,
                              ObjectMapper objectMapper) {
        this.props = props;
        // Reuse the shared HttpClient bean (same pattern as ElasticsearchLogService)
        this.httpClient = elasticsearchHttpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a valid Bearer token, logging in if necessary.
     *
     * @throws ExensioAuthException if login fails
     */
    public String getToken() {
        if (cachedToken != null) {
            return cachedToken;
        }
        return login();
    }

    /**
     * Invalidates the cached token and forces a fresh login on the next call.
     * Called by {@link ExensioClient} when it receives HTTP 401.
     */
    public void invalidateToken() {
        cachedToken = null;
    }

    /**
     * Performs a fresh login and caches the resulting token.
     */
    public String login() {
        loginLock.lock();
        try {
            // Double-check after acquiring lock
            if (cachedToken != null) {
                return cachedToken;
            }

            String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/session/login";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("username", props.getUsername());
            body.put("password", props.getPassword());
            body.put("dbname", props.resolvedDbname());
            body.put("dbschema", props.getDbschema());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Connection", "Close")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ExensioAuthException("Exensio login failed with HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String token = json.path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw new ExensioAuthException("Exensio login response missing 'token' field");
            }

            cachedToken = token;
            log.info("Exensio session established (env={})", props.getEnv());
            return token;

        } catch (ExensioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new ExensioAuthException("Exensio login error: " + e.getMessage(), e);
        } finally {
            loginLock.unlock();
        }
    }

    @PreDestroy
    public void logout() {
        if (cachedToken == null || !props.isConfigured()) return;
        try {
            String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/session/logout";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + cachedToken)
                    .header("Content-Type", "application/json")
                    .header("Connection", "Close")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("Exensio session closed");
        } catch (Exception e) {
            log.debug("Exensio logout failed (non-critical): {}", e.getMessage());
        } finally {
            cachedToken = null;
        }
    }

    public static class ExensioAuthException extends RuntimeException {
        public ExensioAuthException(String message) { super(message); }
        public ExensioAuthException(String message, Throwable cause) { super(message, cause); }
    }
}
