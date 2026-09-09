package com.onsemi.cim.apps.exensio.exensioreload.stage;

public record PayloadCandidate(String metadataId, String dataId, String lot, String wafer, String filename, java.time.Instant endTime, String dataType, String testPhase, String device, String step, String testerId, String testProgram) {
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
        if (device != null && device.isBlank()) device = null;
        if (step != null && step.isBlank()) step = null;
        if (testerId != null && testerId.isBlank()) testerId = null;
        if (testProgram != null && testProgram.isBlank()) testProgram = null;
    }

    public PayloadCandidate(String metadataId, String dataId) {
        this(metadataId, dataId, null, null, null, null, null, null, null, null, null, null);
    }

    public PayloadCandidate(String metadataId, String dataId, String lot, String wafer, String filename, java.time.Instant endTime) {
        this(metadataId, dataId, lot, wafer, filename, endTime, null, null, null, null, null, null);
    }

    public PayloadCandidate(String metadataId, String dataId, String lot, String wafer, String filename, java.time.Instant endTime, String dataType, String testPhase) {
        this(metadataId, dataId, lot, wafer, filename, endTime, dataType, testPhase, null, null, null, null);
    }

    public PayloadCandidate(String metadataId, String dataId, String lot, String wafer, String filename, java.time.Instant endTime, String dataType, String testPhase, String device) {
        this(metadataId, dataId, lot, wafer, filename, endTime, dataType, testPhase, device, null, null, null);
    }
}
