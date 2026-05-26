package com.onsemi.cim.apps.exensio.exensioreload.service;

/**
 * Utility class that maps a stepper Data Type string to the correct Exensio pgc_key.
 * Falls back to wafer-presence logic when the data type is unknown or null.
 */
public final class DataTypePgcKeyMapper {

    public static final int PGC_KEY_PROBE  = 1;
    public static final int PGC_KEY_FT     = 2;
    public static final int PGC_KEY_WMAP   = 4;
    public static final int PGC_KEY_DEFECT = 14;

    private DataTypePgcKeyMapper() {}

    /**
     * Resolves the pgc_key for a given data type string.
     * Mapping is case-insensitive.
     *
     * <ul>
     *   <li>PROBE                          → 1</li>
     *   <li>FT / FINAL TEST / FINAL_TEST   → 2</li>
     *   <li>DEFECT                         → 14</li>
     *   <li>MAP / BIN MAP / BINMAP / WMAP  → 4</li>
     *   <li>anything else / null           → 1 if wafer present, 2 if wafer absent</li>
     * </ul>
     *
     * @param dataType   the data type string from the stepper (may be null)
     * @param waferBlank true when the wafer ID is absent (used for fallback)
     * @return the pgc_key to send in the Exensio lot-wafer-lookup request
     */
    public static int resolve(String dataType, boolean waferBlank) {
        if (dataType != null) {
            String normalized = dataType.trim().toUpperCase();
            switch (normalized) {
                case "PROBE":
                    return PGC_KEY_PROBE;
                case "FT":
                case "FINAL TEST":
                case "FINAL_TEST":
                    return PGC_KEY_FT;
                case "DEFECT":
                    return PGC_KEY_DEFECT;
                case "MAP":
                case "BIN MAP":
                case "BINMAP":
                case "WMAP":
                    return PGC_KEY_WMAP;
                default:
                    break;
            }
        }
        // Fallback: wafer-presence logic
        return waferBlank ? PGC_KEY_FT : PGC_KEY_PROBE;
    }
}
