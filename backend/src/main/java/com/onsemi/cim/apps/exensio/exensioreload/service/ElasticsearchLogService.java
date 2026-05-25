package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Queries the CP Elasticsearch index to determine the enrichment outcome for a given file.
 * Uses the JDK built-in {@link HttpClient} and the ES REST API directly — no extra client libs.
 *
 * <p>Requirements: 2.3, 2.4, 2.5, 2.6, 3.1, 3.3, 4.1, 5.3</p>
 */
@Service
public class ElasticsearchLogService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchLogService.class);

    /** Regex to extract the output path from a CP success log message. Requirements: 3.3 */
    private static final Pattern OUTPUT_PATH_PATTERN =
            Pattern.compile("output path\\s*=\\s*(.+)", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final String authHeader;
    private final CpElasticsearchProperties props;
    private final ObjectMapper objectMapper;

    public ElasticsearchLogService(HttpClient elasticsearchHttpClient,
                                   CpElasticsearchProperties props,
                                   ObjectMapper objectMapper) {
        this.httpClient = elasticsearchHttpClient;
        this.authHeader = buildAuthHeader(props);
        this.props = props;
        this.objectMapper = objectMapper;
    }

    private static String buildAuthHeader(CpElasticsearchProperties props) {
        String apiKey = props.getApiKey();
        String username = props.getUsername();
        String password = props.getPassword();
        if (apiKey != null && !apiKey.isBlank()) {
            return "ApiKey " + java.util.Base64.getEncoder().encodeToString(apiKey.getBytes());
        }
        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            String credentials = username + ":" + password;
            return "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
        }
        return null;
    }

    /**
     * Searches the CP Elasticsearch index for a log entry matching the given file.
     *
     * <p>Query filters (Requirements 2.3, 2.4, 2.5, 2.6):</p>
     * <ul>
     *   <li>{@code cpConfig: *sender*} — isolates ExensioReload-triggered files</li>
     *   <li>{@code idData: <dataId>} — primary key match</li>
     *   <li>{@code mLot: <lot>} — disambiguation when idData is ambiguous</li>
     *   <li>{@code @timestamp >= since} — only logs after enrichment started</li>
     * </ul>
     *
     * <p>Hit evaluation order (Requirements 3.1, 4.1):</p>
     * <ol>
     *   <li>If any hit has {@code error.type} or {@code error.message} → {@link CpLogResult.Failure}</li>
     *   <li>Else if any hit has "output path" in {@code message} → {@link CpLogResult.Success}</li>
     *   <li>Else → {@link CpLogResult.NotFound}</li>
     * </ol>
     *
     * @param dataId the data_id of the SENDER_STAGE record
     * @param lot    the lot of the SENDER_STAGE record
     * @param since  the instant the record entered ENRICHMENT status
     * @return the enrichment outcome
     */
    public CpLogResult findCpLog(String dataId, String lot, Instant since, String site) {
        String initialFilter = props.getCpConfigFilter();
        String url = props.getUrl().replaceAll("/$", "") + "/" + props.getIndexPattern() + "/_search";

        try {
            // First attempt using configured cpConfig filter
            String queryJson = buildQuery(dataId, lot, since, site, initialFilter);
            CpLogResult result = executeSearch(url, queryJson, dataId, lot);

            // If no hit found and the configured filter is different from the broad fallback,
            // retry once with fallback "*sender*" (case-insensitive) to improve recall.
            if (result instanceof CpLogResult.NotFound
                    && initialFilter != null
                    && !initialFilter.equalsIgnoreCase("*sender*")) {
                log.debug("No hits with cpConfig filter='{}'. retrying with fallback '*sender*' for dataId={}", initialFilter, dataId);
                String fallbackQuery = buildQuery(dataId, lot, since, site, "*sender*");
                return executeSearch(url, fallbackQuery, dataId, lot);
            }

            return result;

        } catch (ElasticsearchQueryException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Elasticsearch query failed for dataId={}, lot={}: {}", dataId, lot, e.getMessage());
            throw new ElasticsearchQueryException("ES query failed for dataId=" + dataId, e);
        }
    }

    /**
     * Execute the HTTP request and parse the response into a {@link CpLogResult}.
     */
    private CpLogResult executeSearch(String url, String queryJson, String dataId, String lot) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(queryJson));

        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("ES query returned HTTP {} for dataId={}", response.statusCode(), dataId);
            throw new ElasticsearchQueryException("ES returned HTTP " + response.statusCode());
        }

        return parseResponse(response.body(), dataId, lot);
    }

    /**
     * Extracts the output path from a CP log message using the pattern {@code output path\s*=\s*(.+)}.
     * Requirements: 3.3
     */
    public Optional<String> extractOutputPath(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = OUTPUT_PATH_PATTERN.matcher(message);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).trim());
        }
        return Optional.empty();
    }

    /**
     * Determines the output target from the output path string.
     * Requirements: 5.3
     *
     * @param path the output path extracted from the CP log
     * @return "PRODUCTION", "SANDBOX", or "UNKNOWN" — never null, never throws
     */
    public String detectOutputTarget(String path) {
        if (path == null) {
            return "UNKNOWN";
        }
        String upper = path.toUpperCase();
        if (upper.contains("PRODUCTION")) {
            return "PRODUCTION";
        }
        if (upper.contains("SANDBOX")) {
            return "SANDBOX";
        }
        return "UNKNOWN";
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Builds the ES query JSON as a string.
     */
    String buildQuery(String dataId, String lot, Instant since, String site) {
        return buildQuery(dataId, lot, since, site, props.getCpConfigFilter());
    }

    /**
     * Builds the ES query JSON as a string, using an explicit cpConfig wildcard filter when provided.
     */
    String buildQuery(String dataId, String lot, Instant since, String site, String cpConfigFilter) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode query = root.putObject("query");
            ObjectNode bool = query.putObject("bool");
            ArrayNode must = bool.putArray("must");

            // cpConfig wildcard filter (Requirement 2.3)
            ObjectNode wildcardClause = must.addObject();
            ObjectNode wildcard = wildcardClause.putObject("wildcard");
            ObjectNode cpConfigWild = wildcard.putObject("cpConfig");
            cpConfigWild.put("value", cpConfigFilter == null ? props.getCpConfigFilter() : cpConfigFilter);
            cpConfigWild.put("case_insensitive", true);

            // Optional service.country filter (for example, PHO for the External source)
            if (props.getServiceCountryFilter() != null && !props.getServiceCountryFilter().isBlank()) {
                String fieldName = props.resolveServiceCountryField(site);
                ObjectNode termServiceCountry = must.addObject();
                ObjectNode termServiceCountryInner = termServiceCountry.putObject("term");
                termServiceCountryInner.put(fieldName, props.getServiceCountryFilter().trim());
            }

            // idData term match (Requirement 2.4)
            ObjectNode termIdData = must.addObject();
            ObjectNode termIdDataInner = termIdData.putObject("term");
            termIdDataInner.put("idData", dataId);

            // mLot term match (Requirement 2.5) — only add when requireLot is true AND lot is provided
            if (props.isRequireLot() && lot != null && !lot.isBlank()) {
                ObjectNode termLot = must.addObject();
                ObjectNode termLotInner = termLot.putObject("term");
                termLotInner.put("mLot", lot);
            }

            // @timestamp range (Requirement 2.6)
            ObjectNode rangeClause = must.addObject();
            ObjectNode range = rangeClause.putObject("range");
            ObjectNode tsRange = range.putObject("@timestamp");
            tsRange.put("gte", since.toString());

            // Sort by @timestamp desc, fetch up to 10 hits
            ArrayNode sort = root.putArray("sort");
            ObjectNode sortField = sort.addObject();
            ObjectNode tsSort = sortField.putObject("@timestamp");
            tsSort.put("order", "desc");

            root.put("size", 10);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ElasticsearchQueryException("Failed to build ES query", e);
        }
    }

    /**
     * Parses the ES search response body and returns the appropriate {@link CpLogResult}.
     */
    private CpLogResult parseResponse(String body, String dataId, String lot) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode hits = root.path("hits").path("hits");

            if (!hits.isArray() || hits.isEmpty()) {
                return new CpLogResult.NotFound();
            }

            // Failure check first — error takes priority over success (Requirements 3.1, 4.1)
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                if (source.isMissingNode()) continue;

                if (hasError(source)) {
                    String errorMessage = extractErrorMessage(source);
                    Instant timestamp = parseTimestamp(source);
                    log.debug("CP failure log found for dataId={}, lot={}: {}", dataId, lot, errorMessage);
                    return new CpLogResult.Failure(errorMessage, timestamp);
                }
            }

            // Success check — look for "output path" in message
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                if (source.isMissingNode()) continue;

                String message = source.path("message").asText(null);
                if (message != null && message.toLowerCase().contains("output path")) {
                    Optional<String> outputPath = extractOutputPath(message);
                    if (outputPath.isPresent()) {
                        String path = outputPath.get().trim();
                        String target = detectOutputTarget(path);
                        Instant timestamp = parseTimestamp(source);
                        log.debug("CP success log found for dataId={}, lot={}: path={}, target={}",
                                dataId, lot, path, target);
                        return new CpLogResult.Success(path, target, timestamp);
                    }
                }
            }

            return new CpLogResult.NotFound();

        } catch (Exception e) {
            log.warn("Failed to parse ES response for dataId={}: {}", dataId, e.getMessage());
            throw new ElasticsearchQueryException("Failed to parse ES response", e);
        }
    }

    private boolean hasError(JsonNode source) {
        JsonNode errorNode = source.path("error");
        if (errorNode.isMissingNode() || errorNode.isNull()) return false;
        String errorType = errorNode.path("type").asText(null);
        String errorMessage = errorNode.path("message").asText(null);
        return isNonBlank(errorType) || isNonBlank(errorMessage);
    }

    private String extractErrorMessage(JsonNode source) {
        JsonNode errorNode = source.path("error");
        String msg = errorNode.path("message").asText(null);
        if (isNonBlank(msg)) return msg;
        String type = errorNode.path("type").asText(null);
        if (isNonBlank(type)) return type;
        return "Unknown error";
    }

    private Instant parseTimestamp(JsonNode source) {
        String ts = source.path("@timestamp").asText(null);
        if (ts != null) {
            try {
                return Instant.parse(ts);
            } catch (Exception e) {
                log.trace("Could not parse @timestamp: {}", ts);
            }
        }
        return Instant.now();
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Unchecked wrapper for ES IO failures — lets callers handle at the monitor level. */
    public static class ElasticsearchQueryException extends RuntimeException {
        public ElasticsearchQueryException(String message) {
            super(message);
        }
        public ElasticsearchQueryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
