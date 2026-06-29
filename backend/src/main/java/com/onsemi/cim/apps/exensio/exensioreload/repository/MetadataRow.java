package com.onsemi.cim.apps.exensio.exensioreload.repository;

import java.time.LocalDateTime;

public class MetadataRow {
    private final String lot;
    private final String id;
    private final String idData;
    private final LocalDateTime endTime;
    private final String wafer;
    private final String originalFileName;
    private final String device;

    public MetadataRow(String lot, String id, String idData, LocalDateTime endTime, String wafer, String originalFileName, String device) {
        this.lot = lot;
        this.id = id;
        this.idData = idData;
        this.endTime = endTime;
        this.wafer = wafer;
        this.originalFileName = originalFileName;
        this.device = device;
    }

    public String getLot() { return lot; }
    public String getId() { return id; }
    public String getIdData() { return idData; }
    public LocalDateTime getEndTime() { return endTime; }
    public String getWafer() { return wafer; }
    public String getOriginalFileName() { return originalFileName; }
    public String getDevice() { return device; }
}
