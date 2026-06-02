import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, catchError, of } from 'rxjs';
import {
  AiChatRequest,
  AiChatResponse,
  AiSummarizeRequest,
  AiSummarizeResponse,
  AiStatus,
  ChatMessage,
  NaturalLanguageSearchRequest,
  NaturalLanguageSearchResponse,
  AlertTriageRequest,
  AlertTriageResponse,
  SessionRecommendationRequest,
  SessionRecommendationResponse,
  AnomalyDetectionRequest,
  AnomalyDetectionResponse,
  RootCauseAnalysisRequest,
  RootCauseAnalysisResponse,
  DailySummaryRequest,
  DailySummaryResponse,
  PredictiveFailureRequest,
  PredictiveFailureResponse,
  DataQualityScoreRequest,
  DataQualityScoreResponse,
  IntelligentRoutingRequest,
  IntelligentRoutingResponse,
  // New AI feature types
  ShiftHandoffRequest,
  ShiftHandoffSummary,
  PredictiveMaintenanceRequest,
  PredictiveMaintenanceResponse,
  CrossSiteComparisonRequest,
  CrossSiteComparisonResponse,
  TrendForecastingRequest,
  TrendForecastingResponse,
  AutoIncidentReportRequest,
  AutoIncidentReportResponse,
  OptimalBatchSizingRequest,
  OptimalBatchSizingResponse,
  CostAnalysisRequest,
  CostAnalysisResponse,
  KnowledgeBaseSearchRequest,
  KnowledgeBaseSearchResponse,
  NotificationRequest,
  NotificationResponse,
  ScheduledReportRequest,
  ScheduledReportResponse,
  ReportSchedule,
  ExportRequest,
  ExportResponse,
  FavoriteQueryRequest,
  FavoriteQueryResponse,
  FavoriteQuery,
  VoiceCommandRequest,
  VoiceCommandResponse
} from './ai.types';

@Injectable({ providedIn: 'root' })
export class AiService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/ai';
  
  // State management
  isAvailable = signal(false);
  isEnabled = signal(false);
  conversationId = signal<string | null>(null);
  messages = signal<ChatMessage[]>([]);
  
  // Example prompts for quick start
  examplePrompts = [
    'Show me lots that failed in the last 24 hours',
    'What alerts do we have right now?',
    'Help me understand this session status',
    'What sender has the most errors today?'
  ];

  constructor() {
    this.checkStatus();
  }

  // ==================== Status & Health ====================

  /**
   * Check AI service status.
   */
  checkStatus(): Observable<AiStatus> {
    return this.http.get<AiStatus>(`${this.apiUrl}/status`).pipe(
      tap(status => {
        this.isAvailable.set(status.chatAvailable);
        this.isEnabled.set(status.enabled);
      }),
      catchError(() => {
        this.isAvailable.set(false);
        this.isEnabled.set(false);
        return of(this.createDefaultStatus());
      })
    );
  }

  /**
   * Get AI status.
   */
  getStatus(): Observable<AiStatus> {
    return this.http.get<AiStatus>(`${this.apiUrl}/status`);
  }

  // ==================== Chat ====================

  /**
   * Send a chat message and get AI response.
   */
  chat(message: string, context?: Record<string, unknown>): Observable<AiChatResponse> {
    const request: AiChatRequest = {
      message,
      context,
      conversationId: this.conversationId() || undefined
    };

    return this.http.post<AiChatResponse>(`${this.apiUrl}/chat`, request).pipe(
      tap(response => {
        if (response.conversationId && !this.conversationId()) {
          this.conversationId.set(response.conversationId);
        }
        
        this.addMessage({
          id: this.generateId(),
          role: 'assistant',
          content: response.reply,
          timestamp: new Date(),
          confidence: response.confidence,
          suggestedActions: response.suggestedActions
        });
      })
    );
  }

  /**
   * Add a user message to local history.
   */
  addUserMessage(content: string): void {
    this.addMessage({
      id: this.generateId(),
      role: 'user',
      content,
      timestamp: new Date()
    });
  }

  /**
   * Add a message to local history.
   */
  addMessage(message: ChatMessage): void {
    this.messages.update(msgs => [...msgs, message]);
  }

  /**
   * Clear conversation history.
   */
  clearConversation(): void {
    this.messages.set([]);
    if (this.conversationId()) {
      const convId = this.conversationId()!;
      this.http.delete(`${this.apiUrl}/conversation/${convId}`).subscribe();
    }
    this.conversationId.set(null);
  }

  /**
   * Clear messages only (keep conversation ID).
   */
  clearMessages(): void {
    this.messages.set([]);
  }

  // ==================== Summarization ====================

  /**
   * Summarize alerts using AI.
   */
  summarizeAlerts(alerts: AiSummarizeRequest['alerts']): Observable<AiSummarizeResponse> {
    const request: AiSummarizeRequest = {
      alerts,
      summaryType: 'alerts'
    };

    return this.http.post<AiSummarizeResponse>(`${this.apiUrl}/summarize/alerts`, request);
  }

  // ==================== Natural Language Search ====================

  /**
   * Search using natural language.
   */
  search(query: string, sites?: string[], limit?: number): Observable<NaturalLanguageSearchResponse> {
    const request: NaturalLanguageSearchRequest = {
      query,
      sites,
      limit: limit || 100
    };

    return this.http.post<NaturalLanguageSearchResponse>(`${this.apiUrl}/search`, request);
  }

  // ==================== Alert Triage ====================

  /**
   * Perform smart alert triage.
   */
  triageAlerts(alerts: AlertTriageRequest['alerts']): Observable<AlertTriageResponse> {
    const request: AlertTriageRequest = { alerts };
    return this.http.post<AlertTriageResponse>(`${this.apiUrl}/alerts/triage`, request);
  }

  // ==================== Session Recommendations ====================

  /**
   * Get session recommendations.
   */
  getSessionRecommendations(site: string, senderId?: string): Observable<SessionRecommendationResponse> {
    const request: SessionRecommendationRequest = {
      site,
      senderId,
      userId: undefined
    };
    return this.http.post<SessionRecommendationResponse>(`${this.apiUrl}/recommendations/session`, request);
  }

  // ==================== Anomaly Detection ====================

  /**
   * Detect anomalies in staging patterns.
   */
  detectAnomalies(site: string, timeRange?: string): Observable<AnomalyDetectionResponse> {
    const request: AnomalyDetectionRequest = {
      site,
      timeRange: timeRange || '24h',
      baselinePeriod: '7d'
    };
    return this.http.post<AnomalyDetectionResponse>(`${this.apiUrl}/anomaly/detect`, request);
  }

  // ==================== Root Cause Analysis ====================

  /**
   * Perform root cause analysis.
   */
  analyzeRootCause(errorCode: string, errorMessage: string, site?: string): Observable<RootCauseAnalysisResponse> {
    const request: RootCauseAnalysisRequest = {
      errorCode,
      errorMessage,
      site,
      timeRange: '7d'
    };
    return this.http.post<RootCauseAnalysisResponse>(`${this.apiUrl}/analysis/root-cause`, request);
  }

  // ==================== Daily Summary ====================

  /**
   * Get daily summary report.
   */
  getDailySummary(date?: string, sites?: string[]): Observable<DailySummaryResponse> {
    const request: DailySummaryRequest = {
      date: date || new Date().toISOString().split('T')[0],
      sites
    };
    return this.http.post<DailySummaryResponse>(`${this.apiUrl}/summary/daily`, request);
  }

  // ==================== Predictive Failure ====================

  /**
   * Predict potential failures.
   */
  predictFailures(site: string, lotIds?: string[]): Observable<PredictiveFailureResponse> {
    const request: PredictiveFailureRequest = {
      site,
      lotIds,
      timeWindow: '4h'
    };
    return this.http.post<PredictiveFailureResponse>(`${this.apiUrl}/predict/failure`, request);
  }

  // ==================== Data Quality Score ====================

  /**
   * Score data quality.
   */
  scoreDataQuality(records: Record<string, unknown>[], site?: string): Observable<DataQualityScoreResponse> {
    const request: DataQualityScoreRequest = {
      records,
      site,
      includeDetails: true
    };
    return this.http.post<DataQualityScoreResponse>(`${this.apiUrl}/quality/score`, request);
  }

  // ==================== Intelligent Routing ====================

  /**
   * Get optimal routing recommendation.
   */
  getOptimalRoute(site: string, senderId: string, recordData?: Record<string, unknown>): Observable<IntelligentRoutingResponse> {
    const request: IntelligentRoutingRequest = {
      site,
      senderId,
      recordData
    };
    return this.http.post<IntelligentRoutingResponse>(`${this.apiUrl}/routing/optimal`, request);
  }

  // ==================== Shift Handoff Summary ====================

  /**
   * Get shift handoff summary.
   */
  getShiftHandoffSummary(site: string, shift: 'DAY' | 'NIGHT' | 'SWING', date?: string): Observable<ShiftHandoffSummary> {
    const request: ShiftHandoffRequest = { site, shift, date };
    return this.http.post<ShiftHandoffSummary>(`${this.apiUrl}/handoff/summary`, request);
  }

  // ==================== Predictive Maintenance ====================

  /**
   * Get predictive maintenance recommendations.
   */
  getMaintenancePrediction(site: string, timeWindow?: string): Observable<PredictiveMaintenanceResponse> {
    const request: PredictiveMaintenanceRequest = { site, timeWindow: timeWindow || '7d' };
    return this.http.post<PredictiveMaintenanceResponse>(`${this.apiUrl}/maintenance/predict`, request);
  }

  // ==================== Cross-Site Comparison ====================

  /**
   * Compare performance across sites.
   */
  compareSites(sites: string[], metrics: string[]): Observable<CrossSiteComparisonResponse> {
    const request: CrossSiteComparisonRequest = { sites, metrics, timeRange: '30d' };
    return this.http.post<CrossSiteComparisonResponse>(`${this.apiUrl}/comparison/sites`, request);
  }

  // ==================== Trend Forecasting ====================

  /**
   * Get trend forecasts.
   */
  forecastTrends(site: string, forecastDays?: number): Observable<TrendForecastingResponse> {
    const request: TrendForecastingRequest = {
      site,
      forecastDays: forecastDays || 7,
      timeRange: '30d'
    };
    return this.http.post<TrendForecastingResponse>(`${this.apiUrl}/trends/forecast`, request);
  }

  // ==================== Auto Incident Report ====================

  /**
   * Generate incident report.
   */
  generateIncidentReport(request: AutoIncidentReportRequest): Observable<AutoIncidentReportResponse> {
    return this.http.post<AutoIncidentReportResponse>(`${this.apiUrl}/incidents/generate`, request);
  }

  // ==================== Optimal Batch Sizing ====================

  /**
   * Get optimal batch size recommendations.
   */
  getOptimalBatchSize(site: string, senderId?: string): Observable<OptimalBatchSizingResponse> {
    const request: OptimalBatchSizingRequest = { site, senderId, timeRange: '30d' };
    return this.http.post<OptimalBatchSizingResponse>(`${this.apiUrl}/batch/optimal`, request);
  }

  // ==================== Cost Analysis ====================

  /**
   * Analyze operation costs.
   */
  analyzeCosts(site: string, timeRange?: string): Observable<CostAnalysisResponse> {
    const request: CostAnalysisRequest = { site, timeRange: timeRange || '7d' };
    return this.http.post<CostAnalysisResponse>(`${this.apiUrl}/costs/analyze`, request);
  }

  // ==================== Knowledge Base Search ====================

  /**
   * Search knowledge base.
   */
  searchKnowledge(query: string, category?: string): Observable<KnowledgeBaseSearchResponse> {
    const request: KnowledgeBaseSearchRequest = { query, category };
    return this.http.post<KnowledgeBaseSearchResponse>(`${this.apiUrl}/knowledge/search`, request);
  }

  // ==================== Notifications ====================

  /**
   * Send notification.
   */
  sendNotification(request: NotificationRequest): Observable<NotificationResponse> {
    return this.http.post<NotificationResponse>(`${this.apiUrl}/notifications/send`, request);
  }

  /**
   * Configure notification channels.
   */
  configureNotifications(config: Record<string, string>): Observable<NotificationResponse> {
    return this.http.post<NotificationResponse>(`${this.apiUrl}/notifications/configure`, config);
  }

  // ==================== Scheduled Reports ====================

  /**
   * Create scheduled report.
   */
  scheduleReport(request: ScheduledReportRequest): Observable<ScheduledReportResponse> {
    return this.http.post<ScheduledReportResponse>(`${this.apiUrl}/reports/schedule`, request);
  }

  /**
   * Get all scheduled reports.
   */
  getSchedules(): Observable<{ schedules: ReportSchedule[]; total: number }> {
    return this.http.get<{ schedules: ReportSchedule[]; total: number }>(`${this.apiUrl}/reports/schedules`);
  }

  /**
   * Generate report now.
   */
  generateReportNow(scheduleId: string): Observable<ScheduledReportResponse> {
    return this.http.post<ScheduledReportResponse>(`${this.apiUrl}/reports/generate/${scheduleId}`, {});
  }

  // ==================== Export ====================

  /**
   * Export data with AI context.
   */
  exportData(request: ExportRequest): Observable<ExportResponse> {
    return this.http.post<ExportResponse>(`${this.apiUrl}/export`, request);
  }

  // ==================== Favorite Queries ====================

  /**
   * Save query as favorite.
   */
  saveFavorite(request: FavoriteQueryRequest): Observable<FavoriteQueryResponse> {
    return this.http.post<FavoriteQueryResponse>(`${this.apiUrl}/favorites/save`, request);
  }

  /**
   * Get all favorites.
   */
  getFavorites(userId: string): Observable<{ favorites: FavoriteQuery[] }> {
    return this.http.get<{ favorites: FavoriteQuery[] }>(`${this.apiUrl}/favorites/${userId}`);
  }

  /**
   * Delete a favorite.
   */
  deleteFavorite(userId: string, queryId: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.apiUrl}/favorites/${userId}/${queryId}`);
  }

  // ==================== Voice Commands ====================

  /**
   * Process voice command.
   */
  processVoiceCommand(command: string): Observable<VoiceCommandResponse> {
    const request: VoiceCommandRequest = { command };
    return this.http.post<VoiceCommandResponse>(`${this.apiUrl}/voice/command`, request);
  }

  /**
   * Get voice command help.
   */
  getVoiceHelp(): Observable<VoiceCommandResponse> {
    return this.http.get<VoiceCommandResponse>(`${this.apiUrl}/voice/help`);
  }

  // ==================== Helpers ====================

  get messageCount(): number {
    return this.messages().length;
  }

  private generateId(): string {
    return `msg-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
  }

  private createDefaultStatus(): AiStatus {
    return {
      enabled: false,
      configured: false,
      provider: 'unknown',
      model: 'unknown',
      chatAvailable: false,
      searchAvailable: false,
      alertTriageAvailable: false,
      recommendationAvailable: false,
      anomalyAvailable: false,
      rootCauseAvailable: false,
      dailySummaryAvailable: false,
      predictiveAvailable: false,
      qualityScoreAvailable: false,
      routingAvailable: false,
      shiftHandoffAvailable: false,
      predictiveMaintenanceAvailable: false,
      crossSiteComparisonAvailable: false,
      trendForecastingAvailable: false,
      autoIncidentReportAvailable: false,
      optimalBatchSizingAvailable: false,
      costAnalysisAvailable: false,
      knowledgeBaseSearchAvailable: false,
      notificationAvailable: false,
      scheduledReportsAvailable: false,
      exportAvailable: false,
      favoriteQueriesAvailable: false,
      voiceCommandsAvailable: false
    };
  }
}