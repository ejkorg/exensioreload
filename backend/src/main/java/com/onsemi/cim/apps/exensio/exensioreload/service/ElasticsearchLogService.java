package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;

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

    // Circuit breaker state (thread-safe for singleton access from @Scheduled threads)
    private enum State { CLOSED, OPEN, HALF_OPEN }
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private static final int FAILURE_THRESHOLD = 5;
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private static final long TIMEOUT_DURATION = 60_000; // 1 minute

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
     * <p>Query filters (Requirements 1.1, 1.2, 1.3, 2.3–2.6):</p>
     * <ul>
     *   <li>{@code cpConfig: *sender*} — isolates ExensioReload-triggered files</li>
     *   <li>{@code idFile: <idFile>} — boost match (when non-blank)</li>
     *   <li>{@code idData: <dataId>} — data-level key match</li>
     *   <li>{@code mLot: <lot>} — disambiguation when idData is ambiguous</li>
     *   <li>{@code filename: <filename>} — optional filename match for additional accuracy</li>
     *   <li>{@code @timestamp >= earliest(endTime, since) - buffer} — lookback floor</li>
     * </ul>
     *
     * <p>Convenience overload without filename or endTime context.</p>
     */
    public CpLogResult findCpLog(String idFile, String dataId, String lot, Instant since, String site) {
        return findCpLog(idFile, dataId, lot, since, site, null, null);
    }

    /**
     * Searches the CP Elasticsearch index with optional filename filter.
     *
     * @param idFile    the metadata_id of the SENDER_STAGE record (may be null/blank)
     * @param dataId    the data_id of the SENDER_STAGE record
     * @param lot       the lot of the SENDER_STAGE record
     * @param since     the instant the record entered ENRICHMENT status
     * @param site      the site identifier
     * @param filename  the optional filename to filter by (may be null/blank)
     * @return the enrichment outcome
     */
    public CpLogResult findCpLog(String idFile, String dataId, String lot, Instant since, String site, String filename) {
        return findCpLog(idFile, dataId, lot, since, site, filename, null);
    }
    /**
     * Searches the CP Elasticsearch index with optional filename filter.
     *
     * <p>The {@code @timestamp} lower bound is computed in UTC:
     * <pre>
     *   floor = since (enrichmentStartedAt) - lookbackBufferSeconds
     * </pre>
     * Elasticsearch stores and streams {@code @timestamp} in UTC (ending with 'Z').
     * The lower bound is formatted as an ISO-8601 UTC string (e.g. {@code 2026-09-04T07:40:10.652Z})
     * to accurately match indexed document timestamps regardless of client/Kibana local display timezone.
     *
     * @param idFile    the metadata_id of the SENDER_STAGE record (may be null/blank)
     * @param dataId    the data_id of the SENDER_STAGE record
     * @param lot       the lot of the SENDER_STAGE record
     * @param since     the instant the record entered ENRICHMENT status (lookback anchor in UTC)
     * @param site      the site identifier
     * @param filename  the optional filename to filter by (may be null/blank)
     * @param endTime   unused (retained for signature compatibility)
     * @return the enrichment outcome
     */
    public CpLogResult findCpLog(String idFile, String dataId, String lot, Instant since, String site, String filename, Instant endTime) {
        // Generate trace ID for this request
        String traceId = UUID.randomUUID().toString();
        log.info("Starting ES query with traceId={} for dataId={}, lot={}", traceId, dataId, lot);

        // Check circuit breaker
        if (!allowRequest()) {
            log.warn("Circuit breaker is OPEN for ES query (traceId={}, dataId={}, lot={})", traceId, dataId, lot);
            throw new ElasticsearchQueryException("Circuit breaker is OPEN");
        }

        String initialFilter = props.getCpConfigFilter();
        String url = props.resolveSearchUrl();

        try {
            // First attempt using configured cpConfig filter
            String queryJson = buildQuery(idFile, dataId, lot, since, site, filename, initialFilter, endTime);
            CpLogResult result = executeSearch(url, queryJson, idFile, dataId, lot, traceId);

            // If no hit found and the configured filter is restrictive,
            // retry once with wildcard "*" to catch configs that don't match the sender pattern
            // (e.g. cz2_defect_klarf_18_Si).
            if (result instanceof CpLogResult.NotFound
                    && initialFilter != null
                    && !initialFilter.equals("*")) {
                log.info("No hits with cpConfig filter='{}'. Retrying with wildcard '*' for dataId={} (traceId={})", initialFilter, dataId, traceId);
                String fallbackQuery = buildQuery(idFile, dataId, lot, since, site, filename, "*", endTime);
                return executeSearch(url, fallbackQuery, idFile, dataId, lot, traceId);
            }

            return result;

        } catch (ElasticsearchQueryException e) {
            recordFailure();
            throw e;
        } catch (Exception e) {
            recordFailure();
            log.warn("Elasticsearch query failed for dataId={}, lot={}: {} (traceId={})", dataId, lot, e.getMessage(), traceId);
            throw new ElasticsearchQueryException("ES query failed for dataId=" + dataId, e);
        }
    }

    /**
     * Execute the HTTP request and parse the response into a {@link CpLogResult}.
     */
    private CpLogResult executeSearch(String url, String queryJson, String idFile, String dataId, String lot, String traceId) throws Exception {
        long startTime = System.currentTimeMillis();

        log.info("Elasticsearch query START: url={}, dataId={}, lot={}, traceId={}", url, dataId, lot, traceId);
        log.info("ES query JSON:\n{}", queryJson);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(queryJson));

        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - startTime;

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("ES query FAILED (HTTP {}): dataId={}, elapsed={}ms, traceId={}, response={}",
                    response.statusCode(), dataId, elapsed, traceId, response.body());
            throw new ElasticsearchQueryException("ES returned HTTP " + response.statusCode());
        }

        log.info("ES query HTTP RESPONSE ({}ms): HTTP {}, dataId={}, traceId={}", elapsed, response.statusCode(), dataId, traceId);
        if (props.isLogRequestPayloads()) {
            log.info("ES query response body:\n{}", response.body());
        }

        CpLogResult result = parseResponse(response.body(), idFile, dataId, lot, traceId);
        if (result instanceof CpLogResult.Success || result instanceof CpLogResult.Failure) {
            recordSuccess();
        } else {
            // NotFound is not a failure for the circuit breaker
        }
        return result;
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
    private boolean allowRequest() {
        if (state.get() == State.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime.get() > TIMEOUT_DURATION) {
                state.set(State.HALF_OPEN);
                return true;
            }
            return false;
        }
        return true;
    }

    private void recordSuccess() {
        failureCount.set(0);
        state.set(State.CLOSED);
    }

    private void recordFailure() {
        int count = failureCount.incrementAndGet();
        if (count >= FAILURE_THRESHOLD) {
            state.set(State.OPEN);
            lastFailureTime.set(System.currentTimeMillis());
        }
    }

    /**
     * Builds the ES query JSON using the default cpConfig filter.
     * Requirements: 1.1, 1.2, 1.3, 1.4, 2.1–2.5, 7.1
     */
    String buildQuery(String idFile, String dataId, String lot, Instant since, String site) {
        return buildQuery(idFile, dataId, lot, since, site, null, props.getCpConfigFilter(), null);
    }

    /**
     * Builds the ES query JSON with an explicit cpConfig wildcard filter.
     * Requirements: 1.1, 1.2, 1.3, 1.4, 2.1–2.5, 7.1
     */
    String buildQuery(String idFile, String dataId, String lot, Instant since, String site, String cpConfigFilter) {
        return buildQuery(idFile, dataId, lot, since, site, null, cpConfigFilter, null);
    }

    /**
     * Builds the ES query JSON with optional filename filter.
     * Requirements: 1.1, 1.2, 1.3, 1.4, 2.1–2.5, 7.1
     */
    String buildQuery(String idFile, String dataId, String lot, Instant since, String site, String filename, String cpConfigFilter) {
        return buildQuery(idFile, dataId, lot, since, site, filename, cpConfigFilter, null);
    }


    /**
     * Core ES query builder.
     *
     * <p><b>idFile and inputFileName placement:</b>
     * Both {@code idFile} and {@code inputFileName} are placed in {@code should} boost
     * clauses (not {@code must}). This increases relevance when the document matches,
     * but does not eliminate documents where the field is absent or indexed differently —
     * which previously caused zero hits. The {@code idData} term remains in {@code must}
     * as the primary required filter.
     *
     * <p><b>Lookback floor:</b>
     * The {@code @timestamp gte} is set to {@code enrichmentStartedAt - lookbackBufferSeconds}.
     * We always look for logs from the <em>current</em> reprocessing event, not from the
     * file's original creation date (which could be days/weeks old for archived files).
     *
     * @param idFile        metadata_id / idFile (null/blank = skip)
     * @param dataId        data_id (required)
     * @param lot           lot id (optional, only used when requireLot=true)
     * @param since         enrichmentStartedAt — the lookback anchor
     * @param site          site key for service-country field resolution
     * @param filename      original filename (null/blank = skip)
     * @param cpConfigFilter wildcard value for the cpConfig must clause
     * @param endTime       unused — retained for API compatibility (may be null)
     */
    String buildQuery(String idFile, String dataId, String lot, Instant since, String site,
                      String filename, String cpConfigFilter, Instant endTime) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode query = root.putObject("query");
            ObjectNode bool = query.putObject("bool");
            ArrayNode must = bool.putArray("must");

            // cpConfig wildcard filter — value driven by cp.elasticsearch.cp-config-filter (default: *sender*)
            ObjectNode wildcardClause = must.addObject();
            ObjectNode wildcard = wildcardClause.putObject("wildcard");
            ObjectNode cpConfigWild = wildcard.putObject("cpConfig");
            String filterValue = cpConfigFilter == null ? props.getCpConfigFilter() : cpConfigFilter;
            cpConfigWild.put("value", filterValue);
            cpConfigWild.put("case_insensitive", true);

            // Optional service.country filter
            if (props.getServiceCountryFilter() != null && !props.getServiceCountryFilter().isBlank()) {
                String fieldName = props.resolveServiceCountryField(site);
                ObjectNode termServiceCountry = must.addObject();
                ObjectNode termServiceCountryInner = termServiceCountry.putObject("term");
                termServiceCountryInner.put(fieldName, props.getServiceCountryFilter().trim());
            }

            // idData term match (Requirement 1.2) — primary required filter
            ObjectNode termIdData = must.addObject();
            ObjectNode termIdDataInner = termIdData.putObject("term");
            termIdDataInner.put("idData", dataId);

            // mLot term match — only add when requireLot is true AND lot is provided
            if (props.isRequireLot() && lot != null && !lot.isBlank()) {
                ObjectNode termLot = must.addObject();
                ObjectNode termLotInner = termLot.putObject("term");
                termLotInner.put("mLot", lot);
            }

            // @timestamp range — anchored to enrichmentStartedAt (current reprocessing).
            // We look for logs from the current reload cycle, not from the original file date.
            int bufferSeconds = props.getLookbackBufferSeconds();
            Instant lookbackFloor = since.minusSeconds(bufferSeconds);

            ObjectNode rangeClause = must.addObject();
            ObjectNode range = rangeClause.putObject("range");
            ObjectNode tsRange = range.putObject("@timestamp");
            String sinceStr = lookbackFloor.toString();
            tsRange.put("gte", sinceStr);
            if (log.isDebugEnabled()) {
                log.debug("ES query @timestamp range: gte={}, since={}, sinceEpochMs={}, systemTZ={}",
                    sinceStr, since, lookbackFloor.toEpochMilli(), java.util.TimeZone.getDefault().getID());
            }

            // ── should clauses (boost, not required) ──────────────────────────────
            ArrayNode should = bool.putArray("should");

            // Boost 5: idFile exact match — in should so mismatch doesn't zero out results.
            // Previously was a must-filter which caused zero hits when ES indexed idFile
            // differently or the field was absent.
            if (idFile != null && !idFile.isBlank()) {
                ObjectNode shouldIdFile = should.addObject();
                ObjectNode termIdFile = shouldIdFile.putObject("term");
                ObjectNode termIdFileInner = termIdFile.putObject("idFile");
                termIdFileInner.put("value", idFile);
                termIdFileInner.put("boost", 5);
            }

            // Boost 4: inputFileName wildcard match — in should so a reprocessed file
            // with a different inputFileName still gets matched by idData alone.
            // Previously was in must which zeroed out results when CP indexed the file
            // under a different name during reprocessing.
            if (filename != null && !filename.isBlank()) {
                String nameBase = filename.trim();
                int dot = nameBase.lastIndexOf('.');
                if (dot > 0) nameBase = nameBase.substring(0, dot);
                ObjectNode shouldFilename = should.addObject();
                ObjectNode wildcardFilenameOuter = shouldFilename.putObject("wildcard");
                ObjectNode filenameWildcard = wildcardFilenameOuter.putObject("inputFileName");
                filenameWildcard.put("value", "*" + nameBase + "*");
                filenameWildcard.put("case_insensitive", true);
                filenameWildcard.put("boost", 4);
            }

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


            // At least one should clause must match
            bool.put("minimum_should_match", 1);

            // Sort by @timestamp desc
            ArrayNode sort = root.putArray("sort");
            ObjectNode sortField = sort.addObject();
            ObjectNode tsSort = sortField.putObject("@timestamp");
            tsSort.put("order", "desc");

            root.put("size", 100);

            // _source fields
            ArrayNode source = root.putArray("_source");
            source.add("@timestamp");
            source.add("cpConfig");
            source.add("idData");
            source.add("idFile");
            source.add("inputFileName");
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
     *   <li>message contains "executed successfully" → pp_log fallback (Requirements 3.1–3.8)</li>
     *   <li>no match → continue to next hit</li>
     * </ol>
     */
    private CpLogResult parseResponse(String body, String idFile, String dataId, String lot, String traceId) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode hits = root.path("hits").path("hits");

            if (!hits.isArray() || hits.isEmpty()) {
                log.info("ES query RESULT: NotFound for dataId={} (no hits) (traceId={})", dataId, traceId);
                return new CpLogResult.NotFound(traceId);
            }

            log.debug("ES query: {} hits found for dataId={}", hits.size(), dataId);

            // First pass: look for success patterns (ignoring ERROR logs)
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                if (source.isMissingNode()) continue;

                String logLevel = source.path("log.level").asText(null);
                String message = source.path("message").asText(null);
                Instant timestamp = parseTimestamp(source);

                // Skip ERROR logs in first pass
                if (logLevel != null && logLevel.equalsIgnoreCase("ERROR")) {
                    continue;
                }

                if (message == null) continue;
                String messageUpper = message.toUpperCase();

                // Priority 2: "Commands flow executed successfully" (primary CP success indicator)
                if (messageUpper.contains("COMMANDS FLOW EXECUTED SUCCESSFULLY")) {
                    log.info("ES query RESULT: Success (Commands flow executed successfully) for dataId={}", dataId);
                    return new CpLogResult.Success(traceId, message, "PRODUCTION", timestamp);
                }

                // Priority 3: "output path = " in message (actual CP success indicator)
                if (messageUpper.contains("OUTPUT PATH = ")) {
                    log.info("ES query RESULT: Success (output path found) for dataId={}", dataId);
                    return new CpLogResult.Success(traceId, message, "PRODUCTION", timestamp);
                }

                // Priority 4: PRODUCTION in message (Requirement 2.6)
                if (messageUpper.contains("PRODUCTION")) {
                    log.info("ES query RESULT: Success (PRODUCTION) for dataId={}", dataId);
                    return new CpLogResult.Success(traceId, message, "PRODUCTION", timestamp);
                }

                // Priority 5: SANDBOX in message (Requirement 2.6)
                if (messageUpper.contains("SANDBOX")) {
                    log.info("ES query RESULT: Success (SANDBOX) for dataId={}", dataId);
                    return new CpLogResult.Success(traceId, message, "SANDBOX", timestamp);
                }

                // Priority 6: "executed successfully" or "Command Processor successfully"
                // but no PRODUCTION/SANDBOX keyword — pp_log is queried in parallel by CpLogMonitor
                String messageLower = message.toLowerCase();
                if (messageLower.contains("executed successfully") || messageLower.contains("command processor successfully")) {
                    log.info("ES query RESULT: Success (executed successfully, no env keyword) for dataId={}", dataId);
                    return new CpLogResult.Success(traceId, message, "PP_LOG", timestamp);
                }
            }

            // Second pass: if no success found, check for ERROR logs to return Failure
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                if (source.isMissingNode()) continue;

                String logLevel = source.path("log.level").asText(null);
                String message = source.path("message").asText(null);
                Instant timestamp = parseTimestamp(source);

                if (logLevel != null && logLevel.equalsIgnoreCase("ERROR")) {
                    String errorMessage = isNonBlank(message) ? message : "CP processing error";
                    log.info("ES query RESULT: Failure (log.level=ERROR) for dataId={}: {}", dataId, errorMessage);
                    return new CpLogResult.Failure(traceId, errorMessage, timestamp);
                }
            }

            log.info("ES query RESULT: NotFound for dataId={} (no matching criteria)", dataId);
            return new CpLogResult.NotFound(traceId);

        } catch (Exception e) {
            log.warn("Failed to parse ES response for dataId={}: {}", dataId, e.getMessage());
            throw new ElasticsearchQueryException("Failed to parse ES response", e);
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