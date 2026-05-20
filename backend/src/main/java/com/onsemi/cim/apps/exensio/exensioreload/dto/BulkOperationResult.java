package com.onsemi.cim.apps.exensio.exensioreload.dto;

/**
 * Response for bulk sender operations.
 * Matches the frontend contract: { success: number; failed: number; message: string }.
 */

/**
 * @author fg8n8x
 */

public record BulkOperationResult(
        int success,
        int failed,
        String message
) {
    public static BulkOperationResult ok(int count, String verb) {
        return new BulkOperationResult(count, 0,
                verb + " " + count + " sender(s) successfully");
    }

    public static BulkOperationResult partial(int success, int failed, String verb) {
        return new BulkOperationResult(success, failed,
                verb + " " + success + " sender(s), " + failed + " failed");
    }

    public static BulkOperationResult error(String message) {
        return new BulkOperationResult(0, 0, message);
    }
}

