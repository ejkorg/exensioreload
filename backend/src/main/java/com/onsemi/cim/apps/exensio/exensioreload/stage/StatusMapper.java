package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.util.Set;

/**
 * Helper class for mapping SENDER_STAGE status to display status
 */
public class StatusMapper {

    /**
     * Map database status + external queue presence to display status
     *
     * @param dbStatus Status from SENDER_STAGE table
     * @param inExternalQueue Whether the file exists in DTP_SENDER_QUEUE_ITEM
     * @return Display status for UI
     */
    public static String getDisplayStatus(String dbStatus, boolean inExternalQueue) {
        if (dbStatus == null) {
            return "Unknown";
        }

        return switch (dbStatus.toUpperCase()) {
            case "NEW" -> "Staged";
            case "ENRICHMENT" -> inExternalQueue ? "In Queue (pending CP)" : "Enrichment / Translation";
            case "EXENSIO_LOADING" -> "Exensio Loading";
            case "PROCESSING" -> inExternalQueue ? "In Queue (pending CP)" : "Enrichment / Translation"; // legacy compat
            case "DONE" -> "Completed";
            case "FAILED" -> "Failed";
            case "CANCELLED" -> "Cancelled";
            default -> dbStatus;
        };
    }

    /**
     * Determine if a file is in the external queue based on queue keys
     *
     * @param metadataId Metadata ID
     * @param dataId Data ID
     * @param queueKeys Set of keys currently in external queue
     * @return true if file is in queue
     */
    public static boolean isInExternalQueue(String metadataId, String dataId, Set<String> queueKeys) {
        String key = buildKey(metadataId, dataId);
        return queueKeys.contains(key);
    }

    /**
     * Build a composite key from metadata and data IDs
     */
    public static String buildKey(String metadataId, String dataId) {
        String left = metadataId == null ? "" : metadataId.trim();
        String right = dataId == null ? "" : dataId.trim();
        return left + "|" + right;
    }
}
