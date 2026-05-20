package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StageRecordView;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class StageRecordMapper {

    public StageRecordView toView(StageRecord record) {
        if (record == null) {
            return new StageRecordView(
                    0L,
                    null,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "unknown",
                    "unknown",
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
        String lot = normalizeDisplayValue(record.lot(), "-");
        String wafer = normalizeDisplayValue(record.wafer(), "-");
        String filename = resolveFilename(record);

        return new StageRecordView(
                record.id(),
                record.site(),
                record.senderId(),
                record.senderName(),
                record.metadataId(),
                record.dataId(),
                lot,
                wafer,
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
                record.exensioPgKey()
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
