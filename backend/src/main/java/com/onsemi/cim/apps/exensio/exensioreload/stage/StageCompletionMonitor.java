package com.onsemi.cim.apps.exensio.exensioreload.stage;

/**
 * Downstream verification step applied after CP removes a staged item from
 * {@code DTP_SENDER_QUEUE_ITEM}. Resolved from configured integrations only
 * (feature-toggle / capability pattern).
 */
public enum StageCompletionMonitor {

    /** Poll Elasticsearch CP logs ({@code ENRICHMENT} → {@code EXENSIO_LOADING} or {@code DONE}). */
    ELASTICSEARCH,

    /** Poll Exensio lot-wafer API ({@code EXENSIO_LOADING} → {@code DONE}). */
    EXENSIO_API,

    /** No external verification — mark {@code DONE} when CP consumes the queue row. */
    NONE
}
