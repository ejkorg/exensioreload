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
 * <p>Requirements: 1.1, 1.2, 1.3, 1.4, 2.1–2.7, 3.1–3.8, 4.1–4.3, 5.1, 5.3, 6.3, 6.4, 7.1–7.3</p>
 */
@Service
public class ElasticsearchLogService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchLogService.class);

    /** Regex to extract the output path from a CP success log message. Retained for pp_log output_directory. */
    private static final Pattern OUTPUT_PATH_PATTERN =
            Pattern.compile("output path\\s*=\\s*(.+)", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final String authHeader;
    private final CpElasticsearchProperties props;
    private final ObjectMapper objectMapper;
    /** May be null in test environments where RefDB is not configured. Requirements: 6.3, 6.4 */
    private final RefDbService refDbService;

    public ElasticsearchLogService(HttpClient elasticsearchHttpClient,
                                   CpElasticsearchProperties props,
                                   ObjectMapper objectMapper,
                                   RefDbService refDbService) {
        this.httpClient = elasticsearchHttpClient;
        this.authHeader = buildAuthHeader(props);
        this.props = props;
        this.objectMapper = objectMapper;
        this.refDbService = refDbService;
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
     * <p>Query filters (Requirements 1.1, 1.2, 1.3, 2.3–2.6):</p>
     * <ul>
     *   <li>{@code cpConfig: *sender*} — isolates ExensioReload-triggered files</li>
     *   <li>{@code idFile: <idFile>} — file-level key match (when non-blank)</li>
     *   <li>{@code idData: <dataId>} — data-level key match</li>
     *   <li>{@code mLot: <lot>} — disambiguation when idData is ambiguous</li>
     *   <li>{@code @timestamp >= since} — only logs after enrichment started</li>
     * </ul>
     *
     * <p>Hit evaluation order (Requirements 4.1, 2.6, 2.7, 3.1):</p>
     * <ol>
     *   <li>If {@code log.level == ERROR} → {@link CpLogResult.Failure}</li>
     *   <li>Else if message contains PRODUCTION → {@link CpLogResult.Success}</li>
     *   <li>Else if message contains SANDBOX → {@link CpLogResult.Success}</li>
     *   <li>Else if message contains "executed successfully" → pp_log fallback</li>
     *   <li>Else → {@link CpLogResult.NotFound}</li>
     * </ol>
     *
     * @param idFile the metadata_id of the SENDER_STAGE record (may be null/blank)
     * @param dataId the data_id of the SENDER_STAGE record
     * @param lot    the lot of the SENDER_STAGE record
     * @param since  the instant the record entered ENRICHMENT status
     * @param site   the site identifier
     * @return the enrichment outcome
     */
    public CpLogResult findCpLog(String idFile, String dataId, String lot, Instant since, String site) {
        String initialFilter = props.getCpConfigFilter();
        String url = props.resolveSearchUrl();

        try {
            // First attempt using configured cpConfig filter
            String queryJson = buildQuery(idFile, dataId, lot, since, site, initialFilter);
            CpLogResult result = executeSearch(url, queryJson, idFile, dataId, lot);

            // If no hit found and the configured filter is different from the broad fallback,
            // retry once with fallback "*sender*" (case-insensitive) to improve recall.
            if (result instanceof CpLogResult.NotFound
                    && initialFilter != null
                    && !initialFilter.equalsIgnoreCase("*sender*")) {
                log.debug("No hits with cpConfig filter='{}'. retrying with fallback '*sender*' for dataId={}", initialFilter, dataId);
                String fallbackQuery = buildQuery(idFile, dataId, lot, since, site, "*sender*");
                return executeSearch(url, fallbackQuery, idFile, dataId, lot);
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
    private CpLogResult executeSearch(String url, String queryJson, String idFile, String dataId, String lot) throws Exception {
        if (props.isLogRequestPayloads()) {
            log.info("ES query payload (dataId={}, idFile={}): url={}, body={}", dataId, idFile, url, queryJson);
        }
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

        return parseResponse(response.body(), idFile, dataId, lot);
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
     * Builds the ES query JSON using the default cpConfig filter.
     * Requirements: 1.1, 1.2, 1.3, 1.4, 2.1–2.5, 7.1
     */
    String buildQuery(String idFile, String dataId, String lot, Instant since, String site) {
        return buildQuery(idFile, dataId, lot, since, site, props.getCpConfigFilter());
    }

    /**
     * Builds the ES query JSON with an explicit cpConfig wildcard filter.
     * Requirements: 1.1, 1.2, 1.3, 1.4, 2.1–2.5, 7.1
     */
    String buildQuery(String idFile, String dataId, String lot, Instant since, String site, String cpConfigFilter) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode query = root.putObject("query");
            ObjectNode bool = query.putObject("bool");
            ArrayNode must = bool.putArray("must");

            // cpConfig wildcard filter (Requirement 2.3) — use "_sender*" pattern
            ObjectNode wildcardClause = must.addObject();
            ObjectNode wildcard = wildcardClause.putObject("wildcard");
            ObjectNode cpConfigWild = wildcard.putObject("cpConfig");
            String filterValue = cpConfigFilter == null ? props.getCpConfigFilter() : cpConfigFilter;
            cpConfigWild.put("value", "*_sender*");
            cpConfigWild.put("case_insensitive", true);

            // Optional service.country filter
            if (props.getServiceCountryFilter() != null && !props.getServiceCountryFilter().isBlank()) {
                String fieldName = props.resolveServiceCountryField(site);
                ObjectNode termServiceCountry = must.addObject();
                ObjectNode termServiceCountryInner = termServiceCountry.putObject("term");
                termServiceCountryInner.put(fieldName, props.getServiceCountryFilter().trim());
            }

            // idFile term match — only when non-blank (Requirements 1.1, 1.3, 7.1)
            if (idFile != null && !idFile.isBlank()) {
                ObjectNode termIdFile = must.addObject();
                ObjectNode termIdFileInner = termIdFile.putObject("term");
                termIdFileInner.put("idFile", idFile);
            }

            // idData term match (Requirement 1.2)
            ObjectNode termIdData = must.addObject();
            ObjectNode termIdDataInner = termIdData.putObject("term");
            termIdDataInner.put("idData", dataId);

            // mLot term match — only add when requireLot is true AND lot is provided
            if (props.isRequireLot() && lot != null && !lot.isBlank()) {
                ObjectNode termLot = must.addObject();
                ObjectNode termLotInner = termLot.putObject("term");
                termLotInner.put("mLot", lot);
            }

            // @timestamp range
            ObjectNode rangeClause = must.addObject();
            ObjectNode range = rangeClause.putObject("range");
            ObjectNode tsRange = range.putObject("@timestamp");
            tsRange.put("gte", since.toString());

            // should clauses for scoring (Requirements 2.1–2.4)
            ArrayNode should = bool.putArray("should");

            // Boost 4: PRODUCTION output path in message
            ObjectNode shouldProd = should.addObject();
            ObjectNode wildcardProd = shouldProd.putObject("wildcard");
            ObjectNode wildcardProdMsg = wildcardProd.putObject("message");
            wildcardProdMsg.put("value", "*output path*PRODUCTION*");
            wildcardProdMsg.put("case_insensitive", true);
            wildcardProdMsg.put("boost", 4);

            // Boost 3: SANDBOX in message
            ObjectNode shouldSbx = should.addObject();
            ObjectNode wildcardSbx = shouldSbx.putObject("wildcard");
            ObjectNode wildcardSbxMsg = wildcardSbx.putObject("message");
            wildcardSbxMsg.put("value", "*SANDBOX*");
            wildcardSbxMsg.put("case_insensitive", true);
            wildcardSbxMsg.put("boost", 3);

            // Boost 3: non-ERROR log level
            ObjectNode shouldNonError = should.addObject();
            ObjectNode boolNonError = shouldNonError.putObject("bool");
            boolNonError.put("boost", 3);
            ArrayNode mustNotArr = boolNonError.putArray("must_not");
            ObjectNode mustNotTerm = mustNotArr.addObject();
            ObjectNode mustNotTermInner = mustNotTerm.putObject("term");
            mustNotTermInner.put("log.level", "ERROR");

            // Boost 1: ERROR log level
            ObjectNode shouldError = should.addObject();
            ObjectNode termError = shouldError.putObject("term");
            ObjectNode termErrorInner = termError.putObject("log.level");
            termErrorInner.put("value", "ERROR");
            termErrorInner.put("boost", 1);

            // Requirement 2.5: at least one should clause must match
            bool.put("minimum_should_match", 1);

            // Sort by @timestamp desc, fetch up to 10 hits
            ArrayNode sort = root.putArray("sort");
            ObjectNode sortField = sort.addObject();
            ObjectNode tsSort = sortField.putObject("@timestamp");
            tsSort.put("order", "desc");

            root.put("size", 2);

            // Requirement 1.4: include idFile and idData in _source
            ArrayNode source = root.putArray("_source");
            source.add("@timestamp");
            source.add("cpConfig");
            source.add("idData");
            source.add("idFile");
            source.add("message");
            source.add("log.level");

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ElasticsearchQueryException("Failed to build ES query", e);
        }
    }

    /**
     * Parses the ES search response body and returns the appropriate {@link CpLogResult}.
     *
     * <p>Priority order per hit (Requirements 4.1, 4.3, 2.6, 2.7, 3.1–3.8):</p>
     * <ol>
     *   <li>{@code log.level == ERROR} → {@link CpLogResult.Failure}</li>
     *   <li>message contains PRODUCTION → {@link CpLogResult.Success}</li>
     *   <li>message contains SANDBOX → {@link CpLogResult.Success}</li>
     *   <li>message contains "executed successfully" → pp_log fallback via RefDbService</li>
     *   <li>no match → continue to next hit</li>
     * </ol>
     */
    private CpLogResult parseResponse(String body, String idFile, String dataId, String lot) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode hits = root.path("hits").path("hits");

            if (!hits.isArray() || hits.isEmpty()) {
                return new CpLogResult.NotFound();
            }

            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                if (source.isMissingNode()) continue;

                String logLevel = source.path("log.level").asText(null);
                String message = source.path("message").asText(null);
                Instant timestamp = parseTimestamp(source);

                // Priority 1: ERROR log level → always a failure (Requirements 4.1, 4.3)
                if (logLevel != null && logLevel.equalsIgnoreCase("ERROR")) {
                    String errorMessage = isNonBlank(message) ? message : "CP processing error";
                    log.info("CP failure (log.level=ERROR) for dataId={}: {}", dataId, errorMessage);
                    return new CpLogResult.Failure(errorMessage, timestamp);
                }

                if (message == null) continue;
                String messageUpper = message.toUpperCase();

                // Priority 2: PRODUCTION in message (Requirement 2.6)
                if (messageUpper.contains("PRODUCTION")) {
                    log.info("CP success (PRODUCTION in message) for dataId={}", dataId);
                    return new CpLogResult.Success(message, "PRODUCTION", timestamp);
                }

                // Priority 3: SANDBOX in message (Requirement 2.6)
                if (messageUpper.contains("SANDBOX")) {
                    log.info("CP success (SANDBOX in message) for dataId={}", dataId);
                    return new CpLogResult.Success(message, "SANDBOX", timestamp);
                }

                // Priority 4: "executed successfully" → pp_log fallback (Requirements 3.1–3.8)
                if (message.toLowerCase().contains("executed successfully")) {
                    log.debug("CP 'executed successfully' hit for dataId={} — querying pp_log", dataId);
                    return queryPpLogFallback(idFile, lot, timestamp);
                }
            }

            return new CpLogResult.NotFound();

        } catch (Exception e) {
            log.warn("Failed to parse ES response for dataId={}: {}", dataId, e.getMessage());
            throw new ElasticsearchQueryException("Failed to parse ES response", e);
        }
    }

    /**
     * Delegates to RefDbService to query pp_log for the output directory or error message.
     * Requirements: 3.2–3.8, 6.3, 6.4
     */
    private CpLogResult queryPpLogFallback(String idFile, String lot, Instant timestamp) {
        // Requirement 6.4: guard against RefDbService being unavailable
        if (refDbService == null || idFile == null || idFile.isBlank()) {
            log.debug("pp_log fallback skipped — refDbService={} idFile={}", refDbService, idFile);
            return new CpLogResult.NotFound();
        }
        try {
            // Requirement 3.2–3.4: query for success row
            String outputDirectory = refDbService.queryPpLogSuccess(lot, idFile);
            if (outputDirectory != null) {
                String target = detectOutputTarget(outputDirectory);
                log.debug("pp_log success for idFile={} lot={}: dir={} target={}", idFile, lot, outputDirectory, target);
                return new CpLogResult.Success(outputDirectory, target, timestamp);
            }
            // Requirement 3.5–3.6: query for error row
            String logMessage = refDbService.queryPpLogError(lot, idFile);
            if (logMessage != null) {
                log.debug("pp_log error for idFile={} lot={}: {}", idFile, lot, logMessage);
                return new CpLogResult.Failure(logMessage, timestamp);
            }
            // Requirement 3.7: no rows in either query → retry next cycle
            log.debug("pp_log returned no rows for idFile={} lot={}", idFile, lot);
            return new CpLogResult.NotFound();
        } catch (Exception ex) {
            // Requirement 3.8: SQLException or other error → log warning, retry next cycle
            log.warn("pp_log fallback failed for idFile={} lot={}: {}", idFile, lot, ex.getMessage());
            return new CpLogResult.NotFound();
        }
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
