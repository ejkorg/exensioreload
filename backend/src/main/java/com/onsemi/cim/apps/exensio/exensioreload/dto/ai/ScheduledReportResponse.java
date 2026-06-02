package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Response for scheduled report operations.
 */
public class ScheduledReportResponse {
    private String scheduleId;
    private boolean success;
    private String message;
    private ReportSchedule schedule;
    private String generatedContent;
    private List<String> deliveredTo;

    public static class ReportSchedule {
        private String reportId;
        private String reportName;
        private String frequency;
        private String time;
        private String dayOfWeek;
        private Integer dayOfMonth;
        private boolean enabled;
        private List<String> channels;
        private List<String> recipients;
        private String lastRun;
        private String nextRun;

        public String getReportId() { return reportId; }
        public void setReportId(String reportId) { this.reportId = reportId; }
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
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getChannels() { return channels; }
        public void setChannels(List<String> channels) { this.channels = channels; }
        public List<String> getRecipients() { return recipients; }
        public void setRecipients(List<String> recipients) { this.recipients = recipients; }
        public String getLastRun() { return lastRun; }
        public void setLastRun(String lastRun) { this.lastRun = lastRun; }
        public String getNextRun() { return nextRun; }
        public void setNextRun(String nextRun) { this.nextRun = nextRun; }
    }

    // Getters and setters
    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public ReportSchedule getSchedule() { return schedule; }
    public void setSchedule(ReportSchedule schedule) { this.schedule = schedule; }
    public String getGeneratedContent() { return generatedContent; }
    public void setGeneratedContent(String generatedContent) { this.generatedContent = generatedContent; }
    public List<String> getDeliveredTo() { return deliveredTo; }
    public void setDeliveredTo(List<String> deliveredTo) { this.deliveredTo = deliveredTo; }
}