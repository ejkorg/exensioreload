package com.onsemi.cim.apps.exensio.exensioreload.stage;

/**
 * Downstream verification step applied after CP removes a staged item from
 * {@code DTP_SENDER_QUEUE_ITEM}. Resolved from configured integrations only
 * (feature-toggle / capability pattern).
 */
public enum StageCompletionMonitor {

    /** Poll Elasticsearch CP logs ({@code ELASTICSEARCH_MONITORING} → {@code EXENSIO_MONITORING} or {@code COMPLETED}). */
    ELASTICSEARCH,

    /** Poll Exensio lot-wafer API ({@code EXENSIO_MONITORING} → {@code COMPLETED}). */
    EXENSIO_API,

    /** No external verification — mark {@code COMPLETED} when CP consumes the queue row. */
    NONE
}
