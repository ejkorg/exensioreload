package com.onsemi.cim.apps.exensio.exensioreload.util;

/**
 * WaferNormalizerUtils
 *
 * Utility for normalizing wafer identifiers from discovery data.
 * Cleans wafer numbers by:
 * 1. Removing all non-digit characters
 * 2. Padding single digits or numbers < 10 with leading zero (e.g., 1 -> 01, 9 -> 09)
 *
 * Examples:
 *   "W1" -> "01"
 *   "Wafer-9" -> "09"
 *   "W10" -> "10"
 *   "W001" -> "01"
 *   "wafer_42" -> "42"
 *   "" -> null (empty after cleaning)
 */
public class WaferNormalizerUtils {

    private WaferNormalizerUtils() {
        // Utility class, no instantiation
    }

    /**
     * Normalize a wafer identifier to a padded wafer number.
     *
     * @param wafer The raw wafer identifier (e.g., "W1", "Wafer-9", "10")
     * @return Normalized wafer number (e.g., "01", "09", "10"), or null if empty after cleaning
     */
    public static String normalizeWafer(String wafer) {
        if (wafer == null || wafer.isBlank()) {
            return null;
        }

        // Remove all non-digit characters
        String digitsOnly = wafer.replaceAll("[^0-9]", "").trim();

        // If no digits remain, return null
        if (digitsOnly.isEmpty()) {
            return null;
        }

        // Parse as integer to remove leading zeros (e.g., "001" -> 1)
        int waferNum = Integer.parseInt(digitsOnly);

        // Pad to 2 digits (e.g., 1 -> "01", 10 -> "10")
        return String.format("%02d", waferNum);
    }
}
