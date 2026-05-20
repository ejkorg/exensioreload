package com.onsemi.cim.apps.exensio.resender.dto;

/**
 * Simple aggregate bucket totals used for lot/wafer drilldown metrics.
 */
public record DashboardBucketTotals(
        long total,
        long ready,
        long enqueued,
        long failed,
        long completed,
        long backlog
) {

    public static DashboardBucketTotals of(long ready, long enqueued, long failed, long completed) {
        long backlog = Math.max(0L, ready + enqueued);
        long total = Math.max(0L, ready + enqueued + failed + completed);
        return new DashboardBucketTotals(total, ready, enqueued, failed, completed, backlog);
    }

    public DashboardBucketTotals add(DashboardBucketTotals other) {
        if (other == null) {
            return this;
        }
        return new DashboardBucketTotals(
                Math.max(0L, this.total + other.total),
                Math.max(0L, this.ready + other.ready),
                Math.max(0L, this.enqueued + other.enqueued),
                Math.max(0L, this.failed + other.failed),
                Math.max(0L, this.completed + other.completed),
                Math.max(0L, this.backlog + other.backlog)
        );
    }
}
