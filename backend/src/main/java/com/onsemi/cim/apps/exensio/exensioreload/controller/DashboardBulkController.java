package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.dto.BulkOperationResult;
import com.onsemi.cim.apps.exensio.exensioreload.dto.BulkSenderRequest;
import com.onsemi.cim.apps.exensio.exensioreload.service.RefDbService;
import com.onsemi.cim.apps.exensio.exensioreload.service.SenderDispatchService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 4.2 — Bulk dashboard actions.
 *
 * All four endpoints accept a JSON body { "senderIds": [1, 2, …] }
 * and return { "success": N, "failed": M, "message": "…" }.
 * The export endpoint returns a CSV blob instead.
 *
 * Semantics aligned with what the backend actually supports:
 * <ul>
 *   <li>resume  — immediately dispatch all NEW (READY) staged records for each selected sender</li>
 *   <li>pause   — cancel (mark CANCELLED) all still-pending NEW records so they won't be dispatched</li>
 *   <li>export  — stream a combined CSV of all staged records across all selected senders</li>
 *   <li>delete  — mark NEW + FAILED records as CANCELLED for all selected senders</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dashboard/bulk")
public class DashboardBulkController {

    private static final Logger log = LoggerFactory.getLogger(DashboardBulkController.class);

    private static final List<String> CANCELLABLE_STATUSES = List.of("STAGED_TO_REFDB", "CP_FAILED");

    private final RefDbService refDbService;
    private final SenderDispatchService senderDispatchService;
    private final StageRecordMapper mapper;

    public DashboardBulkController(RefDbService refDbService,
                                   SenderDispatchService senderDispatchService,
                                   StageRecordMapper mapper) {
        this.refDbService = refDbService;
        this.senderDispatchService = senderDispatchService;
        this.mapper = mapper;
    }

    // =========================================================================
    // POST /api/dashboard/bulk/resume
    // =========================================================================

    /**
     * Immediately dispatch all NEW staged records for each selected sender.
     * Looks up which sites have pending records for each senderId, then calls
     * SenderDispatchService.dispatchSender(site, senderId) for each combination found.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/resume")
    public ResponseEntity<BulkOperationResult> resume(@RequestBody BulkSenderRequest request) {
        List<Integer> senderIds = validatedIds(request);
        if (senderIds.isEmpty()) {
            return ResponseEntity.ok(BulkOperationResult.error("No sender IDs provided"));
        }

        String actor = currentUsername();
        log.info("[BulkResume] {} requested dispatch for senders: {}", actor, senderIds);

        int successCount = 0;
        int failCount = 0;

        for (int senderId : senderIds) {
            // Find all sites that have NEW records for this sender
            List<StageStatus> statuses = refDbService.fetchStatusesFor(null, senderId, null);
            List<String> activeSites = statuses.stream()
                    .filter(s -> s.stagedToRefdb() > 0)   // ready == NEW count
                    .map(StageStatus::site)
                    .toList();

            if (activeSites.isEmpty()) {
                log.debug("[BulkResume] sender {} has no NEW records to dispatch", senderId);
                // Count it as success — nothing to do
                successCount++;
                continue;
            }

            boolean senderOk = true;
            for (String site : activeSites) {
                try {
                    int dispatched = senderDispatchService.dispatchSender(site, senderId);
                    log.info("[BulkResume] sender={} site={} dispatched={}", senderId, site, dispatched);
                } catch (Exception ex) {
                    log.error("[BulkResume] failed sender={} site={}: {}", senderId, site, ex.getMessage(), ex);
                    senderOk = false;
                }
            }

            if (senderOk) {
                successCount++;
            } else {
                failCount++;
            }
        }

        BulkOperationResult result = failCount == 0
                ? BulkOperationResult.ok(successCount, "Resumed")
                : BulkOperationResult.partial(successCount, failCount, "Resumed");
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // POST /api/dashboard/bulk/pause
    // =========================================================================

    /**
     * Cancel all still-pending NEW records for each selected sender so they will
     * not be picked up by the next dispatch cycle.
     * Records already PROCESSING (sent to the external queue) cannot be recalled.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/pause")
    public ResponseEntity<BulkOperationResult> pause(@RequestBody BulkSenderRequest request) {
        List<Integer> senderIds = validatedIds(request);
        if (senderIds.isEmpty()) {
            return ResponseEntity.ok(BulkOperationResult.error("No sender IDs provided"));
        }

        String actor = currentUsername();
        log.info("[BulkPause] {} cancelling NEW records for senders: {}", actor, senderIds);

        int successCount = 0;
        int failCount = 0;

        for (int senderId : senderIds) {
            try {
                // Only cancel NEW; leave PROCESSING/FAILED/DONE untouched
                refDbService.bulkCancelBySender(senderId, List.of("STAGED_TO_REFDB"));
                successCount++;
            } catch (Exception ex) {
                log.error("[BulkPause] failed for sender={}: {}", senderId, ex.getMessage(), ex);
                failCount++;
            }
        }

        BulkOperationResult result = failCount == 0
                ? BulkOperationResult.ok(successCount, "Paused")
                : BulkOperationResult.partial(successCount, failCount, "Paused");
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // POST /api/dashboard/bulk/export
    // =========================================================================

    /**
     * Stream a combined CSV of all staged records for the selected senders.
     * Rows are ordered by sender_id, then updated_at DESC.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> export(@RequestBody BulkSenderRequest request) {
        List<Integer> senderIds = validatedIds(request);
        if (senderIds.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("[BulkExport] {} exporting senders: {}", currentUsername(), senderIds);

        StreamingResponseBody body = outputStream -> {
            OutputStream target = outputStream;
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(target, StandardCharsets.UTF_8))) {

                // CSV header
                writer.write("id,site,senderId,senderName,metadataId,dataId,lot,wafer," +
                        "filename,endTime,status,stagedBy,lastRequestedBy," +
                        "createdAt,updatedAt,processedAt,errorMessage\n");

                int pageSize = 500;
                for (int senderId : senderIds) {
                    int page = 0;
                    while (true) {
                        List<StageRecord> rows = refDbService.listRecords(
                                null, senderId, null, page * pageSize, pageSize);
                        if (rows.isEmpty()) break;

                        for (StageRecord row : rows) {
                            var view = mapper.toView(row);
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

                        page++;
                        if (rows.size() < pageSize) break;
                    }
                }

                writer.flush();
            } catch (Exception ex) {
                log.error("[BulkExport] streaming failed: {}", ex.getMessage(), ex);
            }
        };

        String filename = "bulk-export-" + Instant.now().toString().replace(':', '-') + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        headers.add(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8");

        return ResponseEntity.ok().headers(headers).body(body);
    }

    // =========================================================================
    // POST /api/dashboard/bulk/delete
    // =========================================================================

    /**
     * Mark NEW and FAILED staged records as CANCELLED for each selected sender.
     * This is a soft delete — records remain in the database but are excluded
     * from dispatch and dashboard ready-counts.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/delete")
    public ResponseEntity<BulkOperationResult> delete(@RequestBody BulkSenderRequest request) {
        List<Integer> senderIds = validatedIds(request);
        if (senderIds.isEmpty()) {
            return ResponseEntity.ok(BulkOperationResult.error("No sender IDs provided"));
        }

        String actor = currentUsername();
        log.info("[BulkDelete] {} cancelling NEW+FAILED records for senders: {}", actor, senderIds);

        int successCount = 0;
        int failCount = 0;

        for (int senderId : senderIds) {
            try {
                int cancelled = refDbService.bulkCancelBySender(senderId, CANCELLABLE_STATUSES);
                log.info("[BulkDelete] sender={} cancelled={}", senderId, cancelled);
                successCount++;
            } catch (Exception ex) {
                log.error("[BulkDelete] failed for sender={}: {}", senderId, ex.getMessage(), ex);
                failCount++;
            }
        }

        BulkOperationResult result = failCount == 0
                ? BulkOperationResult.ok(successCount, "Deleted")
                : BulkOperationResult.partial(successCount, failCount, "Deleted");
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<Integer> validatedIds(BulkSenderRequest request) {
        if (request == null || request.senderIds() == null) {
            return List.of();
        }
        List<Integer> valid = new ArrayList<>();
        for (Integer id : request.senderIds()) {
            if (id != null && id > 0) valid.add(id);
        }
        return valid;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }

    private String csv(Object value) {
        String v = value == null ? "" : value.toString();
        String escaped = v.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\r") || escaped.contains("\"")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
