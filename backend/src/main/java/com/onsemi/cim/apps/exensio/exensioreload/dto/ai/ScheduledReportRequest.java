package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for scheduled report configuration.
 */
public class ScheduledReportRequest {
    private String reportName;
    private String frequency;  // HOURLY, DAILY, WEEKLY, MONTHLY
    private String time;
    private String dayOfWeek;  // MONDAY-SUNDAY
    private Integer dayOfMonth;  // 1-31
    private List<String> channels;
    private List<String> recipients;
    private String site;

    public String getReportName() { return reportName; }
    public void setReportName(String reportName) { this.reportName = reportName; }
    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    public String getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(String dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public Integer getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(Integer dayOfMonth) { this.dayOfMonth = dayOfMonth; }
    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels; }
    public List<String> getRecipients() { return recipients; }
    public void setRecipients(List<String> recipients) { this.recipients = recipients; }
    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
}