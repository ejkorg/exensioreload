import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface ConfigPipelineStage {
  id?: number;
  name: string;
  type: 'CP' | 'PPLOG' | 'EXENSIO';
  timeoutMinutes: number;
  dependsOn: string[];
  executionOrder?: number;
}

export interface ConfigPipeline {
  id?: number;
  pipelineKey: string;
  site: string;
  server: string;
  socketPort: number;
  configName?: string;
  senderId: number;
  rerunPeriodMinutes: number;
  environment: 'PROD' | 'QA';
  enabled: boolean;
  historicalModeEnabled: boolean;
  stages: ConfigPipelineStage[];
  createdAt?: string;
  updatedAt?: string;
  createdBy?: number;
  updatedBy?: number;
}

export interface PipelinePage {
  content: ConfigPipeline[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface ConfigEtlServer {
  id?: number;
  serverKey: string;
  host: string;
  sshPort: number;
  socketPort: number;
  user: string;
  encryptedPassword?: string; // Not returned by API, only sent on create/update
  timeoutMs: number;
  environment: 'PROD' | 'QA';
  enabled: boolean;
  isHistoricalSender: boolean;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: number;
  updatedBy?: number;
}

export interface EtlServerPage {
  content: ConfigEtlServer[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface SenderOption {
  senderId: number;
  port: number;
  name: string;
  source?: string; // 'DATABASE' or 'YAML'
}

export interface ConfigDbConnection {
  id?: number;
  connectionKey: string;
  dbType: 'ORACLE' | 'POSTGRESQL';
  schema: string;
  host: string;
  user: string;
  encryptedPassword?: string; // Not returned by API, only sent on create/update
  connectionTimeoutMs: number;
  maximumPoolSize: number;
  minimumIdle: number;
  downloadUrl?: string;
  environment: 'PROD' | 'QA';
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: number;
  updatedBy?: number;
}

export interface DbConnectionPage {
  content: ConfigDbConnection[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

@Injectable({
  providedIn: 'root',
})
export class ConfigurationService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${environment.apiUrl}/configuration`;

  // Pipeline endpoints
  getPipelines(
    params: {
      page?: number;
      size?: number;
      environment?: string;
      search?: string;
      sortBy?: string;
      sortDir?: string;
    } = {},
  ): Observable<PipelinePage> {
    let httpParams = new HttpParams();
    // Always send environment; default to PROD if not provided
    const env = params.environment && params.environment !== 'ALL' ? params.environment : 'PROD';
    httpParams = httpParams.set('environment', env);

    if (params.search) httpParams = httpParams.set('search', params.search);
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());

    // Build sort parameter in Spring Data format: "field,direction"
    if (params.sortBy) {
      const direction = params.sortDir === 'desc' ? 'desc' : 'asc';
      httpParams = httpParams.set('sort', `${params.sortBy},${direction}`);
    }

    return this.http.get<PipelinePage>(`${this.apiUrl}/pipelines`, {
      params: httpParams,
      withCredentials: true,
    });
  }

  getPipelineByKey(key: string): Observable<ConfigPipeline> {
    return this.http.get<ConfigPipeline>(`${this.apiUrl}/pipelines/${key}`, {
      withCredentials: true,
    });
  }

  createPipeline(pipeline: ConfigPipeline): Observable<ConfigPipeline> {
    return this.http.post<ConfigPipeline>(`${this.apiUrl}/pipelines`, pipeline, {
      withCredentials: true,
    });
  }

  updatePipeline(key: string, pipeline: ConfigPipeline): Observable<ConfigPipeline> {
    return this.http.put<ConfigPipeline>(`${this.apiUrl}/pipelines/${key}`, pipeline, {
      withCredentials: true,
    });
  }

  deletePipeline(key: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/pipelines/${key}`, {
      withCredentials: true,
    });
  }

  // Query endpoints for Step 1 UI
  getSites(environment?: string): Observable<string[]> {
    let httpParams = new HttpParams();
    // Always send environment; default to PROD if not provided
    const env = environment && environment !== 'ALL' ? environment : 'PROD';
    httpParams = httpParams.set('environment', env);

    return this.http.get<string[]>(`${this.apiUrl}/sites`, {
      params: httpParams,
      withCredentials: true,
    });
  }

  getSenders(site: string, environment?: string, historicalMode?: boolean): Observable<SenderOption[]> {
    let httpParams = new HttpParams().set('site', site);

    // Always send environment; default to PROD if not provided
    const env = environment && environment !== 'ALL' ? environment : 'PROD';
    httpParams = httpParams.set('environment', env);

    if (historicalMode !== undefined) {
      httpParams = httpParams.set('historicalMode', historicalMode.toString());
    }

    return this.http.get<SenderOption[]>(`${this.apiUrl}/senders`, {
      params: httpParams,
      withCredentials: true,
    });
  }

  // ETL Server endpoints
  getEtlServers(environment?: string): Observable<ConfigEtlServer[]> {
    let httpParams = new HttpParams();
    // Always send environment; default to PROD if not provided
    const env = environment && environment !== 'ALL' ? environment : 'PROD';
    httpParams = httpParams.set('environment', env);

    return this.http.get<ConfigEtlServer[]>(`${this.apiUrl}/etl-servers`, {
      params: httpParams,
      withCredentials: true,
    });
  }

  getEtlServersPaged(
    params: {
      page?: number;
      size?: number;
      environment?: string;
      search?: string;
      historicalOnly?: boolean;
      sortBy?: string;
      sortDir?: string;
    } = {},
  ): Observable<EtlServerPage> {
    let httpParams = new HttpParams();
    // Always send environment; default to PROD if not provided
    const env = params.environment && params.environment !== 'ALL' ? params.environment : 'PROD';
    httpParams = httpParams.set('environment', env);

    if (params.search) httpParams = httpParams.set('search', params.search);
    if (params.historicalOnly) httpParams = httpParams.set('historicalOnly', params.historicalOnly.toString());
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());

    // Build sort parameter in Spring Data format: "field,direction"
    if (params.sortBy) {
      const direction = params.sortDir === 'desc' ? 'desc' : 'asc';
      httpParams = httpParams.set('sort', `${params.sortBy},${direction}`);
    }

    return this.http.get<EtlServerPage>(`${this.apiUrl}/etl-servers`, {
      params: httpParams,
      withCredentials: true,
    });
  }

  getEtlServerByKey(key: string): Observable<ConfigEtlServer> {
    return this.http.get<ConfigEtlServer>(`${this.apiUrl}/etl-servers/${key}`, {
      withCredentials: true,
    });
  }

  createEtlServer(server: ConfigEtlServer): Observable<ConfigEtlServer> {
    return this.http.post<ConfigEtlServer>(`${this.apiUrl}/etl-servers`, server, {
      withCredentials: true,
    });
  }

  updateEtlServer(key: string, server: ConfigEtlServer): Observable<ConfigEtlServer> {
    return this.http.put<ConfigEtlServer>(`${this.apiUrl}/etl-servers/${key}`, server, {
      withCredentials: true,
    });
  }

  deleteEtlServer(key: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/etl-servers/${key}`, {
      withCredentials: true,
    });
  }

  testEtlServerConnection(server: ConfigEtlServer): Observable<{ success: boolean; message: string }> {
    return this.http.post<{ success: boolean; message: string }>(`${this.apiUrl}/etl-servers/test-connection`, server, {
      withCredentials: true,
    });
  }

  // DB Connection endpoints
  getDbConnections(environment?: string): Observable<ConfigDbConnection[]> {
    let httpParams = new HttpParams();
    // Always send environment; default to PROD if not provided
    const env = environment && environment !== 'ALL' ? environment : 'PROD';
    httpParams = httpParams.set('environment', env);

    return this.http.get<ConfigDbConnection[]>(`${this.apiUrl}/db-connections`, {
      params: httpParams,
      withCredentials: true,
    });
  }

  getDbConnectionsPaged(
    params: {
      page?: number;
      size?: number;
      environment?: string;
      search?: string;
      sortBy?: string;
      sortDir?: string;
    } = {},
  ): Observable<DbConnectionPage> {
    let httpParams = new HttpParams();
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());
    // Always send environment; default to PROD if not provided
    const env = params.environment && params.environment !== 'ALL' ? params.environment : 'PROD';
    httpParams = httpParams.set('environment', env);

    if (params.search) httpParams = httpParams.set('search', params.search);
    // Build sort parameter in Spring Data format: "field,direction"
    if (params.sortBy) {
      const direction = params.sortDir === 'desc' ? 'desc' : 'asc';
      httpParams = httpParams.set('sort', `${params.sortBy},${direction}`);
    }

    return this.http.get<DbConnectionPage>(`${this.apiUrl}/db-connections`, {
      params: httpParams,
      withCredentials: true,
    });
  }

  getDbConnectionByKey(key: string): Observable<ConfigDbConnection> {
    return this.http.get<ConfigDbConnection>(`${this.apiUrl}/db-connections/${key}`, {
      withCredentials: true,
    });
  }

  createDbConnection(connection: ConfigDbConnection): Observable<ConfigDbConnection> {
    return this.http.post<ConfigDbConnection>(`${this.apiUrl}/db-connections`, connection, {
      withCredentials: true,
    });
  }

  updateDbConnection(key: string, connection: ConfigDbConnection): Observable<ConfigDbConnection> {
    return this.http.put<ConfigDbConnection>(`${this.apiUrl}/db-connections/${key}`, connection, {
      withCredentials: true,
    });
  }

  deleteDbConnection(key: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/db-connections/${key}`, {
      withCredentials: true,
    });
  }

  testDbConnection(connection: ConfigDbConnection): Observable<{ success: boolean; message: string }> {
    return this.http.post<{ success: boolean; message: string }>(
      `${this.apiUrl}/db-connections/test-connection`,
      connection,
      {
        withCredentials: true,
      },
    );
  }
}
