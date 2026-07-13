package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.util.Set;

/**
 * Maps SENDER_STAGE database status values to display labels for the UI.
 * All status values must come from {@link PipelineStatus}.
 */
public class StatusMapper {

    public static String getDisplayStatus(String dbStatus, boolean inExternalQueue) {
        if (dbStatus == null) return "Unknown";

        PipelineStatus ps = PipelineStatus.fromDbValue(dbStatus);
        if (ps == null) {
            // Legacy fallback for values not yet migrated
            return dbStatus;
        }

        return switch (ps) {
            case DISCOVERED -> "Discovered";
            case STAGED -> "Loaded to REFDB";
            case QUEUED_FOR_CP -> inExternalQueue ? "In Queue (pending CP)" : "Queued for CP";
            case CP_CONSUMED -> "CP Consumed";
            case ELASTICSEARCH_MONITORING -> inExternalQueue ? "In Queue (pending CP)" : "ES Monitoring";
            case CP_TIMEOUT -> "CP Timeout";
            case EXENSIO_MONITORING -> "Exensio Monitoring";
            case COMPLETED_MANUAL_VERIFICATION_REQUIRED -> "Manual Verification Req'd";
            case COMPLETED -> "Completed";
            case CP_FAILED -> "CP Failed";
            case LOAD_FAILED -> "Load Failed";
            case CANCELLED -> "Cancelled";
        };
    }

    public static boolean isInExternalQueue(String metadataId, String dataId, Set<String> queueKeys) {
        String key = buildKey(metadataId, dataId);
        return queueKeys.contains(key);
    }

    public static String buildKey(String metadataId, String dataId) {
        String left = metadataId == null ? "" : metadataId.trim();
        String right = dataId == null ? "" : dataId.trim();
        return left + "|" + right;
    }
}
