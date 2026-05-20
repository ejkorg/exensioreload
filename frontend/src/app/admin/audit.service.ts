import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
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

export interface AuditLogPage {
    content: EtlAuditLog[];
    totalElements: number;
    totalPages: number;
    size: number;
    number: number;
}

@Injectable({
    providedIn: 'root'
})
export class AuditService {
    private readonly http = inject(HttpClient);
    private readonly apiUrl = `${environment.apiUrl}/api/etl-trigger/audit`;

    getAuditLogs(params: {
        page?: number;
        size?: number;
        requestId?: string;
        userId?: string;
        status?: string;
        site?: string;
        etlServerName?: string;
    } = {}): Observable<AuditLogPage> {
        let httpParams = new HttpParams();
        if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
        if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());
        if (params.requestId) httpParams = httpParams.set('requestId', params.requestId);
        if (params.userId) httpParams = httpParams.set('userId', params.userId);
        if (params.status) httpParams = httpParams.set('status', params.status);
        if (params.site) httpParams = httpParams.set('site', params.site);
        if (params.etlServerName) httpParams = httpParams.set('etlServerName', params.etlServerName);

        return this.http.get<AuditLogPage>(this.apiUrl, { params: httpParams, withCredentials: true });
    }

    getAuditLogsByRequestId(requestId: string): Observable<EtlAuditLog[]> {
        return this.http.get<EtlAuditLog[]>(`${this.apiUrl}/request-id/${requestId}`, { withCredentials: true });
    }

    getAuditLogsByUserId(userId: string): Observable<EtlAuditLog[]> {
        return this.http.get<EtlAuditLog[]>(`${this.apiUrl}/user-id/${userId}`, { withCredentials: true });
    }

    getAuditLogsByStatus(status: string): Observable<EtlAuditLog[]> {
        return this.http.get<EtlAuditLog[]>(`${this.apiUrl}/status/${status}`, { withCredentials: true });
    }

    getAuditLogsBySite(site: string): Observable<EtlAuditLog[]> {
        return this.http.get<EtlAuditLog[]>(`${this.apiUrl}/site/${site}`, { withCredentials: true });
    }
}
