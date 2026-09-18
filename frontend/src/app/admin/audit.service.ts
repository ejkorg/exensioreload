import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface EtlAuditLog {
  id: number;
  requestId: string;
  userId: string;
  site: string;
  location?: string;
  etlServerName: string;
  senderPort?: number;
  status: 'success' | 'failure' | 'not_configured';
  message?: string;
  timestamp: string;
  remoteIp?: string;
}

export interface AuditLogDto {
  id: number;
  userId?: number;
  action: string;
  resourceType: string;
  resourceId?: string;
  details?: string;
  ipAddress?: string;
  userAgent?: string;
  createdAt: string;
  status?: string;
  errorMessage?: string;
}

export interface AuditLogPage {
  content: EtlAuditLog[] | AuditLogDto[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

@Injectable({
  providedIn: 'root',
})
export class AuditService {
  private readonly http = inject(HttpClient);
  private readonly etlTriggerApiUrl = `${environment.apiUrl}/etl-trigger/audit`;
  private readonly auditLogApiUrl = `${environment.apiUrl}/audit-logs`;

  /**
   * Get configuration audit logs with advanced filtering.
   * This endpoint provides access to configuration management audit logs.
   */
  getConfigAuditLogs(
    params: {
      page?: number;
      size?: number;
      userId?: number;
      action?: string;
      resourceType?: string;
      startDate?: string;
      endDate?: string;
    } = {},
  ): Observable<AuditLogPage> {
    let httpParams = new HttpParams();
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());
    if (params.userId) httpParams = httpParams.set('userId', params.userId.toString());
    if (params.action) httpParams = httpParams.set('action', params.action);
    if (params.resourceType) httpParams = httpParams.set('resourceType', params.resourceType);
    if (params.startDate) httpParams = httpParams.set('startDate', params.startDate);
    if (params.endDate) httpParams = httpParams.set('endDate', params.endDate);

    return this.http.get<AuditLogPage>(this.auditLogApiUrl, { params: httpParams, withCredentials: true });
  }

  /**
   * Get a specific audit log by ID.
   */
  getAuditLogById(id: number): Observable<AuditLogDto> {
    return this.http.get<AuditLogDto>(`${this.auditLogApiUrl}/${id}`, { withCredentials: true });
  }

  /**
   * Export audit logs to CSV.
   */
  exportAuditLogs(
    params: {
      userId?: number;
      action?: string;
      resourceType?: string;
      startDate?: string;
      endDate?: string;
    } = {},
  ): Observable<Blob> {
    let httpParams = new HttpParams();
    if (params.userId) httpParams = httpParams.set('userId', params.userId.toString());
    if (params.action) httpParams = httpParams.set('action', params.action);
    if (params.resourceType) httpParams = httpParams.set('resourceType', params.resourceType);
    if (params.startDate) httpParams = httpParams.set('startDate', params.startDate);
    if (params.endDate) httpParams = httpParams.set('endDate', params.endDate);

    return this.http.get(`${this.auditLogApiUrl}/export`, {
      params: httpParams,
      responseType: 'blob',
      withCredentials: true,
    });
  }

  /**
   * Export ETL trigger audit logs to CSV.
   */
  exportEtlAuditLogs(
    params: {
      requestId?: string;
      userId?: string;
      resourceType?: string;
      status?: string;
      site?: string;
      etlServerName?: string;
      startDate?: string;
      endDate?: string;
    } = {},
  ): Observable<Blob> {
    let httpParams = new HttpParams();
    if (params.requestId) httpParams = httpParams.set('requestId', params.requestId);
    if (params.userId) httpParams = httpParams.set('userId', params.userId);
    if (params.resourceType) httpParams = httpParams.set('resourceType', params.resourceType);
    if (params.status) httpParams = httpParams.set('status', params.status);
    if (params.site) httpParams = httpParams.set('site', params.site);
    if (params.etlServerName) httpParams = httpParams.set('etlServerName', params.etlServerName);
    if (params.startDate) httpParams = httpParams.set('startDate', params.startDate);
    if (params.endDate) httpParams = httpParams.set('endDate', params.endDate);

    return this.http.get(`${this.etlTriggerApiUrl}/export`, {
      params: httpParams,
      responseType: 'blob',
      withCredentials: true,
    });
  }

  // Legacy ETL trigger audit methods
  getAuditLogs(
    params: {
      page?: number;
      size?: number;
      requestId?: string;
      userId?: string;
      resourceType?: string;
      status?: string;
      site?: string;
      etlServerName?: string;
      startDate?: string;
      endDate?: string;
    } = {},
  ): Observable<AuditLogPage> {
    let httpParams = new HttpParams();
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());
    if (params.requestId) httpParams = httpParams.set('requestId', params.requestId);
    if (params.userId) httpParams = httpParams.set('userId', params.userId);
    if (params.resourceType) httpParams = httpParams.set('resourceType', params.resourceType);
    if (params.status) httpParams = httpParams.set('status', params.status);
    if (params.site) httpParams = httpParams.set('site', params.site);
    if (params.etlServerName) httpParams = httpParams.set('etlServerName', params.etlServerName);
    if (params.startDate) httpParams = httpParams.set('startDate', params.startDate);
    if (params.endDate) httpParams = httpParams.set('endDate', params.endDate);

    return this.http.get<AuditLogPage>(this.etlTriggerApiUrl, { params: httpParams, withCredentials: true });
  }

  getAuditLogsByRequestId(requestId: string): Observable<EtlAuditLog[]> {
    return this.http.get<EtlAuditLog[]>(`${this.etlTriggerApiUrl}/request-id/${requestId}`, { withCredentials: true });
  }

  getAuditLogsByUserId(userId: string): Observable<EtlAuditLog[]> {
    return this.http.get<EtlAuditLog[]>(`${this.etlTriggerApiUrl}/user-id/${userId}`, { withCredentials: true });
  }

  getAuditLogsByStatus(status: string): Observable<EtlAuditLog[]> {
    return this.http.get<EtlAuditLog[]>(`${this.etlTriggerApiUrl}/status/${status}`, { withCredentials: true });
  }

  getAuditLogsBySite(site: string): Observable<EtlAuditLog[]> {
    return this.http.get<EtlAuditLog[]>(`${this.etlTriggerApiUrl}/site/${site}`, { withCredentials: true });
  }
}
