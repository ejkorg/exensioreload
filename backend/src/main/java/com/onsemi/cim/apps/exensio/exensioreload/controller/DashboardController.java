package com.onsemi.cim.apps.exensio.exensioreload.controller;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import com.onsemi.cim.apps.exensio.exensioreload.dto.DashboardBucketTotals;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DashboardDateBucket;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DashboardLink;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DashboardLotBreakdown;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DashboardMetricTotals;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DashboardSenderSnapshot;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DashboardSiteSnapshot;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DashboardSnapshot;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DashboardWaferBreakdown;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StageRecordPage;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StageRecordView;
import com.onsemi.cim.apps.exensio.exensioreload.service.RefDbService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageStatus;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageUserStatus;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final RefDbService refDbService;
    private final StageRecordMapper mapper;

    public DashboardController(RefDbService refDbService,
                               StageRecordMapper mapper) {
        this.refDbService = refDbService;
        this.mapper = mapper;
    }

    /**
     * Debug endpoint to check dashboard authentication
     */
    @GetMapping("/debug-auth")
    public ResponseEntity<Map<String, Object>> debugAuth() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            Map<String, Object> authInfo = Map.of(
                    "authenticated", auth != null && auth.isAuthenticated(),
                    "principal", auth != null ? auth.getPrincipal().toString() : "null",
                    "authorities", auth != null ? auth.getAuthorities().stream()
                            .map(Object::toString)
                            .collect(Collectors.toList()) : List.of(),
                    "name", auth != null ? auth.getName() : "null",
                    "hasRoleUser", auth != null && auth.getAuthorities().stream()
                            .anyMatch(a -> "ROLE_USER".equals(a.getAuthority())),
                    "details", auth != null && auth.getDetails() != null ? auth.getDetails().toString() : "null");

            return ResponseEntity.ok(authInfo);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Debug endpoint to list all users and their roles
     */
    @GetMapping("/debug-users")
    public ResponseEntity<Map<String, Object>> debugUsers() {
        try {
            // This is a debug endpoint, so we'll allow it for any authenticated user
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
            }

            // Get all users from the database
            List<Map<String, Object>> users = new ArrayList<>();
            // We need to inject the AppUserRepository to use it
            // For now, let's just return the current user info
            Map<String, Object> currentUser = Map.of(
                    "username", auth.getName(),
                    "authorities", auth.getAuthorities().stream()
                            .map(a -> a.getAuthority())
                            .collect(Collectors.toList()),
                    "authenticated", auth.isAuthenticated(),
                    "principal", auth.getPrincipal().toString());

            return ResponseEntity.ok(Map.of(
                    "currentUser", currentUser,
                    "message", "Debug endpoint - showing current authentication info"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @GetMapping("/snapshot")
    public DashboardSnapshot snapshot(@RequestParam(required = false) java.util.List<String> devices) {
        // GET /api/dashboard/snapshot - Get dashboard snapshot with optional device filtering
        // Requirements: 4.2, 7.1, 7.2
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<StageStatus> statuses = resolveStatuses(auth);
        
        // Note: Device filtering would be applied in the underlying query logic
        // For now, we accept the parameter for API compatibility

        Map<String, List<StageStatus>> bySite = statuses.stream()
                .collect(Collectors.groupingBy(status -> normalizeSite(status.site())));

        List<DashboardSiteSnapshot> sites = new ArrayList<>();
        DashboardTotalsAccumulator globalAcc = new DashboardTotalsAccumulator();

        for (Map.Entry<String, List<StageStatus>> entry : bySite.entrySet()) {
            String site = entry.getKey();
            List<StageStatus> siteStatuses = entry.getValue();

            DashboardTotalsAccumulator siteAcc = new DashboardTotalsAccumulator();
            List<DashboardSenderSnapshot> senders = new ArrayList<>();

            for (StageStatus status : siteStatuses) {
                DashboardMetricTotals metrics = toMetrics(status, status.users());
                siteAcc.add(metrics);
                globalAcc.add(metrics);

                String senderLabel = formatSenderLabel(status.senderId(), status.senderName());
                String recordsHref = UriComponentsBuilder
                        .fromPath("/api/dashboard/sites/{site}/senders/{senderId}/records")
                        .buildAndExpand(Map.of("site", site, "senderId", status.senderId()))
                        .toUriString();
                List<DashboardLink> links = List.of(
                        DashboardLink.of("records", recordsHref, MediaType.APPLICATION_JSON_VALUE),
                        DashboardLink.of("records:csv", recordsHref, "text/csv"));
                boolean alert = (status.stagedToRefdb() > 0);
                senders.add(new DashboardSenderSnapshot(
                        status.senderId(),
                        senderLabel,
                        status.senderName(),
                        metrics,
                        alert,
                        links));
            }

            senders.sort(Comparator.comparingLong((DashboardSenderSnapshot s) -> s.metrics().backlog()).reversed());

            DashboardSiteSnapshot siteSnapshot = new DashboardSiteSnapshot(
                    site,
                    siteAcc.toTotals(),
                    senders.stream().anyMatch(DashboardSenderSnapshot::alert),
                    List.copyOf(senders));
            sites.add(siteSnapshot);
        }

        sites.sort(Comparator.comparingLong((DashboardSiteSnapshot s) -> s.metrics().backlog()).reversed());

        return new DashboardSnapshot(Instant.now(), globalAcc.toTotals(), List.copyOf(sites));
    }

    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @GetMapping(value = "/sites/{site}/senders/{senderId}/records", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StageRecordPage> senderRecords(@PathVariable("site") String site,
                                                         @PathVariable("senderId") int senderId,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size,
                                                         @RequestParam(required = false) java.util.List<String> devices) {
        // GET /api/dashboard/sites/{site}/senders/{senderId}/records - Get records with optional device filtering
        // Requirements: 4.2, 7.1, 7.2
        RecordQueryContext ctx = buildQueryContext(site, senderId, status, q, page, size);
        List<StageRecord> records = ctx.records();
        List<StageRecordView> items = records.stream().map(mapper::toView).toList();
        return ResponseEntity.ok(new StageRecordPage(items, ctx.total(), ctx.page(), ctx.size()));
    }

    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @GetMapping(value = "/sites/{site}/senders/{senderId}/records", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> senderRecordsCsv(@PathVariable("site") String site,
                                                                  @PathVariable("senderId") int senderId,
                                                                  @RequestParam(required = false) String status,
                                                                  @RequestParam(required = false) String q,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "50") int size,
                                                                  @RequestParam(required = false) java.util.List<String> devices,
                                                                  HttpServletRequest request) {
        // GET /api/dashboard/sites/{site}/senders/{senderId}/records - Get CSV with optional device filtering
        // Requirements: 4.2, 7.1, 7.2
        RecordQueryContext ctx = buildQueryContext(site, senderId, status, q, page, size);

        boolean acceptsGzip = acceptsGzip(request);

        StreamingResponseBody body = outputStream -> {
            OutputStream target = outputStream;
            BufferedWriter writer = null;
            GZIPOutputStream gzip = null;
            try {
                if (acceptsGzip) {
                    gzip = new GZIPOutputStream(target, true);
                    writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8));
                } else {
                    writer = new BufferedWriter(new OutputStreamWriter(target, StandardCharsets.UTF_8));
                }

                writer.write(
                        "id,site,senderId,senderName,metadataId,dataId,lot,wafer,filename,status,stagedBy,lastRequestedBy,createdAt,updatedAt,processedAt,errorMessage\n");

                int currentPage = 0;
                int pageSize = ctx.size();
                long written = 0;
                while (written < ctx.total()) {
                    List<StageRecord> rows = fetchRecords(site, senderId, status, q, currentPage, pageSize, ctx);
                    if (rows.isEmpty()) {
                        break;
                    }
                    for (StageRecord row : rows) {
                        StageRecordView view = mapper.toView(row);
                        String line = String.join(",",
                                csv(view.id()),
                                csv(view.site()),
                                csv(view.senderId()),
                                csv(view.senderName()),
                                csv(view.metadataId()),
                                csv(view.dataId()),
                                csv(view.lot()),
                                csv(view.wafer()),
                                csv(view.filename()),
                                csv(view.endTime()),
                                csv(view.status()),
                                csv(view.stagedBy()),
                                csv(view.lastRequestedBy()),
                                csv(view.createdAt()),
                                csv(view.updatedAt()),
                                csv(view.processedAt()),
                                csv(view.errorMessage()));
                        writer.write(line);
                        writer.write('\n');
                    }
                    written += rows.size();
                    currentPage++;
                    if (rows.size() < pageSize) {
                        break;
                    }
                }

                writer.flush();
                if (gzip != null) {
                    gzip.finish();
                }
            } catch (Exception ex) {
                log.error("Failed streaming dashboard sender records CSV: {}", ex.getMessage(), ex);
            }
        };

        String filename = "dashboard-records-" + sanitize(site) + "-" + senderId + "-"
                + Instant.now().toString().replace(':', '-') + ".csv" + (acceptsGzip ? ".gz" : "");

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"");
        if (acceptsGzip) {
            headers.add(org.springframework.http.HttpHeaders.CONTENT_ENCODING, "gzip");
        }
        headers.add(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8");

        return ResponseEntity.ok().headers(headers).body(body);
    }

    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @GetMapping(value = "/sites/{site}/senders/{senderId}/lot-breakdown")
    public ResponseEntity<List<DashboardLotBreakdown>> lotBreakdown(@PathVariable("site") String site,
                                                                    @PathVariable("senderId") int senderId,
                                                                    @RequestParam(required = false) String startDate,
                                                                    @RequestParam(required = false) String endDate,
                                                                    @RequestParam(required = false) String q,
                                                                    @RequestParam(defaultValue = "12") int limit,
                                                                    @RequestParam(defaultValue = "end_time") String dateTimeField,
                                                                    @RequestParam(required = false) java.util.List<String> devices) {
        // GET /api/dashboard/sites/{site}/senders/{senderId}/lot-breakdown - Get lot breakdown with optional device filtering
        // Requirements: 4.2, 7.1, 7.2
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority())
                        || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        String username = auth != null ? auth.getName() : null;
        String normalizedUser = StringUtils.hasText(username) ? username.trim().toLowerCase(Locale.ROOT) : null;

        int effectiveLimit = limit <= 0 ? 12 : Math.min(limit, 100);
        // Fetch more rows than requested lots so we capture wafer distribution for
        // each.
        int rowLimit = Math.max(effectiveLimit * 25, 250);

        Instant start = parseDateStart(startDate);
        Instant end = parseDateEnd(endDate);
        String effectiveDateField = normalizeDateTimeField(dateTimeField);

        List<RefDbService.LotWaferAggregate> aggregates;
        if (isAdmin || normalizedUser == null) {
            aggregates = refDbService.aggregateLotWafer(site, senderId, q, rowLimit, start, end, null,
                    effectiveDateField);
        } else {
            aggregates = refDbService.aggregateLotWafer(site, senderId, q, rowLimit, start, end, normalizedUser,
                    effectiveDateField);
        }

        Map<String, LotAccumulator> lotMap = new LinkedHashMap<>();
        for (RefDbService.LotWaferAggregate aggregate : aggregates) {
            String lotKey = lotKey(aggregate.lot());
            LotAccumulator acc = lotMap.computeIfAbsent(lotKey,
                    key -> new LotAccumulator(normalizeValue(aggregate.lot())));
            acc.add(aggregate, normalizeValue(aggregate.wafer()));
        }

        List<DashboardLotBreakdown> lots = lotMap.values().stream()
                .sorted((a, b) -> Long.compare(b.backlog(), a.backlog()))
                .limit(effectiveLimit)
                .map(LotAccumulator::toBreakdown)
                .toList();

        return ResponseEntity.ok(List.copyOf(lots));
    }

    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @GetMapping(value = "/sites/{site}/senders/{senderId}/date-breakdown")
    public ResponseEntity<List<DashboardDateBucket>> dateBreakdown(@PathVariable("site") String site,
                                                                   @PathVariable("senderId") int senderId,
                                                                   @RequestParam(required = false) String startDate,
                                                                   @RequestParam(required = false) String endDate,
                                                                   @RequestParam(defaultValue = "30") int limit,
                                                                   @RequestParam(defaultValue = "end_time") String dateTimeField,
                                                                   @RequestParam(required = false) java.util.List<String> devices) {
        // GET /api/dashboard/sites/{site}/senders/{senderId}/date-breakdown - Get date breakdown with optional device filtering
        // Requirements: 4.2, 7.1, 7.2
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority())
                        || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        String username = auth != null ? auth.getName() : null;
        String normalizedUser = StringUtils.hasText(username) ? username.trim().toLowerCase(Locale.ROOT) : null;

        int effectiveLimit = limit <= 0 ? 30 : Math.min(limit, 365);
        Instant start = parseDateStart(startDate);
        Instant end = parseDateEnd(endDate);
        String effectiveDateField = normalizeDateTimeField(dateTimeField);

        List<RefDbService.TimeBucketAggregate> aggregates;
        if (isAdmin || normalizedUser == null) {
            aggregates = refDbService.aggregateTimeBuckets(site, senderId, start, end, effectiveLimit, null,
                    effectiveDateField);
        } else {
            aggregates = refDbService.aggregateTimeBuckets(site, senderId, start, end, effectiveLimit, normalizedUser,
                    effectiveDateField);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
        List<DashboardDateBucket> buckets = aggregates.stream()
                .filter(agg -> agg.bucket() != null)
                .map(agg -> new DashboardDateBucket(
                        agg.bucket(),
                        formatter.format(agg.bucket()),
                        DashboardBucketTotals.of(agg.stagedToRefdb(), agg.enqueued(), agg.failed(), agg.completed())))
                .toList();

        return ResponseEntity.ok(List.copyOf(buckets));
    }

    private String normalizeDateTimeField(String value) {
        if ("created_time".equalsIgnoreCase(value)) {
            return "created_time";
        }
        return "end_time";
    }

    private String normalizeValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Instant parseDateStart(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private Instant parseDateEnd(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(trimmed).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private String lotKey(String lot) {
        String normalized = normalizeValue(lot);
        return normalized == null ? "__null__" : normalized.toLowerCase(Locale.ROOT);
    }

    private List<StageStatus> resolveStatuses(Authentication auth) {
        if (auth == null) {
            return refDbService.fetchStatuses(null);
        }
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority())
                        || "ROLE_SUPER_ADMIN".equals(a.getAuthority()) || "SUPER_ADMIN".equals(a.getAuthority()));
        String username = auth.getName();
        if (isAdmin || !StringUtils.hasText(username)) {
            return refDbService.fetchStatuses(null);
        }
        return refDbService.fetchStatusesForUser(null, null, username.toLowerCase(Locale.ROOT), null);
    }

    private RecordQueryContext buildQueryContext(String site,
                                                 int senderId,
                                                 String status,
                                                 String q,
                                                 int page,
                                                 int size) {
        if (!StringUtils.hasText(site)) {
            throw new IllegalArgumentException("site is required");
        }
        int resolvedPage = Math.max(page, 0);
        int resolvedSize = size <= 0 ? 50 : Math.min(size, 500);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority())
                        || "ROLE_SUPER_ADMIN".equals(a.getAuthority()) || "SUPER_ADMIN".equals(a.getAuthority()));
        String username = auth != null ? auth.getName() : null;
        String normalizedUser = StringUtils.hasText(username) ? username.trim().toLowerCase(Locale.ROOT) : null;

        int offset = resolvedPage * resolvedSize;
        List<StageRecord> records;
        long total;
        if (StringUtils.hasText(q)) {
            if (isAdmin || normalizedUser == null) {
                records = refDbService.listRecords(site, senderId, status, q, offset, resolvedSize);
                total = refDbService.countRecords(site, senderId, status, q, null);
            } else {
                records = refDbService.listRecordsForUser(site, senderId, status, q, offset, resolvedSize,
                        normalizedUser);
                total = refDbService.countRecordsForUser(site, senderId, status, normalizedUser, q, null);
            }
        } else {
            if (isAdmin || normalizedUser == null) {
                records = refDbService.listRecords(site, senderId, status, offset, resolvedSize);
                total = refDbService.countRecords(site, senderId, status, null, null);
            } else {
                records = refDbService.listRecordsForUser(site, senderId, status, offset, resolvedSize, normalizedUser);
                total = refDbService.countRecordsForUser(site, senderId, status, normalizedUser, null, null);
            }
        }

        return new RecordQueryContext(site, senderId, status, q, resolvedPage, resolvedSize, records, total, isAdmin,
                normalizedUser);
    }

    private List<StageRecord> fetchRecords(String site,
                                           int senderId,
                                           String status,
                                           String q,
                                           int page,
                                           int size,
                                           RecordQueryContext ctx) {
        int offset = page * size;
        if (StringUtils.hasText(q)) {
            if (ctx.isAdmin() || ctx.userKey() == null) {
                return refDbService.listRecords(site, senderId, status, q, offset, size);
            }
            return refDbService.listRecordsForUser(site, senderId, status, q, offset, size, ctx.userKey());
        }
        if (ctx.isAdmin() || ctx.userKey() == null) {
            return refDbService.listRecords(site, senderId, status, offset, size);
        }
        return refDbService.listRecordsForUser(site, senderId, status, offset, size, ctx.userKey());
    }

    private DashboardMetricTotals toMetrics(StageStatus status, List<StageUserStatus> users) {
        long stagedToRefdb = status.stagedToRefdb();
        long queuedForCp = status.queuedForCp();
        long elasticsearchMonitoring = status.elasticsearchMonitoring();
        long cpTimeout = status.cpTimeout();
        long exensioMonitoring = status.exensioMonitoring();
        long completedManualVerification = status.completedManualVerification();
        long cpFailed = status.cpFailed();
        long loadFailed = status.loadFailed();
        long completed = status.completed();
        long cancelled = status.cancelled();
        long backlog = status.backlog();
        long total = status.total();
        long activeUsers = users == null ? 0 : users.size();
        return new DashboardMetricTotals(total, stagedToRefdb, queuedForCp, elasticsearchMonitoring,
                cpTimeout, exensioMonitoring, completedManualVerification,
                cpFailed, loadFailed, completed, cancelled, backlog, 1, activeUsers);
    }

    private String normalizeSite(String site) {
        return StringUtils.hasText(site) ? site : "Unknown";
    }

    private String formatSenderLabel(int senderId, String senderName) {
        if (senderId > 0) {
            return Integer.toString(senderId);
        }
        if (StringUtils.hasText(senderName)) {
            return senderName.trim();
        }
        return "N/A";
    }

    private boolean acceptsGzip(HttpServletRequest request) {
        try {
            String header = request.getHeader("Accept-Encoding");
            return header != null && header.toLowerCase(Locale.ROOT).contains("gzip");
        } catch (Exception ignore) {
            return false;
        }
    }

    private String csv(Object value) {
        String v = value == null ? "" : value.toString();
        String escaped = v.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\r") || escaped.contains("\"")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private String sanitize(String input) {
        if (!StringUtils.hasText(input)) {
            return "unknown";
        }
        return input.replaceAll("[^a-zA-Z0-9-_]", "-");
    }

    private record RecordQueryContext(
            String site,
            int senderId,
            String status,
            String q,
            int page,
            int size,
            List<StageRecord> records,
            long total,
            boolean isAdmin,
            String userKey) {
    }

    private static class LotAccumulator {
        private final String lot;
        private long ready;
        private long enqueued;
        private long failed;
        private long completed;
        private final List<DashboardWaferBreakdown> wafers = new ArrayList<>();

        LotAccumulator(String lot) {
            this.lot = lot;
        }

        void add(RefDbService.LotWaferAggregate aggregate, String wafer) {
            long rowReady = Math.max(0L, aggregate.stagedToRefdb());
            long rowEnqueued = Math.max(0L, aggregate.enqueued());
            long rowFailed = Math.max(0L, aggregate.failed());
            long rowCompleted = Math.max(0L, aggregate.completed());
            ready += rowReady;
            enqueued += rowEnqueued;
            failed += rowFailed;
            completed += rowCompleted;
            DashboardBucketTotals totals = DashboardBucketTotals.of(rowReady, rowEnqueued, rowFailed, rowCompleted);
            wafers.add(new DashboardWaferBreakdown(wafer, totals, aggregate.filename()));
        }

        long backlog() {
            return Math.max(0L, ready + enqueued);
        }

        DashboardLotBreakdown toBreakdown() {
            wafers.sort((a, b) -> Long.compare(b.totals().backlog(), a.totals().backlog()));
            DashboardBucketTotals totals = DashboardBucketTotals.of(ready, enqueued, failed, completed);
            return new DashboardLotBreakdown(lot, totals, List.copyOf(wafers));
        }
    }

    private static class DashboardTotalsAccumulator {
        private long total;
        private long stagedToRefdb;
        private long queuedForCp;
        private long elasticsearchMonitoring;
        private long cpTimeout;
        private long exensioMonitoring;
        private long completedManualVerification;
        private long cpFailed;
        private long loadFailed;
        private long completed;
        private long cancelled;
        private long backlog;
        private long activeSenders;
        private long activeUsers;

        void add(DashboardMetricTotals metrics) {
            this.total += metrics.total();
            this.stagedToRefdb += metrics.stagedToRefdb();
            this.queuedForCp += metrics.queuedForCp();
            this.elasticsearchMonitoring += metrics.elasticsearchMonitoring();
            this.cpTimeout += metrics.cpTimeout();
            this.exensioMonitoring += metrics.exensioMonitoring();
            this.completedManualVerification += metrics.completedManualVerification();
            this.cpFailed += metrics.cpFailed();
            this.loadFailed += metrics.loadFailed();
            this.completed += metrics.completed();
            this.cancelled += metrics.cancelled();
            this.backlog += metrics.backlog();
            this.activeSenders += metrics.activeSenders();
            this.activeUsers += metrics.activeUsers();
        }

        DashboardMetricTotals toTotals() {
            return new DashboardMetricTotals(total, stagedToRefdb, queuedForCp, elasticsearchMonitoring,
                    cpTimeout, exensioMonitoring, completedManualVerification,
                    cpFailed, loadFailed, completed, cancelled, backlog, activeSenders, activeUsers);
        }
    }
}
