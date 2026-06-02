/**
 * AI Module Type Definitions
 * Includes all AI feature types for the Exensio Reload application
 */

// ==================== Core Types ====================

export interface AiChatRequest {
  message: string;
  context?: Record<string, unknown>;
  conversationId?: string;
}

export interface AiChatResponse {
  reply: string;
  suggestedActions?: SuggestedAction[];
  confidence: number;
  conversationId: string;
  metadata?: Record<string, unknown>;
}

export interface SuggestedAction {
  label: string;
  action: string;
  params?: Record<string, unknown>;
}

export interface AiSummarizeRequest {
  alerts: AlertData[];
  summaryType?: 'alerts' | 'sessions' | 'failures';
}

export interface AlertData {
  sender: string;
  error: string;
  timestamp: string;
  severity?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  lotId?: string;
  waferId?: string;
}

export interface AiSummarizeResponse {
  summary: string;
  groups?: AlertGroup[];
  priority: string;
  totalAlerts: number;
  recommendations?: string[];
}

export interface AlertGroup {
  issue: string;
  count: number;
  senders: string[];
  recommendation: string;
  likelyCause?: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  confidence?: number;
  suggestedActions?: SuggestedAction[];
}

// ==================== AI Service Status ====================

export interface AiStatus {
  enabled: boolean;
  configured: boolean;
  provider: string;
  model: string;
  chatAvailable: boolean;
  searchAvailable: boolean;
  alertTriageAvailable: boolean;
  recommendationAvailable: boolean;
  anomalyAvailable: boolean;
  rootCauseAvailable: boolean;
  dailySummaryAvailable: boolean;
  predictiveAvailable: boolean;
  qualityScoreAvailable: boolean;
  routingAvailable: boolean;
}

// ==================== Natural Language Search ====================

export interface NaturalLanguageSearchRequest {
  query: string;
  sites?: string[];
  limit?: number;
}

export interface NaturalLanguageSearchResponse {
  summary: string;
  sql: string;
  results: SearchResult[];
  totalResults: number;
  searchType: string;
  confidence: number;
  suggestions?: string[];
}

export interface SearchResult {
  lot: string;
  wafer: string;
  senderId: string;
  status: string;
  errorMessage?: string;
  timestamp: string;
}

// ==================== Alert Triage ====================

export interface AlertTriageRequest {
  alerts: AlertData[];
}

export interface AlertTriageResponse {
  triageSummary: string;
  totalAlerts: number;
  byPriority: AlertPriorityCounts;
  byCategory: AlertCategoryCount[];
  recommendedActions: string[];
  overallPriority: string;
  escalationRequired: boolean;
  analysis?: string;
}

export interface AlertPriorityCounts {
  critical: number;
  high: number;
  medium: number;
  low: number;
}

export interface AlertCategoryCount {
  category: string;
  count: number;
  severity: string;
  impact: string;
}

// ==================== Session Recommendations ====================

export interface SessionRecommendationRequest {
  site: string;
  senderId?: string;
  userId?: string;
}

export interface SessionRecommendationResponse {
  recommendations: SessionRecommendation[];
  reason: string;
  confidence: number;
  disclaimer: string;
  recommendationSummary: string;
}

export interface SessionRecommendation {
  type: string;
  current: string;
  recommended: string;
  reason: string;
  estimatedImpact: string;
}

// ==================== Anomaly Detection ====================

export interface AnomalyDetectionRequest {
  site: string;
  timeRange?: string;
  baselinePeriod?: string;
}

export interface AnomalyDetectionResponse {
  anomaliesDetected: boolean;
  totalAnomalies: number;
  anomalies: Anomaly[];
  overallRiskLevel: string;
  recommendations: string[];
  baselineMetrics?: Record<string, number>;
  timestamp: string;
}

export interface Anomaly {
  type: string;
  description: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  affectedEntity: string;
  deviationFromBaseline: number;
  timestamp: string;
  probableCause: string;
}

// ==================== Root Cause Analysis ====================

export interface RootCauseAnalysisRequest {
  errorCode: string;
  errorMessage: string;
  site?: string;
  timeRange?: string;
  failedRecords?: { lot: string; wafer: string; senderId: string }[];
}

export interface RootCauseAnalysisResponse {
  primaryCause: string;
  confidence: string;
  estimatedTimeToResolve: string;
  contributingFactors: string[];
  recommendedActions: string[];
  similarPastIncidents: string[];
  affectedComponents: string[];
  explanation?: string;
  documentationLinks?: string[];
}

// ==================== Daily Summary ====================

export interface DailySummaryRequest {
  date?: string;
  sites?: string[];
}

export interface DailySummaryResponse {
  date: string;
  summary: string;
  totalSessions: number;
  totalRecords: number;
  successRate: number;
  errorRate: number;
  statusBreakdown: Record<string, number>;
  topIssues: TopIssue[];
  trends: TrendItem[];
  highlights: string[];
  recommendations: string[];
  operatorBriefing: string;
}

export interface TopIssue {
  issue: string;
  count: number;
  trend: 'Increasing' | 'Decreasing' | 'Stable';
  impact: string;
}

export interface TrendItem {
  metric: string;
  direction: 'up' | 'down' | 'stable';
  change: number;
  description: string;
}

// ==================== Predictive Failure ====================

export interface PredictiveFailureRequest {
  site: string;
  lotIds?: string[];
  timeWindow?: string;
}

export interface PredictiveFailureResponse {
  predictionsAvailable: boolean;
  predictions: Prediction[];
  riskScores: Record<string, number>;
  riskFactors: string[];
  confidenceLevel: string;
  preventiveActions: string[];
  predictionTimestamp: number;
}

export interface Prediction {
  entityId: string;
  entityType: 'LOT' | 'SESSION' | 'SENDER';
  probability: number;
  predictedOutcome: 'SUCCESS' | 'FAILURE';
  timeframe: string;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  indicators: string[];
  recommendedAction: string;
}

// ==================== Data Quality Score ====================

export interface DataQualityScoreRequest {
  records: Record<string, unknown>[];
  site?: string;
  includeDetails?: boolean;
}

export interface DataQualityScoreResponse {
  overallScore: number;
  grade: string;
  totalRecords: number;
  passedRecords: number;
  failedRecords: number;
  dimensionScores: Record<string, number>;
  issues: QualityIssue[];
  recommendations: string[];
  readyForExensio: boolean;
}

export interface QualityIssue {
  field: string;
  issueType: string;
  affectedCount: number;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  description: string;
  suggestion: string;
}

// ==================== Intelligent Routing ====================

export interface IntelligentRoutingRequest {
  site: string;
  senderId: string;
  recordData?: Record<string, unknown>;
}

export interface IntelligentRoutingResponse {
  recommendedRoute: string;
  confidence: number;
  reason: string;
  targetEndpoint: string;
  alternativeRoutes: string[];
  estimatedProcessingTime: Record<string, string>;
  optimizations: string[];
  autoRouteEnabled: boolean;
}

// ==================== AI Service Status (Extended) ====================

export interface AiStatus {
  enabled: boolean;
  configured: boolean;
  provider: string;
  model: string;
  chatAvailable: boolean;
  searchAvailable: boolean;
  alertTriageAvailable: boolean;
  recommendationAvailable: boolean;
  anomalyAvailable: boolean;
  rootCauseAvailable: boolean;
  dailySummaryAvailable: boolean;
  predictiveAvailable: boolean;
  qualityScoreAvailable: boolean;
  routingAvailable: boolean;
  // New features
  shiftHandoffAvailable?: boolean;
  predictiveMaintenanceAvailable?: boolean;
  crossSiteComparisonAvailable?: boolean;
  trendForecastingAvailable?: boolean;
  autoIncidentReportAvailable?: boolean;
  optimalBatchSizingAvailable?: boolean;
  costAnalysisAvailable?: boolean;
  knowledgeBaseSearchAvailable?: boolean;
  notificationAvailable?: boolean;
  scheduledReportsAvailable?: boolean;
  exportAvailable?: boolean;
  favoriteQueriesAvailable?: boolean;
  voiceCommandsAvailable?: boolean;
}

// ==================== Shift Handoff Summary ====================

export interface ShiftHandoffRequest {
  site: string;
  shift: 'DAY' | 'NIGHT' | 'SWING';
  date?: string;
  outgoingOperator?: string;
  incomingOperator?: string;
}

export interface ShiftHandoffSummary {
  shiftSummary: string;
  handoffNotes: string[];
  openIssues: string[];
  pendingActions: string[];
  equipmentStatus: Record<string, string>;
  personnelNotes: string[];
  criticalItems: string[];
  generatedAt: number;
}

// ==================== Predictive Maintenance ====================

export interface PredictiveMaintenanceRequest {
  site: string;
  equipmentTypes?: string[];
  timeWindow?: string;
}

export interface PredictiveMaintenanceResponse {
  predictions: MaintenancePrediction[];
  recommendations: string[];
  priorityEquipment: string[];
  riskAssessment: string;
  generatedAt: number;
}

export interface MaintenancePrediction {
  equipmentId: string;
  equipmentType: string;
  predictedFailureDate: string;
  confidence: number;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  estimatedDowntime: string;
  recommendedAction: string;
  indicators: string[];
}

// ==================== Cross-Site Comparison ====================

export interface CrossSiteComparisonRequest {
  sites: string[];
  metrics: string[];
  timeRange?: string;
}

export interface CrossSiteComparisonResponse {
  comparison: SiteComparison[];
  overallBestPerformer: string;
  insights: string;
  recommendations: string[];
  benchmarkMetrics: Record<string, number>;
  generatedAt: number;
}

export interface SiteComparison {
  site: string;
  metrics: Record<string, number>;
  performanceScore: number;
  rank: number;
  strengths: string[];
  weaknesses: string[];
}

// ==================== Trend Forecasting ====================

export interface TrendForecastingRequest {
  site: string;
  forecastDays?: number;
  metrics?: string[];
  timeRange?: string;
}

export interface TrendForecastingResponse {
  trends: Trend[];
  forecasts: Forecast[];
  patterns: Pattern[];
  peakPredictions: PeakPrediction[];
  staffingRecommendations: string[];
  insights: string;
  generatedAt: number;
}

export interface Trend {
  metric: string;
  direction: 'UP' | 'DOWN' | 'STABLE';
  changePercent: number;
  averageValue: number;
  confidence: number;
}

export interface Forecast {
  metric: string;
  points: ForecastPoint[];
  confidence: number;
  method: string;
}

export interface ForecastPoint {
  day: number;
  predictedValue: number;
  lowerBound: number;
  upperBound: number;
}

export interface Pattern {
  patternType: string;
  description: string;
  confidence: number;
  implication: string;
}

export interface PeakPrediction {
  timeWindow: string;
  predictedLoad: number;
  confidence: number;
  recommendations: string[];
}

// ==================== Auto Incident Report ====================

export interface AutoIncidentReportRequest {
  incidentId?: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
  startTime: string;
  endTime?: string;
  affectedComponents: string[];
  errorMessages?: string[];
  site?: string;
  includeActionItems?: boolean;
}

export interface AutoIncidentReportResponse {
  incidentId: string;
  reportDate: string;
  severity: string;
  executiveSummary: string;
  incidentTimeline: TimelineEvent[];
  impactAnalysis: Record<string, unknown>;
  rootCauseDescription: string;
  resolutionSteps: ResolutionStep[];
  lessonsLearned: string[];
  actionItems: ActionItem[];
  preventionRecommendations: string[];
  complianceNotes: string;
  fullReportText?: string;
  reportGeneratedAt: number;
}

export interface TimelineEvent {
  time: string;
  event: string;
  type: string;
  details: string;
}

export interface ResolutionStep {
  step: number;
  action: string;
  result: string;
  timeToComplete: string;
}

export interface ActionItem {
  action: string;
  owner: string;
  dueDate: string;
  priority: string;
}

// ==================== Optimal Batch Sizing ====================

export interface OptimalBatchSizingRequest {
  senderId?: string;
  site: string;
  timeRange?: string;
  currentBatchSizes?: number[];
}

export interface OptimalBatchSizingResponse {
  currentAverageBatchSize: number;
  optimalBatchSize: number;
  minRecommendedSize: number;
  maxRecommendedSize: number;
  confidence: number;
  reason: string;
  sizeRecommendations: SizeRecommendation[];
  historicalAnalysis: HistoricalDataPoint[];
  riskFactors: string[];
  expectedImprovements: Record<string, unknown>;
  aiExplanation?: string;
  generatedAt: number;
}

export interface SizeRecommendation {
  batchSize: number;
  label: string;
  description: string;
  expectedSuccessRate: number;
  expectedThroughput: string;
}

export interface HistoricalDataPoint {
  batchSize: number;
  successRate: number;
  avgProcessingTime: number;
  errorRate: number;
  sampleCount: number;
}

// ==================== Cost Analysis ====================

export interface CostAnalysisRequest {
  site: string;
  timeRange?: string;
  startDate?: string;
  endDate?: string;
  includeProjections?: boolean;
}

export interface CostAnalysisResponse {
  totalProcessingCost: number;
  totalErrorCost: number;
  totalRetryCost: number;
  totalLaborCost: number;
  totalEstimatedCost: number;
  costPerLot: number;
  costPerWafer: number;
  costBreakdown: Record<string, unknown>;
  costTrends: CostTrend[];
  majorCostDrivers: string[];
  savingsOpportunities: SavingsOpportunity[];
  insights?: string;
  analysisTimestamp: number;
}

export interface CostTrend {
  day: string;
  amount: number;
  category: string;
}

export interface SavingsOpportunity {
  category: string;
  description: string;
  potentialSavings: number;
  effort: 'LOW' | 'MEDIUM' | 'HIGH';
  recommendation: string;
}

// ==================== Knowledge Base Search ====================

export interface KnowledgeBaseSearchRequest {
  query: string;
  category?: string;
  limit?: number;
}

export interface KnowledgeBaseSearchResponse {
  results: KnowledgeResult[];
  totalResults: number;
  aiSummary?: string;
  relatedTopics: string[];
  searchTimestamp: number;
}

export interface KnowledgeResult {
  id: string;
  title: string;
  category: string;
  relevanceScore: number;
  summary: string;
  content: string;
  relatedActions: string[];
}

// ==================== Notification Integration ====================

export interface NotificationRequest {
  type: 'ALERT' | 'SUMMARY' | 'INCIDENT' | 'RECOMMENDATION' | 'CUSTOM';
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  title: string;
  message: string;
  channels: ('SLACK' | 'TEAMS' | 'EMAIL' | 'SMS' | 'PAGERDUTY')[];
  metadata?: Record<string, string>;
  aiEnhanced?: boolean;
}

export interface NotificationResponse {
  notificationId: string;
  success: boolean;
  errorMessage?: string;
  timestamp: string;
  channelsConfigured: string[];
  channelStatuses?: Record<string, ChannelStatus>;
  messagePreview: string;
  preferencesSaved: boolean;
}

export interface ChannelStatus {
  channel: string;
  status: 'PENDING' | 'DELIVERED' | 'FAILED';
  deliveredAt: string;
  recipients: string[];
}

// ==================== Scheduled Reports ====================

export interface ScheduledReportRequest {
  reportName: string;
  frequency: 'HOURLY' | 'DAILY' | 'WEEKLY' | 'MONTHLY';
  time: string;
  dayOfWeek?: string;
  dayOfMonth?: number;
  channels: string[];
  recipients: string[];
  site?: string;
}

export interface ScheduledReportResponse {
  scheduleId: string;
  success: boolean;
  message: string;
  schedule?: ReportSchedule;
  generatedContent?: string;
  deliveredTo?: string[];
}

export interface ReportSchedule {
  reportId: string;
  reportName: string;
  frequency: string;
  time: string;
  dayOfWeek?: string;
  dayOfMonth?: number;
  enabled: boolean;
  channels: string[];
  recipients: string[];
  lastRun?: string;
  nextRun?: string;
}

// ==================== AI-Enhanced Export ====================

export interface ExportRequest {
  dataType: 'sessions' | 'errors' | 'senders' | 'performance' | 'custom';
  format: 'CSV' | 'EXCEL' | 'JSON';
  site?: string;
  timeRange?: string;
  maxRows?: number;
  includeAiContext?: boolean;
  includeCharts?: boolean;
  customQuery?: string;
}

export interface ExportResponse {
  exportId: string;
  success: boolean;
  errorMessage?: string;
  format: string;
  columns: string[];
  data: unknown[][];
  rowCount: number;
  aiContextSummary?: string;
  aiInsights?: string[];
  chartSuggestions: ChartSuggestion[];
  generatedAt: number;
}

export interface ChartSuggestion {
  chartType: 'LINE' | 'BAR' | 'PIE' | 'HISTOGRAM';
  title: string;
  suggestedColumns: string[];
  description: string;
}

// ==================== Favorite Queries ====================

export interface FavoriteQueryRequest {
  name: string;
  description?: string;
  query: string;
  aiEnhanced?: boolean;
  tags?: string[];
  userId?: string;
}

export interface FavoriteQueryResponse {
  id: string;
  success: boolean;
  message: string;
  favorites?: FavoriteQuery[];
}

export interface FavoriteQuery {
  id: string;
  name: string;
  description: string;
  query: string;
  aiEnhanced: boolean;
  createdAt: string;
  usageCount: number;
  tags: string[];
}

// ==================== Voice Commands ====================

export interface VoiceCommandRequest {
  command: string;
  language?: string;
}

export interface VoiceCommandResponse {
  success: boolean;
  responseMessage: string;
  intent?: CommandIntent;
  entities?: Record<string, string>;
  actionUrl?: string;
  actionConfirmation?: string;
}

export interface CommandIntent {
  intentType: string;
  action: string;
  target: string;
  parameters?: Record<string, string>;
}