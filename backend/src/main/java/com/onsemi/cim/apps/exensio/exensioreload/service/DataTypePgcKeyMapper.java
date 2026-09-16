package com.onsemi.cim.apps.exensio.exensioreload.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single source of truth for mapping stepper Data Type strings to Exensio {@code pgc_key} values.
 *
 * <p>Both the raw-SQL/batch paths and the lot-wafer-lookup batch path resolve through this
 * mapper so a given data type always yields the same program group class.</p>
 */
public final class DataTypePgcKeyMapper {

    private static final Logger log = LoggerFactory.getLogger(DataTypePgcKeyMapper.class);

    public static final int PGC_KEY_PROBE  = 1;
    public static final int PGC_KEY_FT     = 2;
    public static final int PGC_KEY_WMAP   = 4;
    public static final int PGC_KEY_PCM    = 5;
    public static final int PGC_KEY_DEFECT = 14;

    private DataTypePgcKeyMapper() {}

    /**
     * Resolves the {@code pgc_key} for a given data type string (case-insensitive).
     *
     * <ul>
     *   <li>PROBE                         → 1</li>
     *   <li>FT / FINAL TEST               → 2</li>
     *   <li>MAP / BINMAP / WXML / UPM     → 4</li>
     *   <li>PCM                           → 5</li>
     *   <li>DEFECT                        → 14</li>
     *   <li>anything else / null / blank  → 2 (Final Test)</li>
     * </ul>
     *
     * @param dataType the data type string from the stepper (may be null or blank)
     * @return the {@code pgc_key} to use for Exensio queries
     */
    public static int resolve(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            throw new IllegalArgumentException("dataType is required (cannot be null or blank)");
        }

        String normalized = dataType.trim().toLowerCase();
        int pgcKey;
        if (normalized.equals("defect") || normalized.equals("defects") || normalized.contains("defect") || normalized.equals("klarf")) {
            pgcKey = PGC_KEY_DEFECT; // 14
        } else if (normalized.equals("probe") || normalized.equals("cp") || normalized.contains("probe") || normalized.contains("sort")) {
            pgcKey = PGC_KEY_PROBE; // 1
        } else if (normalized.equals("pcm") || normalized.equals("wat") || normalized.contains("pcm")) {
            pgcKey = PGC_KEY_PCM; // 5
        } else if (normalized.equals("map") || normalized.equals("binmap") || normalized.equals("wxml") || normalized.equals("upm") || normalized.contains("map")) {
            pgcKey = PGC_KEY_WMAP; // 4
        } else if (normalized.equals("ft") || normalized.equals("final test") || normalized.contains("final") || normalized.contains("ft")) {
            pgcKey = PGC_KEY_FT; // 2
        } else {
            throw new IllegalArgumentException("Unknown or unsupported dataType: '" + dataType + "'. Supported values: PROBE, FT, PCM, DEFECT, MAP");
        }

        log.debug("[DataTypePgcKeyMapper] Resolved dataType '{}' to PGC_KEY={}", dataType, pgcKey);
        return pgcKey;
    }
}
