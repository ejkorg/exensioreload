package com.onsemi.cim.apps.exensio.resender.service;

/**
 * Discriminated union for the outcome of an Exensio lot-wafer-lookup call.
 */
public sealed interface ExensioLotWaferResult {

    /**
     * The wafer was found in Exensio — data has been loaded.
     *
     * @param lotKey   internal Exensio lot key
     * @param waferKey internal Exensio wafer key
     * @param pgKey    program key (enables future results queries)
     * @param ppid     parametric program ID
     */
    record Found(long lotKey, long waferKey, long pgKey, String ppid) implements ExensioLotWaferResult {}

    /**
     * No matching wafer found — data not yet loaded into Exensio.
     * The monitor should retry on the next cycle.
     */
    record NotFound() implements ExensioLotWaferResult {}

    /**
     * The API call failed (network error, auth error, unexpected response).
     * The monitor should skip this record and log a warning.
     */
    record Error(String message) implements ExensioLotWaferResult {}
}
