package com.onsemi.cim.apps.exensio.resender.dto;

import java.util.List;

public class EnqueueResult {
    private int enqueuedCount;
    private List<String> skippedPayloads;
    private long pendingBefore;
    private long pendingAfter;

    public EnqueueResult() {}

    public EnqueueResult(int enqueuedCount, List<String> skippedPayloads) {
        this.enqueuedCount = enqueuedCount;
        this.skippedPayloads = skippedPayloads;
    }

    public EnqueueResult(int enqueuedCount, List<String> skippedPayloads, long pendingBefore, long pendingAfter) {
        this.enqueuedCount = enqueuedCount;
        this.skippedPayloads = skippedPayloads;
        this.pendingBefore = pendingBefore;
        this.pendingAfter = pendingAfter;
    }

    public int getEnqueuedCount() { return enqueuedCount; }
    public void setEnqueuedCount(int enqueuedCount) { this.enqueuedCount = enqueuedCount; }
    public List<String> getSkippedPayloads() { return skippedPayloads; }
    public void setSkippedPayloads(List<String> skippedPayloads) { this.skippedPayloads = skippedPayloads; }

    public long getPendingBefore() { return pendingBefore; }
    public void setPendingBefore(long pendingBefore) { this.pendingBefore = pendingBefore; }
    public long getPendingAfter() { return pendingAfter; }
    public void setPendingAfter(long pendingAfter) { this.pendingAfter = pendingAfter; }
}
