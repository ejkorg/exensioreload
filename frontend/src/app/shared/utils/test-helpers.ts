import * as fc from 'fast-check';

/**
 * Shared arbitrary (generator) helpers for property-based tests.
 * See: .kiro/specs/dashboard-enhancement/design.md — Correctness Properties.
 */

/** Arbitrary non-negative integer metric value (0..100_000). */
export const arbitraryMetricValue = fc.integer({ min: 0, max: 100_000 });

/** Arbitrary percentage value (0..100). */
export const arbitraryPercent = fc.integer({ min: 0, max: 100 });

/** Arbitrary finite backlog value commonly seen in sender cards. */
export const arbitraryBacklog = fc.integer({ min: 0, max: 10_000 });

/** Arbitrary epoch-ms timestamp within the last 7 days. */
export const arbitraryRecentTimestamp = fc
  .integer({ min: Date.now() - 7 * 24 * 60 * 60 * 1000, max: Date.now() })
  .map((ts) => ts);

/** Time-series point generator for a single metric. */
export const arbitraryMetricPoint = fc.record({
  timestamp: arbitraryRecentTimestamp,
  value: arbitraryMetricValue,
});

/** Full dashboard metric totals record (all keys numeric). */
export const arbitraryTotals = fc.record({
  backlog: arbitraryMetricValue,
  ready: arbitraryMetricValue,
  enqueued: arbitraryMetricValue,
  completed: arbitraryMetricValue,
  failed: arbitraryMetricValue,
  cancelled: arbitraryMetricValue,
});

/** Pipeline state values consistent with Requirement 16.1. */
export const pipelineStatesArb = fc.constantFrom(
  'STAGED',
  'QUEUED_FOR_CP',
  'ELASTICSEARCH_MONITORING',
  'CP_TIMEOUT',
  'EXENSIO_MONITORING',
  'COMPLETED_MANUAL_VERIFICATION_REQUIRED',
  'COMPLETED',
  'CP_FAILED',
  'CANCELLED',
);

/** Session status values consistent with Requirement 17.1. */
export const sessionStatusArb = fc.constantFrom(
  'PENDING',
  'IN_PROGRESS',
  'COMPLETED',
  'PARTIALLY_FAILED',
  'CANCELLED',
);

/** Mock time-series history of n points for throughput tests. */
export function generateMockHistory(minutes: number, completionsPerMinute: number): number[] {
  const points: number[] = [];
  let running = 0;
  for (let i = 0; i < minutes; i++) {
    running += completionsPerMinute;
    points.push(running);
  }
  return points;
}
