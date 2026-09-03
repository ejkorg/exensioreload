package com.onsemi.cim.apps.exensio.exensioreload.controller;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewResponse;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewRow;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewWithDuplicatesResponse;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DispatchRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DispatchResponse;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DuplicatePayloadView;
import com.onsemi.cim.apps.exensio.exensioreload.dto.EnqueueRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckResponse;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckRow;
import com.onsemi.cim.apps.exensio.exensioreload.dto.HistoricalPreviewSummary;
import com.onsemi.cim.apps.exensio.exensioreload.dto.LotVerificationRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.LotVerificationResponse;
import com.onsemi.cim.apps.exensio.exensioreload.dto.LotVerificationResult;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StageAllRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StagePayloadRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StagePayloadResponse;
import com.onsemi.cim.apps.exensio.exensioreload.entity.SenderQueueEntry;
import com.onsemi.cim.apps.exensio.exensioreload.repository.SenderQueueRepository;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioPreCheckCacheService;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioPreCheckService;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioSqlUtilService;
import com.onsemi.cim.apps.exensio.exensioreload.service.MailService;
import com.onsemi.cim.apps.exensio.exensioreload.service.RefDbService;
import com.onsemi.cim.apps.exensio.exensioreload.service.SenderDispatchService;
import com.onsemi.cim.apps.exensio.exensioreload.service.SenderService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.DuplicatePayload;
import com.onsemi.cim.apps.exensio.exensioreload.stage.PayloadCandidate;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageResult;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/senders")
public class SenderController {
    private static final Logger log = LoggerFactory.getLogger(SenderController.class);
    // Caps are configurable via application properties and can be increased without code changes
    @Value("${app.preview.fetch-cap:2000}")
    private int previewFetchCap;
    @Value("${app.stage.page-size-cap:2000}")
    private int stagePageSizeCap;
    @Value("${app.stage.max-rows-cap:50000}")
    private int stageMaxRowsCap;
    @Value("${app.stage.default-max-rows:10000}")
    private int stageDefaultMaxRows;
    private final SenderService senderService;
    private final SenderQueueRepository repo;
    private final com.onsemi.cim.apps.exensio.exensioreload.service.MetadataImporterService metadataImporterService;
    private final com.onsemi.cim.apps.exensio.exensioreload.service.MetricsService metricsService;
    private final RefDbService refDbService;
    private final SenderDispatchService senderDispatchService;
    private final org.springframework.core.env.Environment env;
    private final com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository userRepository;
    private final com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig externalDbConfig;
    private final MailService mailService;
    private final com.onsemi.cim.apps.exensio.exensioreload.service.StageSessionService stageSessionService;
    private final ExensioPreCheckCacheService exensioPreCheckService;
    private final com.onsemi.cim.apps.exensio.exensioreload.service.WaferDiscoveryService waferDiscoveryService;
    private final com.onsemi.cim.apps.exensio.exensioreload.service.ParallelSchemaCheckService parallelSchemaCheckService;

    public SenderController(SenderService senderService, SenderQueueRepository repo, com.onsemi.cim.apps.exensio.exensioreload.service.MetadataImporterService metadataImporterService, com.onsemi.cim.apps.exensio.exensioreload.service.MetricsService metricsService, RefDbService refDbService, SenderDispatchService senderDispatchService, org.springframework.core.env.Environment env, com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository userRepository, com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig externalDbConfig, MailService mailService, com.onsemi.cim.apps.exensio.exensioreload.service.StageSessionService stageSessionService, ExensioPreCheckCacheService exensioPreCheckService, com.onsemi.cim.apps.exensio.exensioreload.service.WaferDiscoveryService waferDiscoveryService, com.onsemi.cim.apps.exensio.exensioreload.service.ParallelSchemaCheckService parallelSchemaCheckService) {
        this.senderService = senderService;
        this.repo = repo;
        this.metadataImporterService = metadataImporterService;
        this.metricsService = metricsService;
        this.refDbService = refDbService;
        this.senderDispatchService = senderDispatchService;
        this.env = env;
        this.userRepository = userRepository;
        this.externalDbConfig = externalDbConfig;
        this.mailService = mailService;
        this.stageSessionService = stageSessionService;
        this.exensioPreCheckService = exensioPreCheckService;
        this.waferDiscoveryService = waferDiscoveryService;
        this.parallelSchemaCheckService = parallelSchemaCheckService;
    }

    // Expose download URL template defined in dbconnections.yml/json for the selected site/environment.
    // Caller replaces {id} with the dataId/id_data from preview rows.
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/{id}/download/url")
    public ResponseEntity<java.util.Map<String, Object>> getDownloadUrlTemplate(@PathVariable("id") Integer id,
                                                                                @RequestParam(required = false, defaultValue = "default") String site,
                                                                                @RequestParam(required = false, defaultValue = "qa") String environment,
                                                                                @RequestParam(required = false, name = "connectionKey") String connectionKey) {
        // Backwards compatibility: allow callers to pass the connection key as either
        // the `site` param or the `connectionKey` param. Normalize into `site` for lookups
        // but retain the original resolved key for diagnostics / client visibility.
        if ((connectionKey == null || connectionKey.isBlank()) && site != null && !site.isBlank()) {
            connectionKey = site;
        } else if ((site == null || site.isBlank()) && connectionKey != null && !connectionKey.isBlank()) {
            site = connectionKey;
        }
        if ((site == null || site.isBlank()) && (connectionKey == null || connectionKey.isBlank())) {
            return ResponseEntity.badRequest().build();
        }
        String resolvedKey = connectionKey != null && !connectionKey.isBlank() ? connectionKey : site;
        log.info("Download URL template request for site='{}' environment='{}' (senderId={}) resolvedKey={}", site, environment, id, resolvedKey);
        String template = externalDbConfig.getDownloadUrlTemplate(site, environment);
        if (template == null || template.isBlank()) {
            log.info("No download URL template found for site='{}' environment='{}' (resolvedKey={})", site, environment, resolvedKey);
            try {
                Map<String, Object> cfg = externalDbConfig.getConfigForSite(site, environment);
                if (cfg != null) {
                    log.info("Resolved config map for site='{}' env='{}' contains keys={}", site, environment, cfg.keySet());
                    log.info("downloadUrl property present={}", cfg.containsKey("downloadUrl"));
                } else {
                    try {
                        java.util.Set<String> known = externalDbConfig.getConfiguredKeys();
                        log.info("No config object resolved for site='{}' env='{}'. Known configured keys ({}): {}", site, environment, known.size(), known);
                    } catch (Exception e) {
                        log.info("No config object resolved for site='{}' env='{}' and failed to list known keys: {}", site, environment, e.toString());
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to inspect external DB config for site='{}' env='{}'", site, environment, ex);
            }
            java.util.Map<String, Object> err = new java.util.HashMap<>();
            err.put("error", "No download URL configured for this site/environment");
            err.put("resolvedKey", resolvedKey);
            return ResponseEntity.status(HttpServletResponse.SC_NOT_FOUND).body(err);
        }
        // Determine whether direct downloads are allowed for this config.
        boolean direct = false;
        try {
            Map<String, Object> cfg = externalDbConfig.getConfigForSite(site, environment);
            if (cfg != null && (cfg.containsKey("downloadDirect") || cfg.containsKey("downloadUrlDirect"))) {
                Object v = cfg.containsKey("downloadDirect") ? cfg.get("downloadDirect") : cfg.get("downloadUrlDirect");
                if (v instanceof Boolean) direct = (Boolean) v;
                else if (v instanceof String) direct = Boolean.parseBoolean((String) v);
            }
        } catch (Exception ignored) {}
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("template", template);
        out.put("direct", direct);
        out.put("resolvedKey", resolvedKey);
        log.info("Resolved download template for site='{}' environment='{}' templatePresent={} direct={}", site, environment, template != null && !template.isBlank(), direct);
        return ResponseEntity.ok(out);
    }

    // Stream download through backend to avoid browser popup/CORS issues and preserve auth.
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/{id}/download")
    public void proxyDownload(@PathVariable("id") Integer id,
                              @RequestParam("dataId") String dataId,
                              @RequestParam(required = false, defaultValue = "default") String site,
                              @RequestParam(required = false, defaultValue = "qa") String environment,
                              @RequestParam(required = false, name = "filename") String filename,
                              HttpServletResponse response) throws java.io.IOException {
        if (dataId == null || dataId.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "dataId is required");
            return;
        }
        if (site == null || site.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "site is required");
            return;
        }

        String template = externalDbConfig.getDownloadUrlTemplate(site, environment);
        if (template == null || template.isBlank()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No download URL configured for this site/environment");
            return;
        }

        String resolvedUrl = template.replace("{id}", URLEncoder.encode(dataId.trim(), StandardCharsets.UTF_8));
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(resolvedUrl).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "*/*");
            conn.connect();

            int status = conn.getResponseCode();
            if (status >= 400) {
                String errBody = readError(conn);
                response.reset();
                response.sendError(status, errBody != null && !errBody.isBlank() ? errBody : "Upstream download failed");
                return;
            }

            String contentType = conn.getContentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            response.setContentType(contentType);
            long len = conn.getContentLengthLong();
            if (len >= 0) {
                response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(len));
            }

            String name = filename;
            String cdHeader = conn.getHeaderField("Content-Disposition");
            if ((name == null || name.isBlank()) && cdHeader != null) {
                try {
                    name = ContentDisposition.parse(cdHeader).getFilename();
                } catch (Exception ignored) {}
            }
            if (name == null || name.isBlank()) {
                name = dataId;
            }
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString());

            try (InputStream in = conn.getInputStream(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        } catch (Exception ex) {
            log.warn("Failed to proxy download for sender {} dataId {} url {}", id, dataId, resolvedUrl, ex);
            if (!response.isCommitted()) {
                response.reset();
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to download file");
            }
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/{id}/download/batch")
    public void batchDownload(@PathVariable("id") Integer id,
                              @RequestBody List<DiscoveryPreviewRow> items,
                              @RequestParam(required = false, defaultValue = "default") String site,
                              @RequestParam(required = false, defaultValue = "qa") String environment,
                              HttpServletResponse response) throws java.io.IOException {
        if (items == null || items.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No items to download");
            return;
        }
        String template = externalDbConfig.getDownloadUrlTemplate(site, environment);
        if (template == null || template.isBlank()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No download URL configured for this site/environment");
            return;
        }

        response.setContentType("application/zip");
        String zipName = "batch-download-" + java.time.Instant.now().toString().replace(':', '-') + ".zip";
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(zipName).build().toString());

        java.util.Set<String> usedNames = new java.util.HashSet<>();

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream())) {
            for (DiscoveryPreviewRow item : items) {
                String downloadId = item.metadataId();
                if (downloadId == null || downloadId.isBlank()) {
                    downloadId = item.dataId();
                }
                if (downloadId == null || downloadId.isBlank()) continue;

                String baseName = item.originalFileName();
                if (baseName == null || baseName.isBlank()) {
                    baseName = downloadId + ".dat";
                }

                String fname = baseName;
                int counter = 1;
                while (usedNames.contains(fname)) {
                    // insert counter before extension
                    int dot = baseName.lastIndexOf('.');
                    if (dot > 0) {
                        fname = baseName.substring(0, dot) + "_" + counter + baseName.substring(dot);
                    } else {
                        fname = baseName + "_" + counter;
                    }
                    counter++;
                }
                usedNames.add(fname);

                String resolvedUrl = template.replace("{id}", URLEncoder.encode(downloadId.trim(), StandardCharsets.UTF_8));

                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(resolvedUrl).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(60000);
                    conn.connect();

                    if (conn.getResponseCode() >= 400) {
                        log.warn("Batch download failed for item {} (status {})", downloadId, conn.getResponseCode());
                        zos.putNextEntry(new java.util.zip.ZipEntry(fname + ".error.txt"));
                        zos.write(("Failed to download. Status: " + conn.getResponseCode()).getBytes(StandardCharsets.UTF_8));
                        zos.closeEntry();
                        continue;
                    }

                    zos.putNextEntry(new java.util.zip.ZipEntry(fname));
                    try (InputStream in = conn.getInputStream()) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                } catch (Exception e) {
                    log.error("Error downloading item {} in batch: {}", downloadId, e.getMessage());
                    try {
                        zos.putNextEntry(new java.util.zip.ZipEntry(fname + ".error.txt"));
                        zos.write(("Failed to download: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                        zos.closeEntry();
                    } catch (Exception ignore) {}
                }
            }
            zos.finish();
        } catch (Exception ex) {
            log.error("Failed to stream batch zip", ex);
        }
    }

    @GetMapping("/{id}/queue")
    public List<SenderQueueEntry> getQueue(@PathVariable("id") Integer id,
                                           @RequestParam(defaultValue = "STAGED") String status,
                                           @RequestParam(defaultValue = "100") int limit) {
        return senderService.getQueue(id, status, limit);
    }

    @GetMapping("/{id}/queue/count")
    public ResponseEntity<java.util.Map<String, Long>> getQueueCount(@PathVariable("id") Integer id,
                                                                     @RequestParam(required = false, defaultValue = "default") String site,
                                                                     @RequestParam(required = false, defaultValue = "qa") String environment,
                                                                     @RequestParam(required = false, name = "connectionKey") String connectionKey) {
        // Backwards compatibility: allow callers to pass the connection key as either
        // the `site` param or the `connectionKey` param. Normalize into `site` for lookups.
        if ((connectionKey == null || connectionKey.isBlank()) && site != null && !site.isBlank()) {
            connectionKey = site;
        } else if ((site == null || site.isBlank()) && connectionKey != null && !connectionKey.isBlank()) {
            site = connectionKey;
        }

        long count = 0;
        try (java.sql.Connection conn = externalDbConfig.getConnection(site, environment)) {
            if (conn != null) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM dtp_sender_queue_item WHERE id_sender = ?")) {
                    ps.setInt(1, id);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            count = rs.getLong(1);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to fetch external queue count for sender {} site {} env {}: {}", id, site, environment, ex.getMessage());
        }

        java.util.Map<String, Long> response = new java.util.HashMap<>();
        response.put("senderId", (long) id);
        response.put("count", count);
        return ResponseEntity.ok(response);
    }

    // Run now: triggers scheduled logic immediately for testing / on-demand
    @PostMapping("/{id}/run")
    public ResponseEntity<String> runNow(@PathVariable("id") Integer id,
                                         @RequestParam(defaultValue = "false") boolean preview,
                                         @RequestParam(defaultValue = "100") int limit) {
        if (preview) {
            return ResponseEntity.ok("Preview - no items processed");
        }
        senderService.processBatch(limit);
        return ResponseEntity.accepted().body("Run started");
    }

    // New endpoint: enqueue payloads from UI form (Reload / Submit)
    @PostMapping("/{id}/enqueue")
    public ResponseEntity<com.onsemi.cim.apps.exensio.exensioreload.dto.EnqueueResult> enqueue(@PathVariable("id") Integer id, @RequestBody EnqueueRequest req) {
        Integer senderId = id;
        if (req.getSenderId() != null) senderId = req.getSenderId();
        SenderService.EnqueueResultHolder holder = senderService.enqueuePayloadsWithResult(senderId, req.getPayloadIds(), req.getSource() != null ? req.getSource() : "ui_submit");
        com.onsemi.cim.apps.exensio.exensioreload.dto.EnqueueResult result = new com.onsemi.cim.apps.exensio.exensioreload.dto.EnqueueResult(holder.enqueuedCount, holder.skippedPayloads, holder.pendingBefore, holder.pendingAfter);
        return ResponseEntity.ok(result);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @PostMapping("/{id}/dispatch")
    public ResponseEntity<DispatchResponse> dispatch(@PathVariable("id") Integer id, @RequestBody DispatchRequest request) {
        if (request == null || request.site() == null || request.site().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Integer resolvedSender = request.senderId() != null ? request.senderId() : id;
        if (resolvedSender == null || resolvedSender <= 0) {
            return ResponseEntity.badRequest().build();
        }
        int dispatched = senderDispatchService.dispatchSender(request.site(), resolvedSender, request.limit());
        return ResponseEntity.ok(new DispatchResponse(request.site(), resolvedSender, dispatched));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/{id}/discover")
    public ResponseEntity<String> discover(@PathVariable("id") Integer id,
                                           @RequestParam(defaultValue = "default") String site,
                                           @RequestParam(defaultValue = "qa") String environment,
                                           @RequestParam(required = false) String startDate,
                                           @RequestParam(required = false) String endDate,
                                           @RequestParam(required = false) String testerType,
                                           @RequestParam(required = false) String dataType,
                                           @RequestParam(required = false) String dataTypeExt,
                                           @RequestParam(required = false) String testPhase,
                                           // legacy 'location' parameter is the metadata filter (kept for compatibility)
                                           @RequestParam(required = false) String location,
                                           // new explicit parameter name to clarify intent: the metadata filter to use in the external query
                                           @RequestParam(required = false, name = "metadataLocation") String metadataLocation,
                                           // the saved ExternalLocation id which selects which DB connection to use
                                           @RequestParam(required = false, name = "locationId") Long locationId,
                                           @RequestParam(defaultValue = "false") boolean writeListFile,
                                           @RequestParam(defaultValue = "300") int numberOfDataToSend,
                                           @RequestParam(defaultValue = "600") int countLimitTrigger,
                                           @RequestParam(required = false) String requestId) {

        // Prefer explicit metadataLocation if supplied; fall back to legacy 'location' param for compatibility.
        String filterLocation = metadataLocation != null ? metadataLocation : location;

        // If a saved locationId is provided and no explicit site was supplied, try to set 'site' from the saved ExternalLocation
        if (locationId != null && (site == null || site.equals("default") || site.isBlank())) {
            com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = metadataImporterService.findLocationById(locationId);
            if (loc != null && loc.getSite() != null && !loc.getSite().isBlank()) {
                site = loc.getSite();
            }
        }
        // Validate discovery input: require both start+end dates when provided, or a lot filter.
        try {
            metadataImporterService.validatePreviewRequest(site, id, startDate, endDate, null, null, testerType);
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            throw rse;
        }

        int added = metadataImporterService.discoverAndEnqueue(site, environment, id, startDate, endDate, testerType, dataType, dataTypeExt, testPhase, filterLocation, locationId, writeListFile, numberOfDataToSend, countLimitTrigger, requestId);
        return ResponseEntity.ok("Discovered and staged " + added + " payloads");
    }

    // Accept JSON body for initial discover requests so clients can send structured params
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping(value = "/{id}/discover", consumes = "application/json")
    public ResponseEntity<String> discoverWithBody(@PathVariable("id") Integer id,
                                                   @RequestBody DiscoveryPreviewRequest request,
                                                   @RequestParam(defaultValue = "default") String site,
                                                   @RequestParam(defaultValue = "qa") String environment,
                                                   @RequestParam(required = false) String metadataLocation,
                                                   @RequestParam(required = false, name = "locationId") Long locationId,
                                                   @RequestParam(defaultValue = "false") boolean writeListFile,
                                                   @RequestParam(defaultValue = "300") int numberOfDataToSend,
                                                   @RequestParam(defaultValue = "600") int countLimitTrigger,
                                                   @RequestParam(required = false) String requestId) {

        // Prefer explicit metadataLocation if supplied; fall back to legacy 'location' field from request
        String filterLocation = metadataLocation != null ? metadataLocation : request.location();

        // If a saved locationId is provided and no explicit site was supplied, try to set 'site' from the saved ExternalLocation
        if (locationId != null && (site == null || site.equals("default") || site.isBlank())) {
            com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = metadataImporterService.findLocationById(locationId);
            if (loc != null && loc.getSite() != null && !loc.getSite().isBlank()) {
                site = loc.getSite();
            }
        }

        String startDate = request.startDate();
        String endDate = request.endDate();
        String testerType = request.testerType();
        String dataType = request.dataType();
        String dataTypeExt = request.dataTypeExt();
        String testPhase = request.testPhase();

        // Validate discovery input: require both start+end dates when provided, or a lot filter.
        try {
            metadataImporterService.validatePreviewRequest(site, id, startDate, endDate, request.lots(), request.wafers(), testerType);
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            throw rse;
        }

        // Prefer requestId from JSON body if present, otherwise use query param
        String resolvedRequestId = request.requestId() != null ? request.requestId() : requestId;
        int added = metadataImporterService.discoverAndEnqueue(site, environment, id, startDate, endDate, testerType, dataType, dataTypeExt, testPhase, filterLocation, locationId, writeListFile, numberOfDataToSend, countLimitTrigger, resolvedRequestId);
        return ResponseEntity.ok("Discovered and staged " + added + " payloads");
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/{id}/discover/preview")
    public ResponseEntity<DiscoveryPreviewResponse> preview(@PathVariable("id") Integer id,
                                                            @RequestBody DiscoveryPreviewRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Preview request for sender={} site={} location={} dataType={} dataTypeExt={} testerType={} testPhase={} start={} end={} page={} size={}"
                    , id, request.site(), request.location(), request.dataType(), request.dataTypeExt(), request.testerType(), request.testPhase(), request.startDate(), request.endDate(), request.page(), request.size());
        }
        boolean isAdmin = currentUserIsAdmin();
        PreviewFilters filters = normalizePreviewFilters(request, isAdmin, id);

        // Pre-validate the preview request to fail fast for invalid date ranges/formats
        try {
            metadataImporterService.validatePreviewRequest(request.site(), id, filters.startDate(), filters.endDate(), filters.lots(), filters.wafers(), request.testerType());
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            throw rse; // Let Spring translate into a 400 response
        }

        boolean hasLotFilter = filters.lots() != null && filters.lots().stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasWaferFilter = filters.wafers() != null && filters.wafers().stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasDateRange = filters.startDate() != null && filters.endDate() != null;
        boolean strictFilters = request.historicalMode() && hasDateRange && !hasLotFilter && !hasWaferFilter;

        DiscoveryPreviewResponse response = metadataImporterService.previewMetadata(
                request.site(),
                request.environment(),
                id,
                filters.startDate(),
                filters.endDate(),
                filters.lots(),
                filters.wafers(),
                filters.devices(),
                request.testerType(),
                request.dataType(),
                request.dataTypeExt(),
                request.testPhase(),
                request.location(),
                request.locationId(),
                request.page(),
                request.size(),
                /* strictFilters */ strictFilters,
                request.bypassCap(),
                filters.steps(),
                filters.recipes(),
                filters.equipmentIds());
        if (log.isInfoEnabled()) {
            log.info("Preview response rows={} total={} strictFilters={} historicalMode={}", response.items().size(), response.total(), strictFilters, request.historicalMode());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Optimized combined preview endpoint that returns both preview rows AND duplicate information
     * in a single HTTP response. This reduces round-trips from 2 to 1.
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/{id}/discover/preview-with-duplicates")
    public ResponseEntity<DiscoveryPreviewWithDuplicatesResponse> previewWithDuplicates(
            @PathVariable("id") Integer id,
            @RequestBody DiscoveryPreviewRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Preview with duplicates request for sender={} site={} location={} dataType={} dataTypeExt={} testerType={} testPhase={} page={} size={} (forcing fetchSize={} page=0)",
                    id, request.site(), request.location(), request.dataType(), request.dataTypeExt(), request.testerType(), request.testPhase(), request.page(), request.size(), previewFetchCap);
        }

        boolean isAdmin = currentUserIsAdmin();
        PreviewFilters filters = normalizePreviewFilters(request, isAdmin, id);

        // Pre-validate the preview-with-duplicates request to fail fast for invalid date ranges/formats
        try {
            metadataImporterService.validatePreviewRequest(request.site(), id, filters.startDate(), filters.endDate(), filters.lots(), filters.wafers(), request.testerType());
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            throw rse;
        }

        long previewStartNanos = System.nanoTime();
        // Always fetch the full preview batch so the UI can paginate client-side.
        // Respect the caller's bypassCap and requested size when provided.
        int fetchPage = 0;
        int fetchSize = request.bypassCap() ? (request.size() > 0 ? request.size() : previewFetchCap) : previewFetchCap;
        boolean hasLotFilter = filters.lots() != null && filters.lots().stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasWaferFilter = filters.wafers() != null && filters.wafers().stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasDateRange = filters.startDate() != null && filters.endDate() != null;
        boolean strictFilters = request.historicalMode() && hasDateRange && !hasLotFilter && !hasWaferFilter;

        DiscoveryPreviewResponse previewResponse = metadataImporterService.previewMetadata(
                request.site(),
                request.environment(),
                id,
                filters.startDate(),
                filters.endDate(),
                filters.lots(),
                filters.wafers(),
                filters.devices(),
                request.testerType(),
                request.dataType(),
                request.dataTypeExt(),
                request.testPhase(),
                request.location(),
                request.locationId(),
                fetchPage,
                fetchSize,
                /* strictFilters */ strictFilters,
                request.bypassCap(),
                filters.steps(),
                filters.recipes(),
                filters.equipmentIds());
        long previewDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - previewStartNanos);

        // Now check for duplicates in parallel for all items
        java.util.Map<String, DuplicatePayloadView> duplicatesMap = new java.util.HashMap<>();
        long duplicateDurationMs = 0L;
        if (previewResponse.items() != null && !previewResponse.items().isEmpty()) {
            long duplicateStartNanos = System.nanoTime();
            java.util.List<PayloadCandidate> candidates = previewResponse.items().stream()
                    .filter(row -> row.metadataId() != null && row.dataId() != null)
                    .map(row -> new PayloadCandidate(row.metadataId().trim(), row.dataId().trim(), null, null, null, null))
                    .filter(candidate -> !candidate.metadataId().isEmpty() && !candidate.dataId().isEmpty())
                    .toList();

            java.util.Map<String, DuplicatePayload> duplicates = refDbService.findDuplicatePayloads(request.site(), id, candidates);
            if (duplicates != null && !duplicates.isEmpty()) {
                for (java.util.Map.Entry<String, DuplicatePayload> entry : duplicates.entrySet()) {
                    duplicatesMap.put(entry.getKey(), toDuplicateView(entry.getValue()));
                }
            }
            duplicateDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - duplicateStartNanos);
        }

        // Discovery Cache logic for Stage-All operations
        String discoveryToken = null;
        if (previewResponse.total() > 0 && previewResponse.total() <= stageMaxRowsCap) {
            discoveryToken = java.util.UUID.randomUUID().toString();
            if (previewResponse.returned() == previewResponse.total()) {
                // We already fetched all of them, cache synchronously and immediately
                metadataImporterService.putCachedDiscoveryResults(discoveryToken, previewResponse.items(), request.dataType());
            } else {
                // We only fetched a page. Kick off a background thread to fetch the full set
                // so when the user clicks 'Stage All' a few seconds later, it's ready.
                final String asyncToken = discoveryToken;
                final Integer asyncSenderId = id;
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        log.info("Preview returned {} but total is {}, caching full results in background for token={}", previewResponse.returned(), previewResponse.total(), asyncToken);
                        DiscoveryPreviewResponse fullResponse = metadataImporterService.previewMetadata(
                                request.site(), request.environment(), asyncSenderId,
                                filters.startDate(), filters.endDate(), filters.lots(), filters.wafers(), filters.devices(),
                                request.testerType(), request.dataType(), request.dataTypeExt(), request.testPhase(),
                                request.location(), request.locationId(),
                                0, (int) previewResponse.total(), // fetch all
                                strictFilters, true, // bypass cap
                                filters.steps(), filters.recipes(), filters.equipmentIds()
                        );
                        if (fullResponse != null && fullResponse.items() != null) {
                            metadataImporterService.putCachedDiscoveryResults(asyncToken, fullResponse.items(), request.dataType());
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to cache full discovery results in background: {}", ex.getMessage());
                    }
                });
            }
        }

        DiscoveryPreviewWithDuplicatesResponse combined = new DiscoveryPreviewWithDuplicatesResponse(
                previewResponse.items(),
                previewResponse.total(),
                previewResponse.returned(),
                previewResponse.page(),
                previewResponse.size(),
                previewResponse.debugSql(),
                previewResponse.capped(),
                previewResponse.bypass(),
                previewResponse.message(),
                duplicatesMap,
                previewDurationMs,
                duplicateDurationMs,
                discoveryToken
        );

        if (log.isInfoEnabled()) {
            log.info("Preview with duplicates response rows={} total={} duplicates={} previewMs={} duplicateMs={} token={}",
                    combined.items().size(), combined.total(), duplicatesMap.size(), previewDurationMs, duplicateDurationMs, discoveryToken != null ? "yes" : "no");
        }
        return ResponseEntity.ok(combined);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @PostMapping("/{id}/discover/historical-summary")
    public ResponseEntity<HistoricalPreviewSummary> historicalSummary(@PathVariable("id") Integer id,
                                                                      @RequestBody DiscoveryPreviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }

        java.util.List<String> lotsParam = request.lots();
        java.util.List<String> wafersParam = request.wafers();
        try {
            if (request.pairs() != null && !request.pairs().isEmpty()) {
                java.util.List<String> l = new java.util.ArrayList<>();
                java.util.List<String> w = new java.util.ArrayList<>();
                for (com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewPair p : request.pairs()) {
                    String lotVal = p == null ? null : p.lot();
                    String waferVal = p == null ? null : p.wafer();
                    lotVal = (lotVal == null || lotVal.isBlank()) ? null : lotVal;
                    waferVal = (waferVal == null || waferVal.isBlank()) ? null : waferVal;
                    l.add(lotVal);
                    w.add(waferVal);
                }
                lotsParam = l;
                wafersParam = w;
            }
        } catch (Exception ex) {
            try { log.warn("Failed parsing pairs param for historical summary: {}", ex.getMessage()); } catch (Exception ignore) {}
        }

        boolean isAdmin = currentUserIsAdmin();
        DateMode dateMode = normalizeDateAndMode(
                request.startDate(),
                request.endDate(),
                Boolean.TRUE.equals(request.historicalMode()),
                isAdmin,
                "historical-summary",
                id
        );

        // Validate request using the same rules as preview/stage-all
        metadataImporterService.validatePreviewRequest(request.site(), id, dateMode.startDate(), dateMode.endDate(), lotsParam, wafersParam, request.testerType());

        java.util.List<String> devices = isAdmin ? request.devices() : null;

        var summary = metadataImporterService.summarizePreview(
                request.site(),
                request.environment(),
                id,
                dateMode.startDate(),
                dateMode.endDate(),
                lotsParam,
                wafersParam,
                devices,
                request.testerType(),
                request.dataType(),
                request.dataTypeExt(),
                request.testPhase(),
                request.location(),
                dateMode.historicalMode());

        String message = summary.total() == 0 ? "No results match the current filters." : null;
        HistoricalPreviewSummary payload = new HistoricalPreviewSummary(
                summary.total(),
                toIso(summary.oldestEndTime()),
                toIso(summary.latestEndTime()),
                message
        );
        return ResponseEntity.ok(payload);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/{id}/discover/stage-all")
    public ResponseEntity<StagePayloadResponse> stageAllMatching(@PathVariable("id") Integer id,
                                                                 @RequestBody StageAllRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String site = request.site();
        if (site == null || site.isBlank()) {
            throw new IllegalArgumentException("site is required");
        }
        Integer resolvedSender = request.senderId() != null ? request.senderId() : id;
        if (resolvedSender == null || resolvedSender <= 0) {
            throw new IllegalArgumentException("senderId is required");
        }
        int resolvedPageSize = request.pageSize() != null && request.pageSize() > 0 ? (request.bypassCap() ? request.pageSize() : Math.min(request.pageSize(), stagePageSizeCap)) : 500;
        int resolvedMaxRows = request.maxRows() != null && request.maxRows() > 0 ? (request.bypassCap() ? request.maxRows() : Math.min(request.maxRows(), stageMaxRowsCap)) : stageDefaultMaxRows;
        if (resolvedMaxRows < resolvedPageSize) {
            resolvedMaxRows = resolvedPageSize;
        }

        java.util.List<String> lotsParam = request.lots();
        java.util.List<String> wafersParam = request.wafers();
        try {
            if (request.pairs() != null && !request.pairs().isEmpty()) {
                java.util.List<String> l = new java.util.ArrayList<>();
                java.util.List<String> w = new java.util.ArrayList<>();
                for (com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewPair p : request.pairs()) {
                    String lotVal = p == null ? null : p.lot();
                    String waferVal = p == null ? null : p.wafer();
                    lotVal = (lotVal == null || lotVal.isBlank()) ? null : lotVal;
                    waferVal = (waferVal == null || waferVal.isBlank()) ? null : waferVal;
                    l.add(lotVal);
                    w.add(waferVal);
                }
                lotsParam = l;
                wafersParam = w;
            }
        } catch (Exception ex) {
            try { log.warn("Failed parsing pairs param for stage-all: {}", ex.getMessage()); } catch (Exception ignore) {}
        }

        boolean isAdmin = currentUserIsAdmin();
        DateMode dateMode = normalizeDateAndMode(
                request.startDate(),
                request.endDate(),
                request.historicalMode(),
                isAdmin,
                "stage-all",
                resolvedSender
        );

        // Validate request before paging through preview results
        try {
            metadataImporterService.validatePreviewRequest(site, resolvedSender, dateMode.startDate(), dateMode.endDate(), lotsParam, wafersParam, request.testerType());
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            throw rse;
        }

        java.util.List<StagePayloadRequest.Payload> payloads = new java.util.ArrayList<>();
        long totalAvailable = 0L;
        int pagesFetched = 0;

        // Calculate strictFilters using same logic as preview endpoint
        boolean hasLotFilter = lotsParam != null && lotsParam.stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasWaferFilter = wafersParam != null && wafersParam.stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasDateRange = dateMode.startDate() != null && dateMode.endDate() != null;
        boolean strictFilters = dateMode.historicalMode() && hasDateRange && !hasLotFilter && !hasWaferFilter;

        // Optimization: Use cached discovery results if a valid token is provided
        java.util.List<DiscoveryPreviewRow> cachedRows = metadataImporterService.getCachedDiscoveryResults(request.discoveryToken());
        if (cachedRows != null && !cachedRows.isEmpty()) {
            totalAvailable = cachedRows.size();
            for (DiscoveryPreviewRow row : cachedRows) {
                if (row == null) continue;
                String metadataId = row.metadataId();
                String dataId = row.dataId();
                if (metadataId == null || metadataId.isBlank() || dataId == null || dataId.isBlank()) continue;

                payloads.add(new StagePayloadRequest.Payload(
                        metadataId.trim(),
                        dataId.trim(),
                        row.lot(),
                        row.wafer(),
                        row.originalFileName(),
                        row.endTime(),
                        row.device()
                ));
                if (payloads.size() >= resolvedMaxRows) {
                    break;
                }
            }
        } else {
            // Fallback: Single optimized DB query instead of paginated loop
            try {
                DiscoveryPreviewResponse resp = metadataImporterService.previewMetadata(
                        site, request.environment(), resolvedSender,
                        dateMode.startDate(), dateMode.endDate(), lotsParam, wafersParam, request.devices(),
                        request.testerType(), request.dataType(), request.dataTypeExt(), request.testPhase(),
                        request.location(), request.locationId(),
                        0, resolvedMaxRows, strictFilters, true, // bypassCap = true
                        request.steps(), request.recipes(), request.equipmentIds());
                if (resp != null) {
                    totalAvailable = resp.total();
                    pagesFetched = 1;
                    if (resp.items() != null) {
                        for (DiscoveryPreviewRow row : resp.items()) {
                            if (row == null) continue;
                            String metadataId = row.metadataId();
                            String dataId = row.dataId();
                            if (metadataId == null || metadataId.isBlank() || dataId == null || dataId.isBlank()) continue;

                            payloads.add(new StagePayloadRequest.Payload(
                                    metadataId.trim(), dataId.trim(), row.lot(), row.wafer(),
                                    row.originalFileName(), row.endTime(), row.device()
                            ));
                            if (payloads.size() >= resolvedMaxRows) break;
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Stage-all single fallback query failed: {}", ex.getMessage());
            }
        }

        if (payloads.isEmpty()) {
            if (log.isInfoEnabled()) {
                log.info("Stage-all request produced no payloads for sender={} site={} location={} dataType={} lots={} wafers={}",
                        resolvedSender, site, request.location(), request.dataType(),
                        lotsParam != null ? lotsParam.size() : 0,
                        wafersParam != null ? wafersParam.size() : 0);
            }
            return ResponseEntity.ok(new StagePayloadResponse(0, 0, java.util.List.of(), 0, false, 0, false, 0));
        }

        boolean truncated = totalAvailable > 0 && payloads.size() < totalAvailable;
        if (log.isInfoEnabled()) {
            log.info("Stage-all collected {} payloads (pageSize={} pages={} total={} truncated={})",
                    payloads.size(), resolvedPageSize, pagesFetched, totalAvailable, truncated);
        }

        StagePayloadRequest proxyRequest = new StagePayloadRequest(
                site,
                request.environment(),
                resolvedSender,
                request.senderName(),
                payloads,
                request.triggerDispatch(),
                request.forceDuplicates(),
                request.userEmail(),
                request.requestId(),
                request.dataType(),
                request.testPhase()
        );
        StagePayloadResponse partial = stagePayloads(id, proxyRequest).getBody();
        if (partial == null) {
            return ResponseEntity.ok(new StagePayloadResponse(0, 0, java.util.List.of(), 0, false, 0, false, 0));
        }

        // Send email notification if user email is provided
        if (request.userEmail() != null && !request.userEmail().isBlank()) {
            try {
                String subject = String.format("Reloader: Staging Complete for Sender %s", resolvedSender);
                StringBuilder body = new StringBuilder();
                body.append("Staging operation completed.\n\n");
                body.append(String.format("Site: %s\n", site));
                body.append(String.format("Sender ID: %d\n", resolvedSender));
                body.append(String.format("User: %s\n", request.userEmail()));
                body.append(String.format("Total Found: %d\n", totalAvailable));
                body.append(String.format("Staged: %d\n", partial.staged()));
                body.append(String.format("Duplicates: %d\n", partial.duplicates()));
                body.append(String.format("Dispatched: %d\n", partial.dispatched()));
                if (truncated) {
                    body.append("\nNote: Result was truncated due to size limits.\n");
                }

                mailService.send(request.userEmail(), subject, body.toString());
            } catch (Exception e) {
                log.error("Failed to send staging completion email to {}", request.userEmail(), e);
            }
        }

        return ResponseEntity.ok(new StagePayloadResponse(
                partial.staged(),
                partial.duplicates(),
                partial.duplicatePayloads(),
                partial.dispatched(),
                partial.requiresConfirmation(),
                totalAvailable,
                truncated,
                partial.requeued()
        ));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping(path = "/{id}/discover/preview/csv", produces = "text/csv")
    public ResponseEntity<ByteArrayResource> previewCsv(@PathVariable("id") Integer id,
                                                        @RequestBody DiscoveryPreviewRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Preview CSV request for sender={} site={} location={} dataType={} testerType={} testPhase={} start={} end={}",
                    id, request.site(), request.location(), request.dataType(), request.testerType(), request.testPhase(), request.startDate(), request.endDate());
        }

        boolean isAdmin = currentUserIsAdmin();
        PreviewFilters filters = normalizePreviewFilters(request, isAdmin, id);

        // Validate CSV preview request up-front to avoid iterating DB when input invalid
        try {
            metadataImporterService.validatePreviewRequest(request.site(), id, filters.startDate(), filters.endDate(), filters.lots(), filters.wafers(), request.testerType());
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            throw rse;
        }

        // Page through results and collect all rows to export
        int page = 0;
        int pageSize = request.size() > 0 ? request.size() : 1000;
        java.util.List<com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewRow> collected = new java.util.ArrayList<>();
        long total = Long.MAX_VALUE;
        try {
            while (true) {
                boolean hasLotFilter = filters.lots() != null && filters.lots().stream().anyMatch(v -> v != null && !v.isBlank());
                boolean hasWaferFilter = filters.wafers() != null && filters.wafers().stream().anyMatch(v -> v != null && !v.isBlank());
                boolean hasDateRange = filters.startDate() != null && filters.endDate() != null;
                boolean strictFilters = request.historicalMode() && hasDateRange && !hasLotFilter && !hasWaferFilter;

                com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewResponse resp = metadataImporterService.previewMetadata(
                        request.site(), request.environment(), id, filters.startDate(), filters.endDate(), filters.lots(), filters.wafers(), filters.devices(),
                        request.testerType(), request.dataType(), request.dataTypeExt(), request.testPhase(), request.location(), request.locationId(), page, pageSize,
                        /* strictFilters */ strictFilters,
                        request.bypassCap(),
                        filters.steps(), filters.recipes(), filters.equipmentIds());
                if (resp == null) break;
                if (total == Long.MAX_VALUE) total = resp.total();
                if (resp.items() != null && !resp.items().isEmpty()) {
                    collected.addAll(resp.items());
                }
                if (collected.size() >= total) break;
                if (resp.items() == null || resp.items().isEmpty()) break;
                page++;
            }
        } catch (Exception ex) {
            log.error("Failed generating preview CSV: {}", ex.getMessage(), ex);
            return ResponseEntity.status(500).body(null);
        }

        // Build CSV content
        StringBuilder sb = new StringBuilder();
        sb.append("metadataId,dataId,lot,wafer,device,originalFileName,endTime\n");
        for (com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewRow r : collected) {
            sb.append(csvEscape(r.metadataId())).append(',');
            sb.append(csvEscape(r.dataId())).append(',');
            sb.append(csvEscape(r.lot())).append(',');
            sb.append(csvEscape(r.wafer())).append(',');
            sb.append(csvEscape(r.device())).append(',');
            sb.append(csvEscape(r.originalFileName())).append(',');
            sb.append(csvEscape(r.endTime())).append('\n');
        }

        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(bytes);
        String fname = "preview-sender-" + id + "-" + java.time.Instant.now().toString().replace(':', '-') + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .contentLength(bytes.length)
                .body(resource);
    }

    private boolean currentUserIsAdmin() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getAuthorities() != null) {
                return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()) || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
            }
        } catch (Exception ignore) {}
        return false;
    }

    private boolean isHistoricalDataType(String dataType) {
        return dataType != null && dataType.trim().equalsIgnoreCase("historical");
    }

    private PreviewFilters normalizePreviewFilters(DiscoveryPreviewRequest request, boolean isAdmin, Integer senderId) {
        java.util.List<String> lotsParam = request.lots();
        java.util.List<String> wafersParam = request.wafers();
        try {
            if (request.pairs() != null && !request.pairs().isEmpty()) {
                java.util.List<String> l = new java.util.ArrayList<>();
                java.util.List<String> w = new java.util.ArrayList<>();
                for (com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewPair p : request.pairs()) {
                    String lotVal = p == null ? null : p.lot();
                    String waferVal = p == null ? null : p.wafer();
                    lotVal = trimToNull(lotVal);
                    waferVal = trimToNull(waferVal);
                    l.add(lotVal);
                    w.add(waferVal);
                }
                lotsParam = l;
                wafersParam = w;
            }
        } catch (Exception ex) {
            try { log.warn("Failed parsing pairs param for preview: {}", ex.getMessage()); } catch (Exception ignore) {}
        }

        String startDate = trimToNull(request.startDate());
        String endDate = trimToNull(request.endDate());
        boolean allowDate = isAdmin && startDate != null && endDate != null;
        if (!allowDate) {
            if ((startDate != null || endDate != null) && log.isInfoEnabled()) {
                log.info("Ignoring start/end date filters for preview (sender={}): admin={} start={} end={}", senderId, isAdmin, startDate, endDate);
            }
            startDate = null;
            endDate = null;
        }

        java.util.List<String> devices = request.devices();
        if (!isAdmin || devices == null || devices.isEmpty()) {
            if (devices != null && !devices.isEmpty() && log.isInfoEnabled()) {
                log.info("Ignoring device filter for preview (sender={}): admin={} deviceCount={}", senderId, isAdmin, devices.size());
            }
            devices = null;
        }

        // New filter fields - admin only
        java.util.List<String> steps = request.steps();
        if (!isAdmin || steps == null || steps.isEmpty()) {
            if (steps != null && !steps.isEmpty() && log.isInfoEnabled()) {
                log.info("Ignoring step filter for preview (sender={}): admin={} stepCount={}", senderId, isAdmin, steps.size());
            }
            steps = null;
        }

        java.util.List<String> recipes = request.recipes();
        if (!isAdmin || recipes == null || recipes.isEmpty()) {
            if (recipes != null && !recipes.isEmpty() && log.isInfoEnabled()) {
                log.info("Ignoring recipe filter for preview (sender={}): admin={} recipeCount={}", senderId, isAdmin, recipes.size());
            }
            recipes = null;
        }

        java.util.List<String> equipmentIds = request.equipmentIds();
        if (!isAdmin || equipmentIds == null || equipmentIds.isEmpty()) {
            if (equipmentIds != null && !equipmentIds.isEmpty() && log.isInfoEnabled()) {
                log.info("Ignoring equipmentId filter for preview (sender={}): admin={} equipmentIdCount={}", senderId, isAdmin, equipmentIds.size());
            }
            equipmentIds = null;
        }

        return new PreviewFilters(lotsParam, wafersParam, startDate, endDate, devices, steps, recipes, equipmentIds);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private DateMode normalizeDateAndMode(String startDateRaw,
                                          String endDateRaw,
                                          boolean requestedHistoricalMode,
                                          boolean isAdmin,
                                          String operation,
                                          Integer senderId) {
        String startDate = trimToNull(startDateRaw);
        String endDate = trimToNull(endDateRaw);

        boolean allowDate = isAdmin && startDate != null && endDate != null;
        if (!allowDate) {
            if ((startDate != null || endDate != null) && log.isInfoEnabled()) {
                log.info("Ignoring start/end date filters for {} (sender={}): admin={} start={} end={}", operation, senderId, isAdmin, startDate, endDate);
            }
            startDate = null;
            endDate = null;
        }

        boolean historicalMode = isAdmin && requestedHistoricalMode;
        if (!isAdmin && requestedHistoricalMode && log.isInfoEnabled()) {
            log.info("Ignoring historicalMode flag for {} (sender={}): admin={}", operation, senderId, isAdmin);
        }

        return new DateMode(startDate, endDate, historicalMode);
    }

    private record DateMode(String startDate, String endDate, boolean historicalMode) {}

    private record PreviewFilters(java.util.List<String> lots, java.util.List<String> wafers, String startDate, String endDate, java.util.List<String> devices,
                                  java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds) { }

    private String csvEscape(String v) {
        if (v == null) return "";
        String s = v.replace("\"", "\"\"");
        boolean needsQuotes = s.contains(",") || s.contains("\n") || s.contains("\r") || s.contains("\"");
        return needsQuotes ? ("\"" + s + "\"") : s;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/{id}/preview/duplicates")
    public ResponseEntity<java.util.List<DuplicatePayloadView>> previewDuplicates(@PathVariable("id") Integer id,
                                                                                  @RequestBody com.onsemi.cim.apps.exensio.exensioreload.dto.PreviewDuplicateRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return ResponseEntity.ok(java.util.List.of());
        }
        Integer resolvedSender = request.senderId() != null ? request.senderId() : id;
        java.util.List<DuplicatePayloadView> out = new java.util.ArrayList<>();
        for (com.onsemi.cim.apps.exensio.exensioreload.dto.PreviewDuplicateRequest.PreviewItem item : request.items()) {
            if (item == null) continue;
            String mid = item.metadataId();
            String did = item.dataId();
            com.onsemi.cim.apps.exensio.exensioreload.stage.DuplicatePayload dup = refDbService.findDuplicatePayload(request.site(), resolvedSender, mid, did);
            if (dup != null) {
                out.add(this.toDuplicateView(dup));
            }
        }
        return ResponseEntity.ok(out);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/{id}/stage")
    public ResponseEntity<StagePayloadResponse> stagePayloads(@PathVariable("id") Integer id,
                                                              @RequestBody StagePayloadRequest request) {
        String site = request.site();
        if (site == null || site.isBlank()) {
            throw new IllegalArgumentException("site is required");
        }
        Integer resolvedSender = request.senderId() != null ? request.senderId() : id;
        if (resolvedSender == null || resolvedSender <= 0) {
            throw new IllegalArgumentException("senderId is required");
        }
        List<StagePayloadRequest.Payload> payloads = request.payloads();
        if (payloads == null || payloads.isEmpty()) {
            return ResponseEntity.ok(new StagePayloadResponse(0, 0, List.<DuplicatePayloadView>of(), 0, false, 0, false, 0));
        }
        List<PayloadCandidate> candidates = payloads.stream()
                .map(p -> new PayloadCandidate(p.metadataId(), p.dataId(), p.lot(), p.wafer(), p.filename(), parseIsoInstant(p.endTime()), request.dataType(), request.testPhase(), p.device()))
                .collect(Collectors.toList());
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String requestedBy = authentication != null && authentication.getName() != null ? authentication.getName().trim() : "ui";
        if (requestedBy.isEmpty()) {
            requestedBy = "ui";
        }

        // Capture user email for completion notifications (stored for future session creation)
        String userEmail = request.userEmail();
        if (userEmail == null || userEmail.isBlank()) {
            // Try to get email from authenticated user
            try {
                if (authentication != null && authentication.getName() != null) {
                    var userOpt = userRepository.findByUsername(authentication.getName());
                    if (userOpt.isPresent() && userOpt.get().getEmail() != null && !userOpt.get().getEmail().isBlank()) {
                        userEmail = userOpt.get().getEmail();
                        log.debug("Captured user email for notifications: {}", userEmail);
                    }
                }
            } catch (Exception ex) {
                log.debug("Could not fetch user email for notifications: {}", ex.getMessage());
            }
        }

        StageResult result = refDbService.stagePayloads(site, resolvedSender, request.senderName(), requestedBy, candidates, request.forceDuplicates(), request.requestId());
        boolean requiresConfirmation = result.duplicates().stream().anyMatch(DuplicatePayload::requiresConfirmation);
        int dispatched = 0;
        if (request.triggerDispatch() && !requiresConfirmation) {
            dispatched = senderDispatchService.dispatchSender(site, resolvedSender);
        } else if (request.triggerDispatch() && requiresConfirmation) {
            log.info("Dispatch deferred for site {} sender {} pending duplicate confirmation", site, resolvedSender);
        }
        // When records were re-queued (already existed), refresh the session counters so
        // total_files reflects the re-queued records immediately — monitoring page shows them right away.
        if (result.requeuedCount() > 0 && request.requestId() != null) {
            try {
                stageSessionService.refreshCounters(request.requestId());
            } catch (Exception ex) {
                log.warn("Failed refreshing session counters after re-queue for session {}: {}", request.requestId(), ex.getMessage());
            }
        }
        Authentication auth2 = SecurityContextHolder.getContext().getAuthentication();
        List<DuplicatePayloadView> duplicateViewsAll = result.duplicates().stream().map(this::toDuplicateView).toList();
        List<DuplicatePayloadView> duplicateViews;
        if (auth2 == null) {
            duplicateViews = duplicateViewsAll;
        } else {
            boolean isAdmin = auth2.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN") || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
            String username = auth2.getName() == null ? "" : auth2.getName().trim();
            if (isAdmin || username.isEmpty()) {
                duplicateViews = duplicateViewsAll;
            } else {
                String me = username.toLowerCase();
                // Only include duplicates for non-admins where the existing staged payload is owned by the user
                // OR where the user already has a staged record for the same metadata/data (so they have visibility).
                duplicateViews = duplicateViewsAll.stream().filter(dv -> {
                    String stagedBy = dv.stagedBy() == null ? "" : dv.stagedBy().toLowerCase();
                    String lastBy = dv.lastRequestedBy() == null ? "" : dv.lastRequestedBy().toLowerCase();
                    if (!stagedBy.isBlank() && stagedBy.equals(me)) return true;
                    if (!lastBy.isBlank() && lastBy.equals(me)) return true;
                    // check whether a staged record exists owned by the user for the same metadata/data
                    boolean existsForUser = refDbService.recordExistsForUser(site, resolvedSender, dv.metadataId(), dv.dataId(), me);
                    return existsForUser;
                }).toList();
            }
        }
        StagePayloadResponse response = new StagePayloadResponse(result.stagedCount(), duplicateViews.size(), duplicateViews, dispatched, requiresConfirmation, candidates.size(), false, result.requeuedCount());
        return ResponseEntity.ok(response);
    }

    /**
     * Verify lot existence in Exensio before running discovery.
     * Returns a map indicating which lots exist and which don't.
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/{id}/verify-lots")
    public ResponseEntity<LotVerificationResponse> verifyLots(
            @PathVariable("id") Integer id,
            @RequestBody LotVerificationRequest request) {

        // Validate request
        if (request == null || request.lots() == null || request.lots().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            // Step 1: Validate request
            if (request == null || request.lots() == null || request.lots().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            List<String> lots = request.lots();
            if (lots.size() > 1000) {
                return ResponseEntity.badRequest()
                    .body(new LotVerificationResponse(
                        Map.of(),
                        "Too many lots. Maximum 1000 lots per request."
                    ));
            }

            // Step 2: Extract and validate dataType
            String dataType = request.dataType();
            if (dataType == null || dataType.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(new LotVerificationResponse(
                        Map.of(),
                        "dataType is required for lot verification."
                    ));
            }

            // Step 3: Determine if wafer-level class and discover wafers if needed
            int pgcKey = ExensioPreCheckService.resolvePgcKey(dataType);
            boolean isWaferLevel = ExensioSqlUtilService.isWaferLevelClass(pgcKey);
            boolean hasWaferFilter = request.wafers() != null && !request.wafers().isEmpty();

            List<String> waferIds = request.wafers();
            
            if (isWaferLevel && !hasWaferFilter) {
                log.info("Lot verification: Wafer-level class detected (pgcKey={}). Discovering wafers for lots...", pgcKey);
                waferIds = waferDiscoveryService.discoverWafersForLots(lots, pgcKey);
                
                if (waferIds.isEmpty()) {
                    log.warn("Lot verification: No wafers discovered for {} lots", lots.size());
                    // Continue anyway - maybe wafers exist in Exensio but not in local DB
                }
                
                log.debug("Lot verification: Discovered {} wafers for {} lots", waferIds.size(), lots.size());
            }

            // Step 4: Build preflight check request
            ExensioPreCheckRequest preCheckRequest = new ExensioPreCheckRequest(
                request.environment(),
                lots,
                waferIds,  // Discovered wafers (or user-provided, or null for lot-level)
                null,      // blocks - not needed for simple lot check
                dataType,
                request.enableSnowflakeFallback(), // enableSnowflakeFallback from caller request
                request.filenames()  // optional filenames for raw-SQL filename prefix matching
            );

            // Step 5: Execute preflight check
            ExensioPreCheckResponse preCheckResponse;
            
            if (isWaferLevel && waferIds != null && !waferIds.isEmpty()) {
                // For wafer-level: check in parallel across PRODUCTION and SANDBOX schemas
                log.debug("Lot verification: Executing parallel schema check for wafer-level class");
                preCheckResponse = parallelSchemaCheckService.checkLotsParallel(lots, waferIds, preCheckRequest);
            } else {
                // For lot-level: use standard check
                preCheckResponse = exensioPreCheckService.check(preCheckRequest);
            }

            // Step 6: Transform ExensioPreCheckResponse to LotVerificationResponse
            Map<String, LotVerificationResult> lotResults = new HashMap<>();
            
            // Create lookup maps from rows
            Map<String, String> lotToSchema = new HashMap<>();
            Map<String, List<String>> lotToWafers = new HashMap<>();
            
            for (ExensioPreCheckRow row : preCheckResponse.rows()) {
                String lot = row.lotId();
                String schema = row.schemaName();
                String wafer = row.waferId();
                
                // Store schema (first occurrence wins)
                lotToSchema.putIfAbsent(lot, schema);
                
                // Collect wafers, avoiding duplicates
                if (wafer != null && !wafer.isBlank()) {
                    List<String> wafersForLot = lotToWafers.computeIfAbsent(lot, k -> new ArrayList<>());
                    if (!wafersForLot.contains(wafer)) {
                        wafersForLot.add(wafer);
                    }
                }
            }
            
            // Map each lot to its result
            for (String lot : lots) {
                boolean found = preCheckResponse.lotsFound().contains(lot);
                String schema = found ? lotToSchema.get(lot) : null;
                List<String> resultWafers = found ? lotToWafers.getOrDefault(lot, Collections.emptyList()) : Collections.emptyList();
                lotResults.put(lot, new LotVerificationResult(found, schema, resultWafers));
            }

            // Step 7: Include error field from ExensioPreCheckResponse if present
            String errorMessage = preCheckResponse.error();
            String error = preCheckResponse.error();
            
            log.info("Lot verification completed for sender {}: {} lots checked, {} found, {} not found",
                    id, lots.size(), preCheckResponse.lotsFound().size(), preCheckResponse.lotsNotFound().size());

            return ResponseEntity.ok(new LotVerificationResponse(lotResults, errorMessage));

        } catch (Exception e) {
            log.error("Lot verification failed for sender {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500)
                .body(new LotVerificationResponse(
                    Map.of(),
                    "Verification failed: " + e.getMessage()
                ));
        }
    }

    private java.time.Instant parseIsoInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.Instant.parse(value.trim());
        } catch (Exception ignored) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(value.trim());
                return ldt.atZone(ZoneOffset.UTC).toInstant();
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    private DuplicatePayloadView toDuplicateView(DuplicatePayload payload) {
        return new DuplicatePayloadView(
                payload.metadataId(),
                payload.dataId(),
                payload.lot(),
                payload.wafer(),
                payload.filename(),
                payload.previousStatus(),
                toIso(payload.previousProcessedAt()),
                displayUser(payload.stagedBy()),
                toIso(payload.stagedAt()),
                displayUser(payload.lastRequestedBy()),
                toIso(payload.lastRequestedAt()),
                payload.requiresConfirmation()
        );
    }

    private String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private String toIso(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneOffset.UTC).toInstant().toString();
    }

    private String displayUser(String value) {
        if (value == null) {
            return "unknown";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "unknown" : trimmed;
    }

    // Lookup senders in selected external DB based on user-provided filters. Returns list of {idSender,name}
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping("/lookup")
    public ResponseEntity<java.util.List<java.util.Map<String,Object>>> lookupSenders(@RequestParam(defaultValue = "default") String site,
                                                                                      @RequestParam(defaultValue = "qa") String environment,
                                                                                      @RequestParam(required = false) String metadataLocation,
                                                                                      @RequestParam(required = false) String location,
                                                                                      @RequestParam(required = false) String dataType,
                                                                                      @RequestParam(required = false) String testerType,
                                                                                      @RequestParam(required = false) String dataTypeExt,
                                                                                      @RequestParam(required = false) String testPhase,
                                                                                      @RequestParam(required = false, name = "senderId") Integer senderId,
                                                                                      @RequestParam(required = false, name = "senderName") String senderName,
                                                                                      // saved ExternalLocation id used to select which DB connection to use
                                                                                      @RequestParam(required = false, name = "locationId") Long locationId,
                                                                                      // alternatively, allow callers to provide the connection key directly
                                                                                      @RequestParam(required = false, name = "connectionKey") String connectionKey,
                                                                                      // per-request toggle: when true and allowed, return paramized SQL in response
                                                                                      @RequestParam(required = false, defaultValue = "false") boolean debug) {
        try {
            if (log.isInfoEnabled()) {
                log.info("Sender lookup called with metadataLocation='{}' location='{}' dataType='{}' testerType='{}' dataTypeExt='{}' testPhase='{}' locationId='{}' connectionKey='{}'",
                        metadataLocation, location, dataType, testerType, dataTypeExt, testPhase, locationId, connectionKey);
            }
            com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = null;
            java.sql.Connection conn = null;
            if (locationId != null) {
                loc = metadataImporterService.findLocationById(locationId);
                if (loc == null) throw new IllegalArgumentException("locationId not found");
                conn = metadataImporterService.resolveConnectionForLocation(loc, environment);
            } else if (connectionKey != null && !connectionKey.isBlank()) {
                if (metadataLocation == null || metadataLocation.isBlank()) {
                    throw new IllegalArgumentException("metadataLocation is required when using a connection key");
                }
                conn = metadataImporterService.resolveConnectionForKey(connectionKey, environment);
            } else {
                throw new IllegalArgumentException("locationId or connectionKey is required");
            }

            // Allow legacy clients to send the metadata/location filter as either
            // `metadataLocation` (preferred) or `location` (legacy). Prefer the
            // explicit `metadataLocation` when present.
            if ((metadataLocation == null || metadataLocation.isBlank()) && location != null && !location.isBlank()) {
                metadataLocation = location;
            }

            try (java.sql.Connection c = conn) {
                // metric: record lookup by saved connection key or locationId
                String key = loc != null ? ("locationId=" + loc.getId()) : connectionKey;
                try { metricsService.increment("external.lookup", key); } catch (Exception ignore) {}
                java.util.List<com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate> res = metadataImporterService.findSendersWithConnection(c, metadataLocation, dataType, testerType, dataTypeExt, testPhase);
                // Determine whether per-request debug is allowed. Allow when:
                //  - request asked for debug=true AND
                //  - (running dev profile OR app.discovery.debug-sql=true OR caller is admin)
                boolean isAdmin = false;
                try {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN") || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
                } catch (Exception ignore) {}
                boolean devProfile = java.util.Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p != null && p.equalsIgnoreCase("dev"));
                boolean debugProperty = Boolean.parseBoolean(env.getProperty("app.discovery.debug-sql", "false"));
                // Allow showing the param-expanded SQL automatically in dev, when the
                // app.discovery.debug-sql property is true, or when caller is admin.
                // Also respect the per-request 'debug' flag so operators can opt-in.
                boolean allowDebug = devProfile || debugProperty || isAdmin || debug;

                String sqlDesc = null;
                try { sqlDesc = metadataImporterService.describeSenderLookupQueryWithParamsWithConnection(c, metadataLocation, dataType, testerType, dataTypeExt, testPhase, allowDebug); } catch (Exception ignore) {}

                // Always log the param-expanded SQL for every lookup call to aid diagnostics.
                try {
                    String forcedSql = metadataImporterService.describeSenderLookupQueryWithParamsWithConnection(c, metadataLocation, dataType, testerType, dataTypeExt, testPhase, true);
                    if (forcedSql != null) log.info("Sender lookup SQL (params): {}", forcedSql);
                } catch (Exception ignore) {}

                // Count how many unique resolved candidate ids are non-null
                java.util.Set<Integer> uniqueResolvedIds = new java.util.HashSet<>();
                for (com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate s : res) {
                    if (s.getIdSender() != null) uniqueResolvedIds.add(s.getIdSender());
                }
                int resolvedCount = uniqueResolvedIds.size();

                java.util.List<java.util.Map<String,Object>> out = new java.util.ArrayList<>();

                if (resolvedCount == 1) {
                    // Exactly one id resolved: return the filtered result (respecting optional senderId/senderName filters)
                    // Log which sender id was resolved so the backend console shows the decision
                    try {
                        // pick the unique resolved id (there will be exactly one at this point)
                        Integer foundId = uniqueResolvedIds.iterator().hasNext() ? uniqueResolvedIds.iterator().next() : null;
                        log.info("Sender lookup resolved to id={} for connectionKey={} metadataLocation={} dataType={} testerType={} dataTypeExt={} testPhase={}",
                                foundId, key, metadataLocation, dataType, testerType, dataTypeExt, testPhase);
                    } catch (Exception ignore) {}
                    for (com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate s : res) {
                        if (senderId != null && s.getIdSender() != null && !java.util.Objects.equals(senderId, s.getIdSender())) {
                            continue;
                        }
                        if (senderName != null && !senderName.isBlank()) {
                            String candidateName = s.getName() == null ? "" : s.getName();
                            if (!candidateName.equalsIgnoreCase(senderName.trim())) {
                                continue;
                            }
                        }
                        java.util.Map<String,Object> m = new java.util.HashMap<>();
                        m.put("idSender", s.getIdSender());
                        m.put("name", s.getName());
                        if (sqlDesc != null) m.put("query", sqlDesc);
                        out.add(m);
                    }
                    return ResponseEntity.ok(out);
                } else {
                    // Did not resolve to a single numeric id. Log useful diagnostic info so operator can see what happened.
                    try {
                        log.info("Sender lookup did not uniquely resolve (resolvedCount={}) for connectionKey={} metadataLocation={} dataType={} testerType={} dataTypeExt={} testPhase={}",
                                resolvedCount, key, metadataLocation, dataType, testerType, dataTypeExt, testPhase);
                    } catch (Exception ignore) {}

                    // Attempt to fetch and log the param-expanded SQL unconditionally for diagnostics.
                    // Use the force-enabled describe helper so we get the exact bound parameter values
                    // even when the per-request debug flag isn't set.
                    try {
                        // Ensure we have a param-expanded SQL string available for diagnostics.
                        // If allowDebug was false above, request the forced formatted SQL for logging.
                        String forcedSqlDesc = allowDebug ? sqlDesc : metadataImporterService.describeSenderLookupQueryWithParamsWithConnection(c, metadataLocation, dataType, testerType, dataTypeExt, testPhase, true);
                        if (forcedSqlDesc != null) {
                            log.info("Sender lookup SQL (params) produced no rows: {}", forcedSqlDesc);
                            // When running in dev/debug modes, include the forced SQL in the response
                            // so the frontend can show diagnostic info. sqlDesc already contains it
                            // when allowDebug is true; otherwise set it for inclusion below.
                            if (sqlDesc == null && (devProfile || debugProperty || isAdmin)) sqlDesc = forcedSqlDesc;
                        }
                    } catch (Exception ex) {
                        try { log.info("Failed generating forced param-formatted SQL for diagnostics: {}", ex.getMessage()); } catch (Exception ignore) {}
                    }

                    // No unique resolution: prefer returning the filtered candidates that the resolver produced.
                    // If multiple candidates exist, try to narrow by the provided `testPhase` and `senderName`:
                    // 1) If both senderName and testPhase are provided, prefer the candidate whose name matches senderName
                    //    (case-insensitive) and whose where_condition contains testPhase (case-insensitive).
                    // 2) Otherwise, if testPhase provided, prefer any candidate whose where_condition contains testPhase.
                    // 3) Otherwise, if senderName provided, prefer candidate whose name matches senderName.
                    // If no narrowing match is found, return the full filtered set. Only fall back to the
                    // complete sender list when the filtered set is empty.
                    java.util.List<com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate> sendersToReturn = null;
                    if (res != null && !res.isEmpty()) {
                        String tp = testPhase == null ? null : testPhase.trim();
                        String sn = senderName == null ? null : senderName.trim();
                        com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate preferred = null;
                        // 1) senderName + testPhase
                        if (sn != null && !sn.isBlank() && tp != null && !tp.isBlank()) {
                            for (com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate sc : res) {
                                String wc = sc.getWhereCondition();
                                String nm = sc.getName() == null ? "" : sc.getName();
                                if (nm.equalsIgnoreCase(sn) && wc != null && wc.toLowerCase(Locale.ROOT).contains(tp.toLowerCase(Locale.ROOT))) {
                                    preferred = sc; break;
                                }
                            }
                        }
                        // 2) testPhase only
                        if (preferred == null && tp != null && !tp.isBlank()) {
                            for (com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate sc : res) {
                                String wc = sc.getWhereCondition();
                                if (wc != null && wc.toLowerCase(Locale.ROOT).contains(tp.toLowerCase(Locale.ROOT))) {
                                    preferred = sc; break;
                                }
                            }
                        }
                        // 3) senderName only
                        if (preferred == null && sn != null && !sn.isBlank()) {
                            for (com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate sc : res) {
                                String nm = sc.getName() == null ? "" : sc.getName();
                                if (nm.equalsIgnoreCase(sn)) { preferred = sc; break; }
                            }
                        }

                        if (preferred != null) {
                            sendersToReturn = java.util.List.of(preferred);
                            try { log.info("Sender lookup narrowed to preferred candidate id={} name={}", preferred.getIdSender(), preferred.getName()); } catch (Exception ignore) {}
                        } else {
                            sendersToReturn = res;
                            try { log.info("Sender lookup produced multiple filtered candidates (count={}) — returning those for dropdown.", res.size()); } catch (Exception ignore) {}
                        }
                    } else {
                        sendersToReturn = metadataImporterService.findAllSendersWithConnection(c);
                    }
                    if (sendersToReturn == null || sendersToReturn.isEmpty()) {
                        try { log.warn("Sender lookup fallback returned zero senders for connectionKey={}.", key); } catch (Exception ignore) {}
                    } else {
                        if (res != null && !res.isEmpty()) {
                            try { log.info("Sender lookup produced multiple filtered candidates (count={}) — returning those for dropdown.", res.size()); } catch (Exception ignore) {}
                        }
                    }
                    for (com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate s : sendersToReturn) {
                        java.util.Map<String,Object> m = new java.util.HashMap<>();
                        m.put("idSender", s.getIdSender());
                        m.put("name", s.getName());
                        m.put("id", s.getIdSender());
                        if (sqlDesc != null) m.put("query", sqlDesc);
                        out.add(m);
                    }
                    return ResponseEntity.ok(out);
                }
            }
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(java.util.Collections.singletonList(java.util.Map.of("error", ex.getMessage())));
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping("/historical/senders")
    public ResponseEntity<java.util.List<java.util.Map<String,Object>>> historicalSenders(@RequestParam(required = false, name = "locationId") Long locationId,
                                                                                          @RequestParam(required = false, name = "connectionKey") String connectionKey,
                                                                                          @RequestParam(required = false) String site,
                                                                                          @RequestParam(required = false) String dataType,
                                                                                          @RequestParam(defaultValue = "qa") String environment) {
        try {
            if (log.isInfoEnabled()) {
                log.info("Historical sender lookup request: locationId={} connectionKey={} site={} dataType={} environment={}",
                        locationId, connectionKey, site, dataType, environment);
            }

            // Backwards compatibility: allow callers to pass site instead of connectionKey.
            if ((connectionKey == null || connectionKey.isBlank()) && site != null && !site.isBlank()) {
                connectionKey = site;
            }

            if (log.isInfoEnabled()) {
                log.info("Historical sender lookup resolved selector: locationId={} resolvedConnectionKey={} (from site alias={})",
                        locationId, connectionKey, site != null && !site.isBlank());
            }

            java.sql.Connection conn = null;
            if (locationId != null) {
                com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = metadataImporterService.findLocationById(locationId);
                if (loc == null) throw new IllegalArgumentException("locationId not found");
                conn = metadataImporterService.resolveConnectionForLocation(loc, environment);
            } else if (connectionKey != null && !connectionKey.isBlank()) {
                conn = metadataImporterService.resolveConnectionForKey(connectionKey, environment);
            } else {
                throw new IllegalArgumentException("locationId or connectionKey is required");
            }

            try (java.sql.Connection c = conn) {
                try { metricsService.increment("external.historicalSenders", locationId != null ? "locationId=" + locationId : connectionKey); } catch (Exception ignore) {}
                java.util.List<com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate> res = metadataImporterService.findHistoricalSendersWithConnection(c, dataType);
                java.util.List<java.util.Map<String,Object>> out = new java.util.ArrayList<>();
                for (com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate s : res) {
                    java.util.Map<String,Object> m = new java.util.HashMap<>();
                    m.put("idSender", s.getIdSender());
                    m.put("name", s.getName());
                    m.put("id", s.getIdSender());
                    out.add(m);
                }
                return ResponseEntity.ok(out);
            }
        } catch (Exception ex) {
            log.error("Historical sender lookup failed for connectionKey={} locationId={} dataType={}: {}", connectionKey, locationId, dataType, ex.getMessage(), ex);
            return ResponseEntity.status(500).body(java.util.List.of(java.util.Map.of("error", ex.getMessage())));
        }
    }

    // Fetch distinct location values from the selected external DB (for location dropdown)
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping("/external/locations")
    public ResponseEntity<java.util.List<String>> externalDistinctLocations(@RequestParam(required = false, name = "locationId") Long locationId,
                                                                            @RequestParam(required = false, name = "connectionKey") String connectionKey,
                                                                            @RequestParam(required = false) String site,
                                                                            @RequestParam(required = false) String dataType,
                                                                            @RequestParam(required = false) String testerType,
                                                                            @RequestParam(required = false) String testPhase,
                                                                            @RequestParam(defaultValue = "qa") String environment) {
        // Allow callers to pass 'site' as an alias for connectionKey for backwards compatibility
        if ((connectionKey == null || connectionKey.isBlank()) && site != null && !site.isBlank()) {
            connectionKey = site;
        }

        try {
            java.sql.Connection conn = null;
            String metricKey = null;
            if (locationId != null) {
                com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = metadataImporterService.findLocationById(locationId);
                if (loc == null) throw new IllegalArgumentException("locationId not found");
                conn = metadataImporterService.resolveConnectionForLocation(loc, environment);
                metricKey = "locationId=" + locationId;
            } else if (connectionKey != null && !connectionKey.isBlank()) {
                conn = metadataImporterService.resolveConnectionForKey(connectionKey, environment);
                metricKey = connectionKey;
            } else {
                return ResponseEntity.badRequest().body(java.util.List.of());
            }

            try (java.sql.Connection c = conn) {
                try { metricsService.increment("external.locations", metricKey); } catch (Exception ignore) {}
                java.util.List<String> out = metadataImporterService.findDistinctLocationsWithConnection(c, dataType, testerType, testPhase);
                return ResponseEntity.ok(out == null ? java.util.List.of() : out);
            }
        } catch (IllegalArgumentException iae) {
            log.warn("Invalid request for externalDistinctLocations: {}", iae.getMessage());
            return ResponseEntity.badRequest().body(java.util.List.of());
        } catch (Exception ex) {
            log.error("Failed fetching distinct locations for connectionKey/site {} env {}: {}", connectionKey != null ? connectionKey : site, environment, ex.getMessage(), ex);
            return ResponseEntity.status(500).body(java.util.List.of());
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping("/external/dataTypes")
    public ResponseEntity<java.util.List<String>> externalDistinctDataTypes(@RequestParam(required = false, name = "locationId") Long locationId,
                                                                            @RequestParam(required = false, name = "connectionKey") String connectionKey,
                                                                            @RequestParam(required = false) String location,
                                                                            @RequestParam(required = false) String testerType,
                                                                            @RequestParam(required = false) String testPhase,
                                                                            @RequestParam(defaultValue = "qa") String environment) {
        try {
            java.sql.Connection conn = null;
            if (locationId != null) {
                com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = metadataImporterService.findLocationById(locationId);
                if (loc == null) throw new IllegalArgumentException("locationId not found");
                conn = metadataImporterService.resolveConnectionForLocation(loc, environment);
            } else if (connectionKey != null && !connectionKey.isBlank()) {
                conn = metadataImporterService.resolveConnectionForKey(connectionKey, environment);
            } else {
                throw new IllegalArgumentException("locationId or connectionKey is required");
            }
            try (java.sql.Connection c = conn) {
                try { metricsService.increment("external.dataTypes", locationId != null ? "locationId=" + locationId : connectionKey); } catch (Exception ignore) {}
                java.util.List<String> out = metadataImporterService.findDistinctDataTypesWithConnection(c, location, testerType, testPhase);
                return ResponseEntity.ok(out);
            }
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(java.util.List.of());
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping("/external/testerTypes")
    public ResponseEntity<java.util.List<String>> externalDistinctTesterTypes(@RequestParam(required = false, name = "locationId") Long locationId,
                                                                              @RequestParam(required = false, name = "connectionKey") String connectionKey,
                                                                              @RequestParam(required = false) String location,
                                                                              @RequestParam(required = false) String dataType,
                                                                              @RequestParam(required = false) String testPhase,
                                                                              @RequestParam(defaultValue = "qa") String environment) {
        try {
            java.sql.Connection conn = null;
            if (locationId != null) {
                com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = metadataImporterService.findLocationById(locationId);
                if (loc == null) throw new IllegalArgumentException("locationId not found");
                conn = metadataImporterService.resolveConnectionForLocation(loc, environment);
            } else if (connectionKey != null && !connectionKey.isBlank()) {
                conn = metadataImporterService.resolveConnectionForKey(connectionKey, environment);
            } else {
                throw new IllegalArgumentException("locationId or connectionKey is required");
            }
            try (java.sql.Connection c = conn) {
                try { metricsService.increment("external.testerTypes", locationId != null ? "locationId=" + locationId : connectionKey); } catch (Exception ignore) {}
                java.util.List<String> out = metadataImporterService.findDistinctTesterTypesWithConnection(c, location, dataType, testPhase);
                return ResponseEntity.ok(out);
            }
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(java.util.List.of());
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping("/external/dataTypeExts")
    public ResponseEntity<java.util.List<String>> externalDistinctDataTypeExts(@RequestParam(required = false, name = "locationId") Long locationId,
                                                                               @RequestParam(required = false, name = "connectionKey") String connectionKey,
                                                                               @RequestParam(required = false) String location,
                                                                               @RequestParam(required = false) String dataType,
                                                                               @RequestParam(required = false) String testerType,
                                                                               @RequestParam(defaultValue = "qa") String environment) {
        try {
            java.sql.Connection conn = null;
            if (locationId != null) {
                com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = metadataImporterService.findLocationById(locationId);
                if (loc == null) throw new IllegalArgumentException("locationId not found");
                conn = metadataImporterService.resolveConnectionForLocation(loc, environment);
            } else if (connectionKey != null && !connectionKey.isBlank()) {
                conn = metadataImporterService.resolveConnectionForKey(connectionKey, environment);
            } else {
                throw new IllegalArgumentException("locationId or connectionKey is required");
            }
            try (java.sql.Connection c = conn) {
                try { metricsService.increment("external.dataTypeExts", locationId != null ? "locationId=" + locationId : connectionKey); } catch (Exception ignore) {}
                // If location or dataType not provided, return empty list (UI will not request until location+dataType available)
                if (location == null || location.isBlank() || dataType == null || dataType.isBlank()) {
                    return ResponseEntity.ok(java.util.List.of());
                }
                java.util.List<String> out = metadataImporterService.findDistinctDataTypeExtsWithConnection(c, location, dataType, testerType);
                return ResponseEntity.ok(out == null ? java.util.List.of() : out);
            }
        } catch (IllegalArgumentException iae) {
            log.warn("Invalid request for externalDistinctDataTypeExts: {}", iae.getMessage());
            return ResponseEntity.badRequest().body(java.util.List.of());
        } catch (Exception ex) {
            log.error("Failed fetching distinct data_type_ext for connection {} env {}: {}", connectionKey != null ? connectionKey : location, environment, ex.getMessage(), ex);
            return ResponseEntity.status(500).body(java.util.List.of());
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping("/external/testPhases")
    public ResponseEntity<java.util.List<String>> externalDistinctTestPhases(@RequestParam(required = false, name = "locationId") Long locationId,
                                                                             @RequestParam(required = false, name = "connectionKey") String connectionKey,
                                                                             @RequestParam(required = false) String location,
                                                                             @RequestParam(required = false) String dataType,
                                                                             @RequestParam(required = false) String dataTypeExt,
                                                                             @RequestParam(required = false) String testerType,
                                                                             @RequestParam(required = false, name = "senderId") Integer senderId,
                                                                             @RequestParam(required = false, name = "senderName") String senderName,
                                                                             @RequestParam(required = false, defaultValue = "false") boolean exactTesterType,
                                                                             @RequestParam(defaultValue = "qa") String environment) {
        try {
            java.sql.Connection conn = null;
            if (locationId != null) {
                com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = metadataImporterService.findLocationById(locationId);
                if (loc == null) throw new IllegalArgumentException("locationId not found");
                conn = metadataImporterService.resolveConnectionForLocation(loc, environment);
            } else if (connectionKey != null && !connectionKey.isBlank()) {
                conn = metadataImporterService.resolveConnectionForKey(connectionKey, environment);
            } else {
                throw new IllegalArgumentException("locationId or connectionKey is required");
            }
            // Only require location and dataType; testerType is optional and the repository will
            // apply OR-NULL semantics so callers can request phases even when testerType is not provided.
            if (location == null || location.isBlank() || dataType == null || dataType.isBlank()) {
                return ResponseEntity.ok(java.util.List.of());
            }
            try (java.sql.Connection c = conn) {
                try { metricsService.increment("external.testPhases", locationId != null ? "locationId=" + locationId : connectionKey); } catch (Exception ignore) {}
                java.util.List<String> out = metadataImporterService.findDistinctTestPhasesWithConnection(
                        c,
                        location,
                        dataType,
                        dataTypeExt,
                        testerType,
                        senderId,
                        senderName,
                        exactTesterType
                );
                return ResponseEntity.ok(out);
            }
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(java.util.List.of());
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping("/external/devices")
    public ResponseEntity<java.util.List<String>> externalDistinctDevices(@RequestParam(required = false, name = "connectionKey") String connectionKey,
                                                                           @RequestParam(required = false) String dataType,
                                                                           @RequestParam(required = false) String testerType,
                                                                           @RequestParam(defaultValue = "qa") String environment) {
        try {
            java.sql.Connection conn = null;
            if (connectionKey != null && !connectionKey.isBlank()) {
                conn = metadataImporterService.resolveConnectionForKey(connectionKey, environment);
            } else {
                throw new IllegalArgumentException("connectionKey is required");
            }
            try (java.sql.Connection c = conn) {
                java.util.List<String> out = metadataImporterService.findDistinctDevicesWithConnection(c, dataType, testerType);
                return ResponseEntity.ok(out == null ? java.util.List.of() : out);
            }
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(java.util.List.of());
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
    @GetMapping("/external/senders")
    public ResponseEntity<java.util.List<java.util.Map<String,Object>>> externalSenders(@RequestParam(required = false, name = "locationId") Long locationId,
                                                                                        @RequestParam(required = false, name = "connectionKey") String connectionKey,
                                                                                        @RequestParam(required = false) String metadataLocation,
                                                                                        @RequestParam(required = false) String location,
                                                                                        @RequestParam(required = false) String dataType,
                                                                                        @RequestParam(required = false) String testerType,
                                                                                        @RequestParam(required = false) String dataTypeExt,
                                                                                        @RequestParam(required = false) String testPhase,
                                                                                        @RequestParam(defaultValue = "qa") String environment,
                                                                                        @RequestParam(required = false, defaultValue = "false") boolean debug) {
        try {
            java.sql.Connection conn = null;
            if (locationId != null) {
                com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation loc = metadataImporterService.findLocationById(locationId);
                if (loc == null) throw new IllegalArgumentException("locationId not found");
                conn = metadataImporterService.resolveConnectionForLocation(loc, environment);
            } else if (connectionKey != null && !connectionKey.isBlank()) {
                conn = metadataImporterService.resolveConnectionForKey(connectionKey, environment);
            } else {
                throw new IllegalArgumentException("locationId or connectionKey is required");
            }
            // Backwards compatibility: accept `location` as an alias for `metadataLocation`.
            if ((metadataLocation == null || metadataLocation.isBlank()) && location != null && !location.isBlank()) {
                metadataLocation = location;
            }

            try (java.sql.Connection c = conn) {
                try { metricsService.increment("external.senders", locationId != null ? "locationId=" + locationId : connectionKey); } catch (Exception ignore) {}
                // Fetch all senders (fallback) and also provide the descriptive SQL that would be used for a filtered lookup
                java.util.List<com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate> senders = metadataImporterService.findAllSendersWithConnection(c);
                // per-request debug toggle for external senders listing as well
                boolean isAdmin = false;
                try {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN") || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
                } catch (Exception ignore) {}
                boolean devProfile = java.util.Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p != null && p.equalsIgnoreCase("dev"));
                boolean debugProperty = Boolean.parseBoolean(env.getProperty("app.discovery.debug-sql", "false"));
                boolean allowDebug = devProfile || debugProperty || isAdmin || debug;
                String sqlDesc = null;
                try { sqlDesc = metadataImporterService.describeSenderLookupQueryWithParamsWithConnection(c, metadataLocation, dataType, testerType, dataTypeExt, testPhase, allowDebug); } catch (Exception ignore) {}
                try {
                    String forcedSql = metadataImporterService.describeSenderLookupQueryWithParamsWithConnection(c, metadataLocation, dataType, testerType, dataTypeExt, testPhase, true);
                    if (forcedSql != null) log.info("Sender lookup SQL (params): {}", forcedSql);
                } catch (Exception ignore) {}
                java.util.List<java.util.Map<String,Object>> out = new java.util.ArrayList<>();
                for (com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate s : senders) {
                    java.util.Map<String,Object> m = new java.util.HashMap<>();
                    m.put("idSender", s.getIdSender());
                    m.put("name", s.getName());
                    m.put("id", s.getIdSender());
                    if (sqlDesc != null) m.put("query", sqlDesc);
                    out.add(m);
                }
                return ResponseEntity.ok(out);
            }
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(java.util.List.of());
        }
    }

    private String readError(HttpURLConnection conn) {
        try (InputStream err = conn.getErrorStream()) {
            if (err == null) return null;
            return new String(err.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }
}
