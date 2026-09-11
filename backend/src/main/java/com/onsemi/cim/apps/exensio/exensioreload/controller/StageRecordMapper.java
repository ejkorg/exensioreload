package com.onsemi.cim.apps.exensio.exensioreload.controller;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StageRecordView;
import com.onsemi.cim.apps.exensio.exensioreload.service.IntegrationStatusService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

@Component
public class StageRecordMapper {

    private final IntegrationStatusService integrationStatusService;
    private final CpElasticsearchProperties cpProps;
    private final ExensioProperties exensioProps;

    public StageRecordMapper(IntegrationStatusService integrationStatusService,
                             CpElasticsearchProperties cpProps,
                             ExensioProperties exensioProps) {
        this.integrationStatusService = integrationStatusService;
        this.cpProps = cpProps;
        this.exensioProps = exensioProps;
    }

    public StageRecordView toView(StageRecord record) {
        if (record == null) {
            return new StageRecordView(
                    0L,                    // id
                    null,                  // site
                    0,                     // senderId
                    null,                  // senderName
                    null,                  // metadataId
                    null,                  // dataId
                    null,                  // lot
                    null,                  // wafer
                    null,                  // device
                    null,                  // step
                    null,                  // testerId
                    null,                  // testProgram
                    null,                  // filename
                    null,                  // endTime
                    null,                  // status
                    null,                  // errorMessage
                    null,                  // createdAt
                    null,                  // updatedAt
                    null,                  // processedAt
                    "unknown",             // stagedBy
                    "unknown",             // lastRequestedBy
                    null,                  // lastRequestedAt
                    null,                  // cpOutputPath
                    null,                  // cpOutputTarget
                    null,                  // exensioWaferKey
                    null,                  // exensioPgKey
                    null,                  // exensioSchema
                    null,                  // cpOutputSchema
                    null,                  // cpIntegrationStatus
                    null,                  // cpIntegrationMessage
                    null,                  // exensioIntegrationStatus
                    null                   // exensioIntegrationMessage
            );
        }
        String lot = normalizeDisplayValue(record.lot(), "-");
        String wafer = normalizeDisplayValue(record.wafer(), "-");
        String filename = resolveFilename(record);

        // Look up integration status from IntegrationStatusService
        var cpStatus = integrationStatusService.getCpStatusForRecord(record.id());
        var exensioStatus = integrationStatusService.getExensioStatusForRecord(record.id());

        // Determine default CP status
        String cpIntegrationStatus;
        String cpIntegrationMessage = cpStatus != null ? cpStatus.message() : null;
        if (cpStatus != null) {
            cpIntegrationStatus = cpStatus.status();
        } else if (!cpProps.isConfigured()) {
            cpIntegrationStatus = "not_configured";
        } else if (record.cpOutputPath() != null || "EXENSIO_MONITORING".equals(record.status()) || "COMPLETED".equals(record.status())) {
            cpIntegrationStatus = "success";
            cpIntegrationMessage = "CP logs verified";
        } else if ("ELASTICSEARCH_MONITORING".equals(record.status())) {
            cpIntegrationStatus = "pending";
            cpIntegrationMessage = "Monitoring Elasticsearch logs";
        } else if ("QUEUED_FOR_CP".equals(record.status()) || "STAGED".equals(record.status())) {
            cpIntegrationStatus = "pending";
            cpIntegrationMessage = "Waiting for CP dispatch";
        } else if ("CP_TIMEOUT".equals(record.status())) {
            cpIntegrationStatus = "timeout";
            cpIntegrationMessage = record.errorMessage() != null ? record.errorMessage() : "Enrichment timed out";
        } else if ("CP_FAILED".equals(record.status())) {
            cpIntegrationStatus = "failure";
            cpIntegrationMessage = record.errorMessage() != null ? record.errorMessage() : "CP processing failed";
        } else {
            cpIntegrationStatus = "not_configured";
        }

        // Determine default Exensio status
        String exensioIntegrationStatus;
        String exensioIntegrationMessage = exensioStatus != null ? exensioStatus.message() : null;
        if (exensioStatus != null) {
            exensioIntegrationStatus = exensioStatus.status();
        } else if (!exensioProps.isConfigured()) {
            exensioIntegrationStatus = "not_configured";
        } else if ("COMPLETED".equals(record.status()) || record.exensioWaferKey() != null) {
            exensioIntegrationStatus = "success";
            exensioIntegrationMessage = "Loaded in Exensio";
        } else if ("EXENSIO_MONITORING".equals(record.status())) {
            exensioIntegrationStatus = "pending";
            exensioIntegrationMessage = "Monitoring Exensio load";
        } else if ("COMPLETED_MANUAL_VERIFICATION_REQUIRED".equals(record.status())) {
            exensioIntegrationStatus = "timeout";
            exensioIntegrationMessage = record.errorMessage() != null ? record.errorMessage() : "Manual verification required";
        } else if ("LOAD_FAILED".equals(record.status())) {
            exensioIntegrationStatus = "failure";
            exensioIntegrationMessage = record.errorMessage() != null ? record.errorMessage() : "Exensio load failed";
        } else {
            exensioIntegrationStatus = "not_configured";
        }

        return new StageRecordView(
                record.id(),
                record.site(),
                record.senderId(),
                record.senderName(),
                record.metadataId(),
                record.dataId(),
                lot,
                wafer,
                record.device(),
                normalizeDisplayValue(record.step(), "-"),
                normalizeDisplayValue(record.testerId(), "-"),
                normalizeDisplayValue(record.testProgram(), "-"),
                filename,
                toIso(record.endTime()),
                record.status(),
                record.errorMessage(),
                toIso(record.createdAt()),
                toIso(record.updatedAt()),
                toIso(record.processedAt()),
                displayUser(record.stagedBy()),
                displayUser(record.lastRequestedBy()),
                toIso(record.lastRequestedAt()),
                record.cpOutputPath(),
                record.cpOutputTarget(),
                record.exensioWaferKey(),
                record.exensioPgKey(),
                record.exensioSchema(),
                record.cpOutputSchema(),
                cpIntegrationStatus,
                cpIntegrationMessage,
                exensioIntegrationStatus,
                exensioIntegrationMessage
        );
    }

    public String displayUser(String value) {
        if (value == null) {
            return "unknown";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "unknown" : trimmed;
    }

    private String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private String normalizeDisplayValue(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private String resolveFilename(StageRecord record) {
        String filename = normalizeDisplayValue(record.filename(), "");
        if (!filename.isEmpty()) {
            return filename;
        }

        String dataId = normalizeDisplayValue(record.dataId(), "");
        if (!dataId.isEmpty()) {
            return dataId;
        }

        String metadataId = normalizeDisplayValue(record.metadataId(), "");
        if (!metadataId.isEmpty()) {
            return metadataId;
        }

        return "unknown";
    }
}
