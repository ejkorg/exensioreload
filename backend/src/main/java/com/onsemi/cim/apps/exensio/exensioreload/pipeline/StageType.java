package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

/**
 * Enum of all supported stage types in the pipeline orchestration system.
 * Each type corresponds to a specific enrichment operation.
 */
public enum StageType {
    /**
     * Command Processor - ETL process that transforms raw data files into standardized format
     * and logs events to Elasticsearch
     */
    CP,

    /**
     * Parametric Program Log - ETL process that processes data and logs events to refdb.pp_log
     * Can be an additional stage or replacement for CP processing
     */
    PPLOG,

    /**
     * Exensio verification - Verifies data against Exensio API
     */
    EXENSIO,

    /**
     * Scribe enrichment stage (future)
     */
    SCRIBE,

    /**
     * ShipScribe enrichment stage (future)
     */
    SHIP_SCRIBE,

    /**
     * Lot Genealogy enrichment stage (future)
     */
    LOT_GENEALOGY,

    /**
     * Reftable enrichment stage (future)
     */
    REFTABLE
}
