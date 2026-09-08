import { Injectable } from '@angular/core';
import * as XLSX from 'xlsx';
import jsPDF from 'jspdf';
import html2canvas from 'html2canvas';

export type ExportFormat = 'csv' | 'excel' | 'json' | 'pdf';

export interface ExportTable {
  name: string;
  headers: string[];
  rows: (string | number)[][];
}

export interface DashboardExportData {
  summary: ExportTable;
  senders: ExportTable;
  sites: ExportTable;
  history: ExportTable;
  filters: string[]; // active filter descriptions used in the filename
  /** Optional DOM selectors to render into PDF (charts / KPI grid). */
  pdfElements?: string[];
}

/**
 * ExportService generates dashboard exports in CSV, JSON, Excel and PDF.
 *
 * - Excel (Req 8.3): xlsx workbook with sheets: Summary, Senders, Sites, History
 * - PDF (Req 8.4): jsPDF + html2canvas rendering KPI/chart DOM elements
 * - CSV (Req 8.2): ISO-8601 timestamps where applicable
 * - Filename (Req 8.5): dashboard-export-{YYYY-MM-DD}-{filter}.{ext}
 */
@Injectable({ providedIn: 'root' })
export class ExportService {
  async export(data: DashboardExportData, format: ExportFormat): Promise<void> {
    const filename = this.generateFilename(format, data.filters);
    switch (format) {
      case 'csv':
        this.toCSV(data, filename);
        break;
      case 'json':
        this.toJSON(data, filename);
        break;
      case 'excel':
        this.toExcel(data, filename);
        break;
      case 'pdf':
        await this.toPDF(data, filename);
        break;
    }
  }

  /** CSV export (RFC 4180 quoting) — timestamps kept as ISO-8601 strings (Req 8.2). */
  toCSV(data: DashboardExportData, filename: string): void {
    const blocks = [data.summary, data.senders, data.sites, data.history].map((s) => {
      const header = s.headers.join(',');
      const rows = s.rows.map((row) => row.map((cell) => this.csvCell(cell)).join(','));
      return [header, ...rows].join('\n');
    });
    this.downloadFile(new Blob([blocks.join('\n\n')], { type: 'text/csv;charset=utf-8' }), filename);
  }

  toJSON(data: DashboardExportData, filename: string): void {
    const payload = {
      exportedAt: new Date().toISOString(),
      summary: this.tableToObjects(data.summary),
      senders: this.tableToObjects(data.senders),
      sites: this.tableToObjects(data.sites),
      history: this.tableToObjects(data.history),
    };
    this.downloadFile(
      new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json;charset=utf-8' }),
      filename,
    );
  }

  /** Excel export with 4 sheets (Requirement 8.3). */
  toExcel(data: DashboardExportData, filename: string): void {
    const wb = XLSX.utils.book_new();
    const appendSheet = (name: string, table: ExportTable) => {
      const aoa = [table.headers, ...table.rows.map((r) => r.map((c) => String(c)))];
      XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(aoa), name);
    };
    appendSheet('Summary', data.summary);
    appendSheet('Senders', data.senders);
    appendSheet('Sites', data.sites);
    appendSheet('History', data.history);
    XLSX.writeFile(wb, filename);
  }

  /**
   * PDF export (Requirement 8.4): captures the KPI grid and chart elements
   * referenced by `pdfElements`, rendering them as images into a paginated PDF.
   */
  async toPDF(data: DashboardExportData, filename: string): Promise<void> {
    const pdf = new jsPDF('p', 'mm', 'a4');
    const pageWidth = pdf.internal.pageSize.getWidth();
    const pageHeight = pdf.internal.pageSize.getHeight();
    const margin = 10;

    // Header block
    pdf.setFontSize(16);
    pdf.text('Dashboard Export', margin, 18);
    pdf.setFontSize(9);
    pdf.setTextColor(120);
    pdf.text(`Generated: ${new Date().toISOString()}`, margin, 24);
    if (data.filters.length > 0) {
      pdf.text(`Filters: ${data.filters.join(', ')}`, margin, 29);
    }
    pdf.setTextColor(0);

    let y = 36;

    for (const selector of data.pdfElements ?? []) {
      const el = document.querySelector(selector) as HTMLElement | null;
      if (!el) continue;

      const canvas = await html2canvas(el, { scale: 2, useCORS: true });
      const img = canvas.toDataURL('image/png');
      // Fit image into page while preserving aspect ratio.
      const imgW = pageWidth - margin * 2;
      const imgH = (canvas.height * imgW) / canvas.width;

      if (y + imgH > pageHeight - margin) {
        pdf.addPage();
        y = margin;
      }
      pdf.addImage(img, 'PNG', margin, y, imgW, imgH);
      y += imgH + 6;
    }

    // Append a summary table page.
    pdf.addPage();
    this.pdfTable(pdf, 'Summary', data.summary, margin);
    pdf.save(filename);
  }

  generateFilename(format: ExportFormat, filters: string[]): string {
    const date = new Date().toISOString().split('T')[0];
    const filterPart = filters.length > 0 ? filters.join('-').replace(/[^a-z0-9-_]/gi, '') : 'all';
    return `dashboard-export-${date}-${filterPart}.${format}`;
  }

  // ── Private helpers ───────────────────────────────────────────

  private tableToObjects(table: ExportTable): Record<string, string | number>[] {
    return table.rows.map((row) => Object.fromEntries(table.headers.map((h, i) => [h, row[i] ?? ''])));
  }

  private csvCell(cell: string | number): string {
    const s = String(cell ?? '');
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  }

  private pdfTable(pdf: jsPDF, title: string, table: ExportTable, margin: number): void {
    pdf.setFontSize(13);
    pdf.text(title, margin, 16);
    pdf.setFontSize(7);
    const pageWidth = pdf.internal.pageSize.getWidth();
    const colW = (pageWidth - margin * 2) / Math.min(table.headers.length, 6);
    const top = 22;
    let y = top;

    pdf.setFont('helvetica', 'bold');
    table.headers.slice(0, 6).forEach((h, i) => pdf.text(h, margin + i * colW, y));
    pdf.setFont('helvetica', 'normal');

    for (const row of table.rows.slice(0, 60)) {
      y += 5;
      if (y > pdf.internal.pageSize.getHeight() - margin) {
        pdf.addPage();
        y = margin + 5;
      }
      row.slice(0, 6).forEach((cell, i) => pdf.text(String(cell ?? ''), margin + i * colW, y));
    }
  }

  private downloadFile(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }
}
