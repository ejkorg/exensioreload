package com.onsemi.cim.apps.exensio.exensioreload.stage;

public record PayloadCandidate(String metadataId, String dataId, String lot, String wafer, String filename, java.time.Instant endTime) {
    public PayloadCandidate {
        if (metadataId == null || metadataId.isBlank()) {
            throw new IllegalArgumentException("metadataId is required");
        }
        if (dataId == null || dataId.isBlank()) {
            throw new IllegalArgumentException("dataId is required");
        }
        if (lot != null && lot.isBlank()) lot = null;
        if (wafer != null && wafer.isBlank()) wafer = null;
        if (filename != null && filename.isBlank()) filename = null;
        if (endTime != null && endTime.toString().isBlank()) endTime = null;
    }

    public PayloadCandidate(String metadataId, String dataId) {
        this(metadataId, dataId, null, null, null, null);
    }
}
