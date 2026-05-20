package com.onsemi.cim.apps.exensio.resender.controller;

import com.onsemi.cim.apps.exensio.resender.service.RefDbService;
import com.onsemi.cim.apps.exensio.resender.service.StageSessionService;
import com.onsemi.cim.apps.exensio.resender.stage.StageRecord;
import com.onsemi.cim.apps.exensio.resender.dto.CreateSessionRequest;
import com.onsemi.cim.apps.exensio.resender.dto.CreateSessionResponse;
import com.onsemi.cim.apps.exensio.resender.dto.LotWaferProgress;
import com.onsemi.cim.apps.exensio.resender.dto.SessionAnalyticsResponse;
import com.onsemi.cim.apps.exensio.resender.dto.StageRecordPage;
import com.onsemi.cim.apps.exensio.resender.dto.StageRecordView;
import com.onsemi.cim.apps.exensio.resender.dto.StagingSessionDetail;
import com.onsemi.cim.apps.exensio.resender.dto.StagingSessionPage;
import com.onsemi.cim.apps.exensio.resender.dto.StagingSessionSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

@RestController
@RequestMapping("/api/stage")
public class StageController {
    private final RefDbService refDbService;
    private final StageRecordMapper mapper;
    private final com.onsemi.cim.apps.exensio.resender.service.MetadataImporterService metadataImporterService;
    private final com.onsemi.cim.apps.exensio.resender.stage.StageMonitorService monitorService;
    private final StageSessionService stageSessionService;
    private static final Logger log = LoggerFactory.getLogger(StageController.class);

    public StageController(RefDbService refDbService,
                           StageRecordMapper mapper,
                           com.onsemi.cim.apps.exensio.resender.service.MetadataImporterService metadataImporterService,
                           com.onsemi.cim.apps.exensio.resender.stage.StageMonitorService monitorService,
                           StageSessionService stageSessionService) {
        this.refDbService = refDbService;
        this.mapper = mapper;
        this.metadataImporterService = metadataImporterService;
        this.monitorService = monitorService;
        this.stageSessionService = stageSessionService;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/records")
    public ResponseEntity<StageRecordPage> list(@RequestParam String site,
                                                @RequestParam(required = false) Integer senderId,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size,
                                                @RequestParam(required = false) String sortBy,
                                                @RequestParam(required = false) String sortDir,
                                                @RequestParam(required = false) String requestId) {
        if (site == null || site.isBlank()) {
            throw new IllegalArgumentException("site is required");
        }
        int resolvedPage = Math.max(page, 0);
        int resolvedSize = size <= 0 ? 50 : Math.min(size, 500);
        int offset = resolvedPage * resolvedSize;
        // If the caller is not an admin, limit returned records to those owned by the user at the SQL level.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<StageRecord> records;
        long total;
        if (q != null && !q.isBlank()) {
            // Delegate q-filtering to SQL-level implementation in RefDbService for efficiency.
            if (auth == null) {
                records = refDbService.listRecords(site, senderId, status, q, offset, resolvedSize, sortBy, sortDir, requestId);
                total = refDbService.countRecords(site, senderId, status, q, requestId);
            } else {
                boolean isAdminLocal = false;
                for (var ga : auth.getAuthorities()) {
                    String authority = ga.getAuthority();
                    if ("ROLE_ADMIN".equals(authority) || "ADMIN".equals(authority) || "ROLE_SUPER_ADMIN".equals(authority)) {
                        isAdminLocal = true;
                        break;
                    }
                }
                String usernameLocal = auth.getName() == null ? "" : auth.getName().trim();
                if (isAdminLocal) {
                    records = refDbService.listRecords(site, senderId, status, q, offset, resolvedSize, sortBy, sortDir, requestId);
                    total = refDbService.countRecords(site, senderId, status, q, requestId);
                } else if (!usernameLocal.isEmpty()) {
                    records = refDbService.listRecordsForUser(site, senderId, status, q, offset, resolvedSize, usernameLocal.toLowerCase(), sortBy, sortDir, requestId);
                    total = refDbService.countRecordsForUser(site, senderId, status, usernameLocal.toLowerCase(), q, requestId);
                } else {
                    records = refDbService.listRecords(site, senderId, status, q, offset, resolvedSize, sortBy, sortDir, requestId);
                    total = refDbService.countRecords(site, senderId, status, q, requestId);
                }
            }
        } else {
            if (auth == null) {
                records = refDbService.listRecords(site, senderId, status, offset, resolvedSize, sortBy, sortDir, requestId);
                total = refDbService.countRecords(site, senderId, status, null, requestId);
            } else {
                boolean isAdmin = false;
                for (var ga : auth.getAuthorities()) {
                    String authority = ga.getAuthority();
                    if ("ROLE_ADMIN".equals(authority) || "ADMIN".equals(authority) || "ROLE_SUPER_ADMIN".equals(authority)) {
                        isAdmin = true;
                        break;
                    }
                }
                String username = auth.getName() == null ? "" : auth.getName().trim();
                if (isAdmin) {
                    records = refDbService.listRecords(site, senderId, status, offset, resolvedSize, sortBy, sortDir, requestId);
                    total = refDbService.countRecords(site, senderId, status, null, requestId);
                } else if (!username.isEmpty()) {
                    records = refDbService.listRecordsForUser(site, senderId, status, offset, resolvedSize, username.toLowerCase(), sortBy, sortDir, requestId);
                    total = refDbService.countRecordsForUser(site, senderId, status, username.toLowerCase(), null, requestId);
                } else {
                    records = refDbService.listRecords(site, senderId, status, offset, resolvedSize, sortBy, sortDir, requestId);
                    total = refDbService.countRecords(site, senderId, status, null, requestId);
                }
            }
        }
        List<StageRecordView> items = records.stream().map(mapper::toView).toList();
        StageRecordPage response = new StageRecordPage(items, total, resolvedPage, resolvedSize);
        return ResponseEntity.ok(response);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/stats")
    public ResponseEntity<java.util.Map<String, Object>> stats(@RequestParam(required = false) String requestId) {
        java.util.Map<String, Object> stats = metadataImporterService.getStagingStats(requestId);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/monitor")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter monitor(@RequestParam String requestId,
                                                                                    @RequestParam(required = false) String token) {
        // Token parameter is for EventSource compatibility (EventSource can't send custom headers)
        // Authentication is still enforced by Spring Security filter chain
        return monitorService.createEmitter(requestId);
    }

    // Simple SSE test endpoint (no auth required for testing)
    @GetMapping("/test-sse")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter testSse(jakarta.servlet.http.HttpServletResponse response) {
        log.info("Test SSE endpoint called");

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60000L);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);
                log.info("Sending test SSE event");
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name("TEST")
                        .data(Map.of("message", "SSE is working!", "timestamp", Instant.now().toString())));
                log.info("Test SSE event sent successfully");
            } catch (Exception e) {
                log.error("Failed to send test SSE event", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @org.springframework.web.bind.annotation.PostMapping(path = "/records/csv")
    public org.springframework.http.ResponseEntity<StreamingResponseBody> exportCsv(@org.springframework.web.bind.annotation.RequestBody com.onsemi.cim.apps.exensio.resender.dto.StageRecordsCsvRequest request,
                                                                                    HttpServletRequest httpRequest) {
        String site = request.site();
        Integer senderId = request.senderId();
        String status = request.status();
        int pageSize = request.size() > 0 ? request.size() : 1000;
        String q = request.q();
        final boolean hasQuery = q != null && !q.isBlank();
        final String qnorm = hasQuery ? q.trim().toLowerCase() : null;
        if (site == null || site.isBlank()) {
            return org.springframework.http.ResponseEntity.badRequest().body(null);
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        final boolean isAdminFinal;
        final String usernameFinal;
        if (auth != null) {
            boolean _isAdmin = false;
            for (var ga : auth.getAuthorities()) {
                String authority = ga.getAuthority();
                if ("ROLE_ADMIN".equals(authority) || "ADMIN".equals(authority)) {
                    _isAdmin = true;
                    break;
                }
            }
            isAdminFinal = _isAdmin;
            usernameFinal = auth.getName() == null ? null : auth.getName().trim();
        } else {
            isAdminFinal = false;
            usernameFinal = null;
        }

        boolean clientAcceptsGzip = false;
        try {
            String ae = httpRequest.getHeader("Accept-Encoding");
            if (ae != null && ae.toLowerCase().contains("gzip")) clientAcceptsGzip = true;
        } catch (Exception ignore) {}

        final boolean useGzip = clientAcceptsGzip;

        StreamingResponseBody stream = outputStream -> {
            OutputStream os = outputStream;
            GZIPOutputStream gzos = null;
            BufferedWriter writer = null;
            try {
                if (useGzip) {
                    gzos = new GZIPOutputStream(os, true);
                    writer = new BufferedWriter(new OutputStreamWriter(gzos, StandardCharsets.UTF_8));
                } else {
                    writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
                }

                // write header
                writer.write("id,site,senderId,metadataId,dataId,lot,wafer,filename,status,stagedBy,lastRequestedBy,createdAt,updatedAt,processedAt,errorMessage\n");
                writer.flush();

                long total = Long.MAX_VALUE;
                int page = 0;
                while (true) {
                    int offset = page * pageSize;
                    java.util.List<com.onsemi.cim.apps.exensio.resender.stage.StageRecord> pageRows;
                    long pageTotal;
                    if (auth == null || isAdminFinal) {
                        if (hasQuery) {
                            pageRows = refDbService.listRecords(site, senderId, status, q, offset, pageSize);
                            pageTotal = refDbService.countRecords(site, senderId, status, q, null);
                        } else {
                            pageRows = refDbService.listRecords(site, senderId, status, offset, pageSize);
                            pageTotal = refDbService.countRecords(site, senderId, status, null, null);
                        }
                    } else if (usernameFinal != null && !usernameFinal.isBlank()) {
                        if (hasQuery) {
                            pageRows = refDbService.listRecordsForUser(site, senderId, status, q, offset, pageSize, usernameFinal.toLowerCase());
                            pageTotal = refDbService.countRecordsForUser(site, senderId, status, usernameFinal.toLowerCase(), q, null);
                        } else {
                            pageRows = refDbService.listRecordsForUser(site, senderId, status, offset, pageSize, usernameFinal.toLowerCase());
                            pageTotal = refDbService.countRecordsForUser(site, senderId, status, usernameFinal.toLowerCase(), null, null);
                        }
                    } else {
                        if (hasQuery) {
                            pageRows = refDbService.listRecords(site, senderId, status, q, offset, pageSize);
                            pageTotal = refDbService.countRecords(site, senderId, status, q, null);
                        } else {
                            pageRows = refDbService.listRecords(site, senderId, status, offset, pageSize);
                            pageTotal = refDbService.countRecords(site, senderId, status, null, null);
                        }
                    }
                    if (total == Long.MAX_VALUE) total = pageTotal;
                    if (pageRows == null || pageRows.isEmpty()) break;
                    for (com.onsemi.cim.apps.exensio.resender.stage.StageRecord r : pageRows) {
                        StageRecordView view = mapper.toView(r);
                        StringBuilder sb = new StringBuilder();
                        sb.append(csvEscape(String.valueOf(view.id()))).append(',');
                        sb.append(csvEscape(view.site())).append(',');
                        sb.append(csvEscape(String.valueOf(view.senderId()))).append(',');
                        sb.append(csvEscape(view.metadataId())).append(',');
                        sb.append(csvEscape(view.dataId())).append(',');
                        sb.append(csvEscape(view.lot())).append(',');
                        sb.append(csvEscape(view.wafer())).append(',');
                        sb.append(csvEscape(view.filename())).append(',');
                        sb.append(csvEscape(view.status())).append(',');
                        sb.append(csvEscape(view.stagedBy())).append(',');
                        sb.append(csvEscape(view.lastRequestedBy())).append(',');
                        sb.append(csvEscape(view.createdAt())).append(',');
                        sb.append(csvEscape(view.updatedAt())).append(',');
                        sb.append(csvEscape(view.processedAt())).append(',');
                        sb.append(csvEscape(view.errorMessage())).append('\n');
                        writer.write(sb.toString());
                    }
                    writer.flush();
                    if ((page + 1) * (long) pageSize >= total) break;
                    page++;
                }
                // finish gzip if used
                if (gzos != null) {
                    try { gzos.finish(); } catch (Exception ignore) {}
                }
            } catch (Exception ex) {
                try { log.error("Failed streaming staged records CSV: {}", ex.getMessage(), ex); } catch (Exception ignore) {}
                // best effort: nothing we can do here for the client besides closing
            } finally {
                try { if (writer != null) writer.flush(); } catch (Exception ignore) {}
            }
        };

        String fname = "stage-records-" + site + "-" + (senderId != null ? senderId : "all") + "-" + java.time.Instant.now().toString().replace(':', '-') + ".csv";
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + (useGzip ? fname + ".gz" : fname) + "\"");
        if (useGzip) {
            headers.add(org.springframework.http.HttpHeaders.CONTENT_ENCODING, "gzip");
        }
        headers.add(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8");

        return org.springframework.http.ResponseEntity.ok().headers(headers).body(stream);
    }

    private String csvEscape(String v) {
        if (v == null) return "";
        String s = v.replace("\"", "\"\"");
        boolean needsQuotes = s.contains(",") || s.contains("\n") || s.contains("\r") || s.contains("\"");
        return needsQuotes ? ("\"" + s + "\"") : s;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/sessions")
    public ResponseEntity<com.onsemi.cim.apps.exensio.resender.dto.CreateSessionResponse> createSession(@org.springframework.web.bind.annotation.RequestBody CreateSessionRequest request) {
        if (request == null || request.site() == null || request.site().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.senderId() == null || request.senderId() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        String username = getCurrentUsername();
        com.onsemi.cim.apps.exensio.resender.dto.CreateSessionResponse response = stageSessionService.createSession(username, request.site(), request.senderId(), request.senderName(), request.environment());
        return ResponseEntity.ok(response);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/sessions")
    public ResponseEntity<StagingSessionPage> getSessions(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(required = false) Integer senderId,
                                                          @RequestParam(required = false) String username,
                                                          @RequestParam(required = false) String sessionId,
                                                          @RequestParam(required = false) String site,
                                                          @RequestParam(required = false) String status) {
        String currentUsername = getCurrentUsername();
        String usernameFilter = username;
        boolean isAdmin = isAdminUser();
        int resolvedPage = Math.max(page, 0);
        int resolvedSize = size <= 0 ? 20 : Math.min(size, 200);
        List<StagingSessionSummary> items = isAdmin
                ? stageSessionService.getAllSessions(resolvedPage, resolvedSize, q, senderId, usernameFilter, sessionId, site, status)
                : stageSessionService.getUserSessions(currentUsername, resolvedPage, resolvedSize, q, senderId, sessionId, site, status);
        long total = isAdmin
                ? stageSessionService.countAllSessions(q, senderId, usernameFilter, sessionId, site, status)
                : stageSessionService.countUserSessions(currentUsername, q, senderId, sessionId, site, status);
        return ResponseEntity.ok(new StagingSessionPage(items, total, resolvedPage, resolvedSize));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<StagingSessionDetail> getSession(@PathVariable String sessionId) {
        StagingSessionDetail detail = stageSessionService.getSession(sessionId, getCurrentUsername(), isAdminUser());
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/sessions/{sessionId}/files")
    public ResponseEntity<StageRecordPage> getSessionFiles(@PathVariable String sessionId,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String q,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "100") int size) {
        StageRecordPage response = stageSessionService.getSessionFiles(sessionId, getCurrentUsername(), isAdminUser(), status, q, page, size, mapper);
        return ResponseEntity.ok(response);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/sessions/{sessionId}/lots")
    public ResponseEntity<List<LotWaferProgress>> getSessionLots(@PathVariable String sessionId) {
        List<LotWaferProgress> items = stageSessionService.getSessionLotWaferProgress(sessionId, getCurrentUsername(), isAdminUser());
        return ResponseEntity.ok(items);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/sessions/{sessionId}/analytics")
    public ResponseEntity<SessionAnalyticsResponse> getSessionAnalytics(@PathVariable String sessionId,
                                                                        @RequestParam(defaultValue = "10") int topPairs,
                                                                        @RequestParam(required = false) String startDate,
                                                                        @RequestParam(required = false) String endDate) {
        SessionAnalyticsResponse analytics = stageSessionService.getSessionAnalytics(
                sessionId,
                getCurrentUsername(),
                isAdminUser(),
                topPairs,
                startDate,
                endDate);
        if (analytics == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(analytics);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/sessions/{sessionId}/refresh")
    public ResponseEntity<StagingSessionDetail> refreshSession(@PathVariable String sessionId) {
        StagingSessionDetail detail = stageSessionService.refreshExternalStatus(sessionId, getCurrentUsername(), isAdminUser());
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSession(@PathVariable String sessionId) {
        stageSessionService.cancelSession(sessionId, getCurrentUsername(), isAdminUser());
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("cancelledAt", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping(path = "/sessions/{sessionId}/monitor", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter monitorSession(@PathVariable String sessionId,
                                                                                           @RequestParam(required = false) String token,
                                                                                           jakarta.servlet.http.HttpServletResponse response) {
        // Token parameter is for EventSource compatibility (EventSource can't send custom headers)
        // Authentication is still enforced by Spring Security filter chain
        log.info("SSE monitor endpoint called for sessionId: {}, token present: {}", sessionId, token != null && !token.isEmpty());

        // Set SSE-specific headers
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no"); // Disable nginx buffering

        // CORS headers for SSE
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Credentials", "true");

        log.info("SSE headers set, creating emitter for sessionId: {}", sessionId);

        return monitorService.subscribe(sessionId);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/sessions/{sessionId}/export")
    public org.springframework.http.ResponseEntity<StreamingResponseBody> exportSessionCsv(@PathVariable String sessionId,
                                                                                           HttpServletRequest httpRequest) {
        StagingSessionDetail detail = stageSessionService.getSession(sessionId, getCurrentUsername(), isAdminUser());
        if (detail == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }

        boolean clientAcceptsGzip = false;
        try {
            String ae = httpRequest.getHeader("Accept-Encoding");
            if (ae != null && ae.toLowerCase().contains("gzip")) clientAcceptsGzip = true;
        } catch (Exception ignore) {
        }
        final boolean useGzip = clientAcceptsGzip;

        StreamingResponseBody stream = outputStream -> {
            OutputStream os = outputStream;
            GZIPOutputStream gzos = null;
            BufferedWriter writer = null;
            try {
                if (useGzip) {
                    gzos = new GZIPOutputStream(os, true);
                    writer = new BufferedWriter(new OutputStreamWriter(gzos, StandardCharsets.UTF_8));
                } else {
                    writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
                }
                writer.write("id,site,senderId,metadataId,dataId,lot,wafer,filename,status,stagedBy,lastRequestedBy,createdAt,updatedAt,processedAt,errorMessage\\n");
                writer.flush();

                int page = 0;
                int pageSize = 1000;
                while (true) {
                    int offset = page * pageSize;
                    List<StageRecord> rows = refDbService.listRecords(detail.site(), detail.senderId(), null, offset, pageSize, "updated_at", "desc", sessionId);
                    if (rows == null || rows.isEmpty()) break;
                    for (StageRecord r : rows) {
                        StageRecordView view = mapper.toView(r);
                        StringBuilder sb = new StringBuilder();
                        sb.append(csvEscape(String.valueOf(view.id()))).append(',');
                        sb.append(csvEscape(view.site())).append(',');
                        sb.append(csvEscape(String.valueOf(view.senderId()))).append(',');
                        sb.append(csvEscape(view.metadataId())).append(',');
                        sb.append(csvEscape(view.dataId())).append(',');
                        sb.append(csvEscape(view.lot())).append(',');
                        sb.append(csvEscape(view.wafer())).append(',');
                        sb.append(csvEscape(view.filename())).append(',');
                        sb.append(csvEscape(view.status())).append(',');
                        sb.append(csvEscape(view.stagedBy())).append(',');
                        sb.append(csvEscape(view.lastRequestedBy())).append(',');
                        sb.append(csvEscape(view.createdAt())).append(',');
                        sb.append(csvEscape(view.updatedAt())).append(',');
                        sb.append(csvEscape(view.processedAt())).append(',');
                        sb.append(csvEscape(view.errorMessage())).append('\n');
                        writer.write(sb.toString());
                    }
                    writer.flush();
                    if (rows.size() < pageSize) break;
                    page++;
                }
                if (gzos != null) {
                    try {
                        gzos.finish();
                    } catch (Exception ignore) {
                    }
                }
            } finally {
                try {
                    if (writer != null) writer.flush();
                } catch (Exception ignore) {
                }
            }
        };

        String fname = "stage-session-" + sessionId + "-" + Instant.now().toString().replace(':', '-') + ".csv";
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + (useGzip ? fname + ".gz" : fname) + "\"");
        if (useGzip) {
            headers.add(org.springframework.http.HttpHeaders.CONTENT_ENCODING, "gzip");
        }
        headers.add(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8");

        return org.springframework.http.ResponseEntity.ok().headers(headers).body(stream);
    }

    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return "system";
        }
        return authentication.getName().trim();
    }

    private boolean isAdminUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (var ga : authentication.getAuthorities()) {
            String authority = ga.getAuthority();
            if ("ROLE_ADMIN".equals(authority) || "ADMIN".equals(authority)
                    || "ROLE_SUPER_ADMIN".equals(authority) || "SUPER_ADMIN".equals(authority)) {
                return true;
            }
        }
        return false;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
    @GetMapping("/records/coverage")
    public ResponseEntity<List<com.onsemi.cim.apps.exensio.resender.dto.CoveragePoint>> getCoverage(
            @RequestParam String site,
            @RequestParam(required = false) Integer senderId,
            @RequestParam(required = false, defaultValue = "day") String granularity,
            @RequestParam(required = false) String endTimeFrom,
            @RequestParam(required = false) String endTimeTo) {
        if (site == null || site.isBlank()) {
            throw new IllegalArgumentException("site is required");
        }
        List<com.onsemi.cim.apps.exensio.resender.dto.CoveragePoint> points =
                refDbService.getCoverage(site, senderId, granularity, endTimeFrom, endTimeTo);
        return ResponseEntity.ok(points);
    }

}
