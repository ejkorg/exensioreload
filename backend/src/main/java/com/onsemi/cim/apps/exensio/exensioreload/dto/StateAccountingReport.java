package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * State accounting verification report for admin debugging.
 * Compares database state distribution against dashboard aggregation.
 */
public class StateAccountingReport {
    private Instant timestamp;
    private DatabaseStateCounts database;
    private DashboardCardCounts dashboardCards;
    private DataIntegrity dataIntegrity;
    private List<SenderStateBreakdown> bySender;

    public StateAccountingReport() {}

    public StateAccountingReport(Instant timestamp,
                                  DatabaseStateCounts database,
                                  DashboardCardCounts dashboardCards,
                                  DataIntegrity dataIntegrity,
                                  List<SenderStateBreakdown> bySender) {
        this.timestamp = timestamp;
        this.database = database;
        this.dashboardCards = dashboardCards;
        this.dataIntegrity = dataIntegrity;
        this.bySender = bySender;
    }

    public static class DatabaseStateCounts {
        private long totalCount;
        private Map<String, Long> states;
        private long sumOfStates;
        private List<Discrepancy> discrepancies;
        // Explicit fields for timeout states (in addition to generic states map)
        private long enrichmentTimeout;
        private long exensioTimeout;

        public DatabaseStateCounts() {}

        public DatabaseStateCounts(long totalCount, Map<String, Long> states, long sumOfStates, List<Discrepancy> discrepancies) {
            this.totalCount = totalCount;
            this.states = states;
            this.sumOfStates = sumOfStates;
            this.discrepancies = discrepancies;
            // Extract timeout values from states map
            this.enrichmentTimeout = states != null ? states.getOrDefault("CP_TIMEOUT", 0L) : 0L;
            this.exensioTimeout = states != null ? states.getOrDefault("COMPLETED_MANUAL_VERIFICATION_REQUIRED", 0L) : 0L;
        }

        public long getTotalCount() { return totalCount; }
        public void setTotalCount(long totalCount) { this.totalCount = totalCount; }

        public Map<String, Long> getStates() { return states; }
        public void setStates(Map<String, Long> states) { 
            this.states = states;
            // Update timeout fields when states map is set
            this.enrichmentTimeout = states != null ? states.getOrDefault("CP_TIMEOUT", 0L) : 0L;
            this.exensioTimeout = states != null ? states.getOrDefault("COMPLETED_MANUAL_VERIFICATION_REQUIRED", 0L) : 0L;
        }

        public long getSumOfStates() { return sumOfStates; }
        public void setSumOfStates(long sumOfStates) { this.sumOfStates = sumOfStates; }

        public List<Discrepancy> getDiscrepancies() { return discrepancies; }
        public void setDiscrepancies(List<Discrepancy> discrepancies) { this.discrepancies = discrepancies; }

        public long getEnrichmentTimeout() { return enrichmentTimeout; }
        public void setEnrichmentTimeout(long enrichmentTimeout) { this.enrichmentTimeout = enrichmentTimeout; }

        public long getExensioTimeout() { return exensioTimeout; }
        public void setExensioTimeout(long exensioTimeout) { this.exensioTimeout = exensioTimeout; }
    }

    public static class DashboardCardCounts {
        private long staged;
        private long queued;
        private long enriching;
        private long enrichmentTimeout;
        private long exensioLoading;
        private long exensioTimeout;
        private long failed;
        private long completed;
        private long cancelled;
        private long sum;

        public DashboardCardCounts() {}

        public DashboardCardCounts(long staged, long queued, long enriching, long enrichmentTimeout, 
                                    long exensioLoading, long exensioTimeout,
                                    long failed, long completed, long cancelled, long sum) {
            this.staged = staged;
            this.queued = queued;
            this.enriching = enriching;
            this.enrichmentTimeout = enrichmentTimeout;
            this.exensioLoading = exensioLoading;
            this.exensioTimeout = exensioTimeout;
            this.failed = failed;
            this.completed = completed;
            this.cancelled = cancelled;
            this.sum = sum;
        }

        public long getStaged() { return staged; }
        public void setStaged(long staged) { this.staged = staged; }

        public long getQueued() { return queued; }
        public void setQueued(long queued) { this.queued = queued; }

        public long getEnriching() { return enriching; }
        public void setEnriching(long enriching) { this.enriching = enriching; }

        public long getEnrichmentTimeout() { return enrichmentTimeout; }
        public void setEnrichmentTimeout(long enrichmentTimeout) { this.enrichmentTimeout = enrichmentTimeout; }

        public long getExensioLoading() { return exensioLoading; }
        public void setExensioLoading(long exensioLoading) { this.exensioLoading = exensioLoading; }

        public long getExensioTimeout() { return exensioTimeout; }
        public void setExensioTimeout(long exensioTimeout) { this.exensioTimeout = exensioTimeout; }

        public long getFailed() { return failed; }
        public void setFailed(long failed) { this.failed = failed; }

        public long getCompleted() { return completed; }
        public void setCompleted(long completed) { this.completed = completed; }

        public long getCancelled() { return cancelled; }
        public void setCancelled(long cancelled) { this.cancelled = cancelled; }

        public long getSum() { return sum; }
        public void setSum(long sum) { this.sum = sum; }
    }

    public static class DataIntegrity {
        private boolean valid;
        private List<String> warnings;
        private List<String> errors;

        public DataIntegrity() {}

        public DataIntegrity(boolean valid, List<String> warnings, List<String> errors) {
            this.valid = valid;
            this.warnings = warnings;
            this.errors = errors;
        }

        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }

        public List<String> getWarnings() { return warnings; }
        public void setWarnings(List<String> warnings) { this.warnings = warnings; }

        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
    }

    public static class Discrepancy {
        private String type;
        private String description;

        public Discrepancy() {}

        public Discrepancy(String type, String description) {
            this.type = type;
            this.description = description;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class SenderStateBreakdown {
        private String site;
        private int senderId;
        private String senderName;
        private long total;
        private Map<String, Long> states;

        public SenderStateBreakdown() {}

        public SenderStateBreakdown(String site, int senderId, String senderName, long total, Map<String, Long> states) {
            this.site = site;
            this.senderId = senderId;
            this.senderName = senderName;
            this.total = total;
            this.states = states;
        }

        public String getSite() { return site; }
        public void setSite(String site) { this.site = site; }

        public int getSenderId() { return senderId; }
        public void setSenderId(int senderId) { this.senderId = senderId; }

        public String getSenderName() { return senderName; }
        public void setSenderName(String senderName) { this.senderName = senderName; }

        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }

        public Map<String, Long> getStates() { return states; }
        public void setStates(Map<String, Long> states) { this.states = states; }
    }

    // Getters and setters
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public DatabaseStateCounts getDatabase() { return database; }
    public void setDatabase(DatabaseStateCounts database) { this.database = database; }

    public DashboardCardCounts getDashboardCards() { return dashboardCards; }
    public void setDashboardCards(DashboardCardCounts dashboardCards) { this.dashboardCards = dashboardCards; }

    public DataIntegrity getDataIntegrity() { return dataIntegrity; }
    public void setDataIntegrity(DataIntegrity dataIntegrity) { this.dataIntegrity = dataIntegrity; }

    public List<SenderStateBreakdown> getBySender() { return bySender; }
    public void setBySender(List<SenderStateBreakdown> bySender) { this.bySender = bySender; }
}
