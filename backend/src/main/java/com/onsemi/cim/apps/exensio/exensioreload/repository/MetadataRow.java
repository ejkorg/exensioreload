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
    private final String step;
    private final String testerId;
    private final String testProgram;

    public MetadataRow(String lot, String id, String idData, LocalDateTime endTime, String wafer, String originalFileName, String device, String step, String testerId, String testProgram) {
        this.lot = lot;
        this.id = id;
        this.idData = idData;
        this.endTime = endTime;
        this.wafer = wafer;
        this.originalFileName = originalFileName;
        this.device = device;
        this.step = step;
        this.testerId = testerId;
        this.testProgram = testProgram;
    }

    public String getLot() { return lot; }
    public String getId() { return id; }
    public String getIdData() { return idData; }
    public LocalDateTime getEndTime() { return endTime; }
    public String getWafer() { return wafer; }
    public String getOriginalFileName() { return originalFileName; }
    public String getDevice() { return device; }
    public String getStep() { return step; }
    public String getTesterId() { return testerId; }
    public String getTestProgram() { return testProgram; }
}
