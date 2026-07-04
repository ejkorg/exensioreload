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
        if (cpStatus != null) {
            cpIntegrationStatus = cpStatus.status();
        } else if (record.status() != null && record.status().equals("ENRICHMENT") && cpProps.isConfigured()) {
            cpIntegrationStatus = "pending";
        } else if (!cpProps.isConfigured()) {
            cpIntegrationStatus = "not_configured";
        } else {
            cpIntegrationStatus = "not_configured";
        }

        // Determine default Exensio status
        String exensioIntegrationStatus;
        if (exensioStatus != null) {
            exensioIntegrationStatus = exensioStatus.status();
        } else if (record.status() != null && record.status().equals("EXENSIO_LOADING") && exensioProps.isConfigured()) {
            exensioIntegrationStatus = "pending";
        } else if (!exensioProps.isConfigured()) {
            exensioIntegrationStatus = "not_configured";
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
                cpIntegrationStatus,
                cpStatus != null ? cpStatus.message() : null,
                exensioIntegrationStatus,
                exensioStatus != null ? exensioStatus.message() : null
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
