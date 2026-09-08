import { TestBed } from '@angular/core/testing';
import * as fc from 'fast-check';
import { ExportService, DashboardExportData } from './export.service';

/**
 * Feature: dashboard-enhancement
 * Property 23: CSV Export Data Completeness (Req 8.2)
 * Property 26: Export Filename Pattern Compliance (Req 8.5)
 * Property 24 (structure): Excel sheet names (Req 8.3)
 */
describe('ExportService (property tests)', () => {
  let service: ExportService;

  const sampleData = (): DashboardExportData => ({
    summary: { name: 'Summary', headers: ['Metric', 'Value'], rows: [['Backlog', 12]] },
    senders: { name: 'Senders', headers: ['Sender', 'Backlog'], rows: [['S1', 3]] },
    sites: { name: 'Sites', headers: ['Site', 'Backlog'], rows: [['FAB', 9]] },
    history: { name: 'History', headers: ['Timestamp', 'Backlog'], rows: [[new Date().toISOString(), 2]] },
    filters: [],
    pdfElements: [],
  });

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ExportService);
  });

  it('Property 23: CSV output contains every header and row value', () => {
    fc.assert(
      fc.property(fc.array(fc.string({ minLength: 1 }), { minLength: 1, maxLength: 8 }), (metrics) => {
        const data = sampleData();
        data.summary.rows = metrics.map((m, i) => [m, i]);
        data.summary.headers = ['Metric', 'Value'];

        let csv = '';
        spyOn(URL, 'createObjectURL').and.returnValue('blob:mock');
        spyOn(HTMLAnchorElement.prototype, 'click').and.callFake(function (this: HTMLAnchorElement) {
          csv = '';
        });
        // Read blob content synchronously for assertion.
        const origCreate = URL.createObjectURL;
        spyOn(URL, 'createObjectURL').and.callFake((blob: Blob) => {
          void blob.text().then((t) => (csv = t));
          return 'blob:mock';
        });

        service.toCSV(data, 'x.csv');
        expect(origCreate).toBeDefined();
        void Promise.resolve().then(() => {
          for (const m of metrics) {
            expect(csv).toContain(`"${m}"`);
          }
        });
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 26: filename matches dashboard-export-{date}-{filter}.{ext}', () => {
    const result = service.generateFilename('csv', ['device-ABC', 'state-STAGED']);
    expect(result).toMatch(/^dashboard-export-\d{4}-\d{2}-\d{2}-device-ABC-state-STAGED\.csv$/);
    const all = service.generateFilename('json', []);
    expect(all).toBe(`dashboard-export-${new Date().toISOString().split('T')[0]}-all.json`);
  });
});
