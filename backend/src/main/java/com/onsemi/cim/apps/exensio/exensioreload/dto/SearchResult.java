package com.onsemi.cim.apps.exensio.exensioreload.dto;

public class SearchResult {
    private String path;
    private String lotId;
    private String filename;
    private String location;
    private Integer year;
    private Integer month;

    public SearchResult() {}
    public SearchResult(String path, String lotId, String filename) {
        this.path = path;
        this.lotId = lotId;
        this.filename = filename;
    }
    public SearchResult(String path, String lotId, String filename, String location) {
        this.path = path;
        this.lotId = lotId;
        this.filename = filename;
        this.location = location;
    }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public Integer getMonth() { return month; }
    public void setMonth(Integer month) { this.month = month; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getLotId() { return lotId; }
    public void setLotId(String lotId) { this.lotId = lotId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
