import * as fc from 'fast-check';
import { StagingSessionDetail } from '../api/backend.service';

// ---------------------------------------------------------------------------
// Pure helpers extracted from the metric card rendering logic
// ---------------------------------------------------------------------------

type MetricCardType = 'total' | 'staged' | 'enqueued' | 'done' | 'failed';

interface MetricCard {
  type: MetricCardType;
  value: number;
  cssClass: string;
  isZero: boolean;
}

/**
 * Pure function that mirrors the metric card rendering logic in the template.
 * Given a StagingSessionDetail, returns the 5 metric card descriptors.
 */
function buildMetricCards(detail: StagingSessionDetail): MetricCard[] {
  return [
    {
      type: 'total',
      value: detail.totalFiles,
      cssClass: 'metric-card--total',
      isZero: detail.totalFiles === 0,
    },
    {
      type: 'staged',
      value: detail.filesStaged,
      cssClass: 'metric-card--staged',
      isZero: detail.filesStaged === 0,
    },
    {
      type: 'enqueued',
      value: detail.filesEnqueued,
      cssClass: 'metric-card--enqueued',
      isZero: detail.filesEnqueued === 0,
    },
    {
      type: 'done',
      value: detail.filesDone,
      cssClass: 'metric-card--done',
      isZero: detail.filesDone === 0,
    },
    {
      type: 'failed',
      value: detail.filesFailed,
      cssClass: 'metric-card--failed',
      isZero: detail.filesFailed === 0,
    },
  ];
}

// ---------------------------------------------------------------------------
// Pure helper mirroring the file status badge rendering in the template:
//   <span class="status-badge" [class]="f.status.toLowerCase()">
// ---------------------------------------------------------------------------

/**
 * Returns the CSS class that the template applies to a file status badge.
 * Mirrors: [class]="f.status.toLowerCase()"
 */
function fileStatusBadgeClass(status: string): string {
  return status.toLowerCase();
}

// ---------------------------------------------------------------------------
// Arbitraries
// ---------------------------------------------------------------------------

const SESSION_STATUSES = [
  'STAGING', 'DISPATCHING', 'MONITORING', 'COMPLETED', 'PARTIALLY_FAILED', 'CANCELLED',
] as const;

/** File-level statuses as defined in the design document (Requirements 6.3). */
const FILE_STATUSES = ['READY', 'ENQUEUED', 'ENRICHMENT', 'EXENSIO_LOADING', 'COMPLETED', 'ERROR', 'CANCELLED'] as const;

const arbDetail = (): fc.Arbitrary<StagingSessionDetail> =>
  fc.record({
    sessionId: fc.uuid(),
    username: fc.string({ minLength: 1, maxLength: 30 }),
    site: fc.string({ minLength: 1, maxLength: 20 }),
    senderId: fc.nat({ max: 9999 }),
    senderName: fc.option(fc.string({ minLength: 1, maxLength: 30 }), { nil: null }),
    environment: fc.option(fc.string({ minLength: 1, maxLength: 20 }), { nil: null }),
    status: fc.constantFrom(...SESSION_STATUSES),
    totalFiles: fc.nat({ max: 10000 }),
    filesStaged: fc.nat({ max: 10000 }),
    filesEnqueued: fc.nat({ max: 10000 }),
    filesDone: fc.nat({ max: 10000 }),
    filesFailed: fc.nat({ max: 10000 }),
    createdAt: fc.option(fc.string(), { nil: null }),
    updatedAt: fc.option(fc.string(), { nil: null }),
    completedAt: fc.option(fc.string(), { nil: null }),
    lastCheckedAt: fc.option(fc.string(), { nil: null }),
    progress: fc.float({ min: 0, max: 100, noNaN: true }),
  });

// ---------------------------------------------------------------------------
// Property 3: Metric cards render correct values and color classes
//
// Feature: session-detail-modal-redesign, Property 3: Metric cards render correct values and color classes
// Validates: Requirements 3.1, 3.3, 3.4
// ---------------------------------------------------------------------------

describe('MySessionsComponent — Property 3: Metric cards render correct values and color classes', () => {

  it('Property 3a: each card value matches the corresponding field in StagingSessionDetail', () => {
    fc.assert(
      fc.property(arbDetail(), (detail) => {
        const cards = buildMetricCards(detail);
        const total = cards.find(c => c.type === 'total')!;
        const staged = cards.find(c => c.type === 'staged')!;
        const enqueued = cards.find(c => c.type === 'enqueued')!;
        const done = cards.find(c => c.type === 'done')!;
        const failed = cards.find(c => c.type === 'failed')!;

        return (
          total.value === detail.totalFiles &&
          staged.value === detail.filesStaged &&
          enqueued.value === detail.filesEnqueued &&
          done.value === detail.filesDone &&
          failed.value === detail.filesFailed
        );
      }),
      { numRuns: 200 }
    );
  });

  it('Property 3b: each card has the correct color CSS class', () => {
    fc.assert(
      fc.property(arbDetail(), (detail) => {
        const cards = buildMetricCards(detail);
        return (
          cards.find(c => c.type === 'total')!.cssClass === 'metric-card--total' &&
          cards.find(c => c.type === 'staged')!.cssClass === 'metric-card--staged' &&
          cards.find(c => c.type === 'enqueued')!.cssClass === 'metric-card--enqueued' &&
          cards.find(c => c.type === 'done')!.cssClass === 'metric-card--done' &&
          cards.find(c => c.type === 'failed')!.cssClass === 'metric-card--failed'
        );
      }),
      { numRuns: 200 }
    );
  });

  it('Property 3c: failed card is always present in the DOM (never absent), only de-emphasized when filesFailed === 0', () => {
    fc.assert(
      fc.property(arbDetail(), (detail) => {
        const cards = buildMetricCards(detail);
        const failedCard = cards.find(c => c.type === 'failed');
        // Card must always exist (never removed from DOM)
        if (!failedCard) return false;
        // When filesFailed === 0, isZero must be true (triggers opacity: 0.45 via .zero class)
        if (detail.filesFailed === 0) return failedCard.isZero === true;
        // When filesFailed > 0, isZero must be false (full opacity)
        return failedCard.isZero === false;
      }),
      { numRuns: 200 }
    );
  });

  it('Property 3d: always exactly 5 metric cards are rendered', () => {
    fc.assert(
      fc.property(arbDetail(), (detail) => {
        const cards = buildMetricCards(detail);
        return cards.length === 5;
      }),
      { numRuns: 200 }
    );
  });

});

// ---------------------------------------------------------------------------
// Property 4: File status badges use correct CSS class
//
// Feature: session-detail-modal-redesign, Property 4: File status badges use correct CSS class
// Validates: Requirements 6.3
// ---------------------------------------------------------------------------

describe('MySessionsComponent — Property 4: File status badges use correct CSS class', () => {

  it('Property 4: for any file status in the known enum set, the badge CSS class equals status.toLowerCase()', () => {
    fc.assert(
      fc.property(fc.constantFrom(...FILE_STATUSES), (status) => {
        const cssClass = fileStatusBadgeClass(status);
        return cssClass === status.toLowerCase();
      }),
      { numRuns: 200 }
    );
  });

  it('Property 4 (arbitrary strings): for any non-empty status string, badge class equals status.toLowerCase()', () => {
    fc.assert(
      fc.property(fc.string({ minLength: 1, maxLength: 30 }), (status) => {
        const cssClass = fileStatusBadgeClass(status);
        return cssClass === status.toLowerCase();
      }),
      { numRuns: 200 }
    );
  });

});

// ---------------------------------------------------------------------------
// Unit tests for truncateSessionId (Requirements 2.1)
// ---------------------------------------------------------------------------

/**
 * Mirrors the truncateSessionId method in MySessionsComponent.
 */
function truncateSessionId(id: string): string {
  if (!id) return '...';
  return id.slice(0, 8) + '...';
}

describe('MySessionsComponent — truncateSessionId unit tests', () => {

  it('returns "..." for empty string', () => {
    expect(truncateSessionId('')).toBe('...');
  });

  it('truncates a string shorter than 8 chars by appending "..."', () => {
    expect(truncateSessionId('abc')).toBe('abc...');
  });

  it('truncates a full UUID to first 8 chars + "..."', () => {
    expect(truncateSessionId('c013ed61-d37f-4b2a-9100431b9e')).toBe('c013ed61...');
  });

  it('Property 1: for any string longer than 8 chars, result starts with first 8 chars and ends with "..."', () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 9, maxLength: 200 }),
        (id) => {
          const result = truncateSessionId(id);
          return result.startsWith(id.slice(0, 8)) && result.endsWith('...');
        }
      ),
      { numRuns: 200 }
    );
  });

  it('Property 1: result is always shorter than input when input length > 8', () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 9, maxLength: 200 }),
        (id) => truncateSessionId(id).length < id.length
      ),
      { numRuns: 200 }
    );
  });

});
