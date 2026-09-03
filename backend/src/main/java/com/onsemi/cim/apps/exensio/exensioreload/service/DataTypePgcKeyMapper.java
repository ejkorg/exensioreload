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
            log.debug("[DataTypePgcKeyMapper] No dataType provided, defaulting to PGC_KEY=2 (FT)");
            return PGC_KEY_FT;
        }

        String normalized = dataType.trim().toLowerCase();
        int pgcKey = switch (normalized) {
            case "probe" -> PGC_KEY_PROBE;
            case "ft", "final test" -> PGC_KEY_FT;
            case "pcm" -> PGC_KEY_PCM;
            case "defect" -> PGC_KEY_DEFECT;
            case "map", "binmap", "wxml", "upm" -> PGC_KEY_WMAP;
            default -> {
                log.warn("[DataTypePgcKeyMapper] Unknown dataType '{}', defaulting to PGC_KEY=2 (FT)", dataType);
                yield PGC_KEY_FT;
            }
        };

        log.debug("[DataTypePgcKeyMapper] Resolved dataType '{}' to PGC_KEY={}", dataType, pgcKey);
        return pgcKey;
    }
}
