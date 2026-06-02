import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiService } from './ai.service';
import { 
  AnomalyDetectionResponse,
  RootCauseAnalysisResponse,
  DailySummaryResponse,
  PredictiveFailureResponse,
  DataQualityScoreResponse,
  AlertTriageResponse,
  SessionRecommendationResponse,
  ShiftHandoffSummary,
  PredictiveMaintenanceResponse,
  CrossSiteComparisonResponse,
  TrendForecastingResponse,
  AutoIncidentReportResponse,
  OptimalBatchSizingResponse,
  CostAnalysisResponse,
  KnowledgeBaseSearchResponse,
  NotificationResponse,
  ScheduledReportResponse,
  ReportSchedule,
  ExportResponse,
  FavoriteQuery
} from './ai.types';

interface FeatureTab {
  id: string;
  label: string;
  icon: string;
  description: string;
}

@Component({
  selector: 'app-ai-features-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="ai-features-panel">
      <!-- Header -->
      <div class="panel-header">
        <h3>AI Features</h3>
        <span class="ai-badge" [class.active]="aiService.isAvailable()">
          {{ aiService.isAvailable() ? 'Active' : 'Inactive' }}
        </span>
      </div>

      <!-- Feature Tabs -->
      <div class="feature-tabs">
        @for (tab of featureTabs; track tab.id) {
          <button 
            class="tab-btn" 
            [class.active]="activeTab() === tab.id"
            (click)="selectTab(tab.id)">
            <span class="tab-icon">{{ tab.icon }}</span>
            <span class="tab-label">{{ tab.label }}</span>
          </button>
        }
      </div>

      <!-- Tab Content -->
      <div class="tab-content">
        @switch (activeTab()) {
          @case ('anomaly') {
            <div class="feature-section">
              <h4>Anomaly Detection</h4>
              <p class="feature-desc">Detect unusual patterns in staging data</p>
              
              <div class="form-group">
                <label>Site</label>
                <select [(ngModel)]="anomalySite" class="form-control">
                  <option value="">All Sites</option>
                  <option value="SLN2">SLN2</option>
                  <option value="SLN3">SLN3</option>
                </select>
              </div>
              
              <button class="btn-primary" (click)="detectAnomalies()" [disabled]="loading()">
                {{ loading() ? 'Analyzing...' : 'Detect Anomalies' }}
              </button>

              @if (anomalyResult()) {
                <div class="result-card" [class.has-anomalies]="anomalyResult()!.anomaliesDetected">
                  <div class="result-header">
                    <span class="risk-badge" [class]="anomalyResult()!.overallRiskLevel?.toLowerCase()">
                      {{ anomalyResult()!.overallRiskLevel }} Risk
                    </span>
                    <span>{{ anomalyResult()!.totalAnomalies }} anomalies found</span>
                  </div>
                  
                  @for (anomaly of anomalyResult()!.anomalies; track $index) {
                    <div class="anomaly-item">
                      <span class="severity" [class]="anomaly.severity?.toLowerCase()">{{ anomaly.severity }}</span>
                      <div class="anomaly-info">
                        <strong>{{ anomaly.type }}</strong>
                        <p>{{ anomaly.description }}</p>
                        <small>{{ anomaly.probableCause }}</small>
                      </div>
                    </div>
                  }
                  
                  @if (anomalyResult()!.recommendations?.length) {
                    <div class="recommendations">
                      <strong>Recommendations:</strong>
                      <ul>
                        @for (rec of anomalyResult()!.recommendations; track $index) {
                          <li>{{ rec }}</li>
                        }
                      </ul>
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('rootcause') {
            <div class="feature-section">
              <h4>Root Cause Analysis</h4>
              <p class="feature-desc">Analyze failures and identify root causes</p>
              
              <div class="form-row">
                <div class="form-group">
                  <label>Error Code</label>
                  <input [(ngModel)]="rcaErrorCode" placeholder="e.g., E001" class="form-control"/>
                </div>
                <div class="form-group">
                  <label>Site</label>
                  <select [(ngModel)]="rcaSite" class="form-control">
                    <option value="">Any</option>
                    <option value="SLN2">SLN2</option>
                    <option value="SLN3">SLN3</option>
                  </select>
                </div>
              </div>
              
              <div class="form-group">
                <label>Error Message</label>
                <textarea [(ngModel)]="rcaErrorMessage" rows="2" placeholder="Describe the error..." class="form-control"></textarea>
              </div>
              
              <button class="btn-primary" (click)="analyzeRootCause()" [disabled]="loading()">
                {{ loading() ? 'Analyzing...' : 'Analyze' }}
              </button>

              @if (rcaResult()) {
                <div class="result-card">
                  <div class="result-header">
                    <strong>{{ rcaResult()!.primaryCause }}</strong>
                    <span class="confidence-badge">{{ rcaResult()!.confidence }} Confidence</span>
                  </div>
                  
                  <p class="estimate">Estimated time to resolve: {{ rcaResult()!.estimatedTimeToResolve }}</p>
                  
                  @if (rcaResult()!.contributingFactors?.length) {
                    <div class="section">
                      <strong>Contributing Factors:</strong>
                      <ul>
                        @for (factor of rcaResult()!.contributingFactors; track $index) {
                          <li>{{ factor }}</li>
                        }
                      </ul>
                    </div>
                  }
                  
                  @if (rcaResult()!.recommendedActions?.length) {
                    <div class="section">
                      <strong>Recommended Actions:</strong>
                      <ul>
                        @for (action of rcaResult()!.recommendedActions; track $index) {
                          <li>{{ action }}</li>
                        }
                      </ul>
                    </div>
                  }
                  
                  @if (rcaResult()!.similarPastIncidents?.length) {
                    <div class="section">
                      <strong>Similar Past Incidents:</strong>
                      <ul class="incidents">
                        @for (incident of rcaResult()!.similarPastIncidents; track $index) {
                          <li>{{ incident }}</li>
                        }
                      </ul>
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('summary') {
            <div class="feature-section">
              <h4>Daily Summary</h4>
              <p class="feature-desc">Get an AI-generated summary of today's operations</p>
              
              <div class="form-group">
                <label>Date</label>
                <input [(ngModel)]="summaryDate" type="date" class="form-control"/>
              </div>
              
              <button class="btn-primary" (click)="getDailySummary()" [disabled]="loading()">
                {{ loading() ? 'Generating...' : 'Generate Summary' }}
              </button>

              @if (summaryResult()) {
                <div class="result-card">
                  <div class="summary-stats">
                    <div class="stat">
                      <span class="stat-value">{{ summaryResult()!.totalSessions }}</span>
                      <span class="stat-label">Sessions</span>
                    </div>
                    <div class="stat">
                      <span class="stat-value">{{ summaryResult()!.totalRecords }}</span>
                      <span class="stat-label">Records</span>
                    </div>
                    <div class="stat">
                      <span class="stat-value">{{ summaryResult()!.successRate }}%</span>
                      <span class="stat-label">Success</span>
                    </div>
                  </div>
                  
                  <div class="summary-text">
                    <strong>Summary:</strong>
                    <p>{{ summaryResult()!.summary }}</p>
                  </div>
                  
                  @if (summaryResult()!.topIssues?.length) {
                    <div class="section">
                      <strong>Top Issues:</strong>
                      @for (issue of summaryResult()!.topIssues; track $index) {
                        <div class="issue-item">
                          <span class="issue-count">{{ issue.count }}</span>
                          <span>{{ issue.issue }}</span>
                          <span class="issue-trend" [class]="issue.trend?.toLowerCase()">{{ issue.trend }}</span>
                        </div>
                      }
                    </div>
                  }
                  
                  @if (summaryResult()!.highlights?.length) {
                    <div class="section highlights">
                      <strong>Highlights:</strong>
                      @for (highlight of summaryResult()!.highlights; track $index) {
                        <p>{{ highlight }}</p>
                      }
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('predict') {
            <div class="feature-section">
              <h4>Predictive Failure Analysis</h4>
              <p class="feature-desc">Predict which lots may fail before processing</p>
              
              <div class="form-group">
                <label>Site</label>
                <select [(ngModel)]="predictSite" class="form-control">
                  <option value="SLN2">SLN2</option>
                  <option value="SLN3">SLN3</option>
                </select>
              </div>
              
              <div class="form-group">
                <label>Lot IDs (comma-separated, optional)</label>
                <input [(ngModel)]="predictLotIds" placeholder="LOT001, LOT002, LOT003" class="form-control"/>
              </div>
              
              <button class="btn-primary" (click)="predictFailures()" [disabled]="loading()">
                {{ loading() ? 'Predicting...' : 'Predict Failures' }}
              </button>

              @if (predictResult()) {
                <div class="result-card">
                  <div class="result-header">
                    <span class="confidence-badge">{{ predictResult()!.confidenceLevel }} Confidence</span>
                    <span>{{ predictResult()!.predictions?.length }} predictions</span>
                  </div>
                  
                  @if (predictResult()!.riskScores) {
                    <div class="risk-scores">
                      <strong>Risk Scores:</strong>
                      @for (entry of objectEntries(predictResult()!.riskScores); track entry[0]) {
                        <div class="risk-item">
                          <span>{{ entry[0] }}:</span>
                          <span class="risk-value" [class.high]="entry[1] > 0.5">{{ (entry[1] * 100).toFixed(0) }}%</span>
                        </div>
                      }
                    </div>
                  }
                  
                  @for (prediction of predictResult()!.predictions; track $index) {
                    <div class="prediction-item">
                      <div class="prediction-header">
                        <strong>{{ prediction.entityId }}</strong>
                        <span class="risk-badge" [class]="prediction.riskLevel?.toLowerCase()">{{ prediction.riskLevel }}</span>
                      </div>
                      <p>Probability: {{ (prediction.probability * 100).toFixed(0) }}%</p>
                      <small>{{ prediction.recommendedAction }}</small>
                    </div>
                  }
                  
                  @if (predictResult()!.preventiveActions?.length) {
                    <div class="recommendations">
                      <strong>Preventive Actions:</strong>
                      <ul>
                        @for (action of predictResult()!.preventiveActions; track $index) {
                          <li>{{ action }}</li>
                        }
                      </ul>
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('quality') {
            <div class="feature-section">
              <h4>Data Quality Score</h4>
              <p class="feature-desc">Validate data quality before Exensio loading</p>
              
              <div class="form-group">
                <label>Site</label>
                <select [(ngModel)]="qualitySite" class="form-control">
                  <option value="SLN2">SLN2</option>
                  <option value="SLN3">SLN3</option>
                </select>
              </div>
              
              <div class="info-box">
                <p>This will analyze sample records from the staging database for quality metrics including completeness, validity, consistency, and accuracy.</p>
              </div>
              
              <button class="btn-primary" (click)="scoreDataQuality()" [disabled]="loading()">
                {{ loading() ? 'Scoring...' : 'Score Data Quality' }}
              </button>

              @if (qualityResult()) {
                <div class="result-card">
                  <div class="quality-header">
                    <div class="grade-badge" [class]="qualityResult()!.grade?.toLowerCase()">
                      {{ qualityResult()!.grade }}
                    </div>
                    <div class="score-info">
                      <span class="score-value">{{ (qualityResult()!.overallScore * 100).toFixed(0) }}%</span>
                      <span class="ready-status" [class]="qualityResult()!.readyForExensio ? 'ready' : 'not-ready'">
                        {{ qualityResult()!.readyForExensio ? 'Ready for Exensio' : 'Needs Attention' }}
                      </span>
                    </div>
                  </div>
                  
                  <div class="quality-stats">
                    <div class="stat">
                      <span class="stat-value">{{ qualityResult()!.totalRecords }}</span>
                      <span class="stat-label">Total</span>
                    </div>
                    <div class="stat passed">
                      <span class="stat-value">{{ qualityResult()!.passedRecords }}</span>
                      <span class="stat-label">Passed</span>
                    </div>
                    <div class="stat failed">
                      <span class="stat-value">{{ qualityResult()!.failedRecords }}</span>
                      <span class="stat-label">Failed</span>
                    </div>
                  </div>
                  
                  @if (qualityResult()!.dimensionScores) {
                    <div class="dimension-scores">
                      <strong>Dimension Scores:</strong>
                      @for (entry of objectEntries(qualityResult()!.dimensionScores); track entry[0]) {
                        <div class="dimension-item">
                          <span>{{ entry[0] }}</span>
                          <div class="score-bar">
                            <div class="score-fill" [style.width.%]="entry[1] * 100"></div>
                          </div>
                          <span>{{ (entry[1] * 100).toFixed(0) }}%</span>
                        </div>
                      }
                    </div>
                  }
                  
                  @if (qualityResult()!.issues?.length) {
                    <div class="section issues">
                      <strong>Issues Found:</strong>
                      @for (issue of qualityResult()!.issues; track $index) {
                        <div class="issue-item">
                          <span class="severity" [class]="issue.severity?.toLowerCase()">{{ issue.severity }}</span>
                          <div>
                            <strong>{{ issue.field }}</strong>
                            <p>{{ issue.description }}</p>
                            <small>{{ issue.suggestion }}</small>
                          </div>
                        </div>
                      }
                    </div>
                  }
                  
                  @if (qualityResult()!.recommendations?.length) {
                    <div class="recommendations">
                      <strong>Recommendations:</strong>
                      <ul>
                        @for (rec of qualityResult()!.recommendations; track $index) {
                          <li>{{ rec }}</li>
                        }
                      </ul>
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('triage') {
            <div class="feature-section">
              <h4>Alert Triage</h4>
              <p class="feature-desc">Analyze and prioritize active alerts</p>
              
              <div class="info-box">
                <p>Click below to analyze current active alerts and get prioritization recommendations.</p>
              </div>
              
              <button class="btn-primary" (click)="triageAlerts()" [disabled]="loading()">
                {{ loading() ? 'Analyzing...' : 'Triage Alerts' }}
              </button>

              @if (triageResult()) {
                <div class="result-card">
                  <div class="result-header">
                    <span class="priority-badge" [class]="triageResult()!.overallPriority?.toLowerCase()">
                      {{ triageResult()!.overallPriority }} Priority
                    </span>
                    <span>{{ triageResult()!.totalAlerts }} alerts</span>
                  </div>
                  
                  <p class="triage-summary">{{ triageResult()!.triageSummary }}</p>
                  
                  <div class="priority-breakdown">
                    <strong>Priority Breakdown:</strong>
                    <div class="priority-bars">
                      @if (triageResult()!.byPriority) {
                        <div class="priority-item">
                          <span>Critical</span>
                          <div class="bar-container">
                            <div class="bar critical" [style.width.%]="(triageResult()!.byPriority.critical / triageResult()!.totalAlerts) * 100"></div>
                          </div>
                          <span>{{ triageResult()!.byPriority.critical }}</span>
                        </div>
                        <div class="priority-item">
                          <span>High</span>
                          <div class="bar-container">
                            <div class="bar high" [style.width.%]="(triageResult()!.byPriority.high / triageResult()!.totalAlerts) * 100"></div>
                          </div>
                          <span>{{ triageResult()!.byPriority.high }}</span>
                        </div>
                        <div class="priority-item">
                          <span>Medium</span>
                          <div class="bar-container">
                            <div class="bar medium" [style.width.%]="(triageResult()!.byPriority.medium / triageResult()!.totalAlerts) * 100"></div>
                          </div>
                          <span>{{ triageResult()!.byPriority.medium }}</span>
                        </div>
                        <div class="priority-item">
                          <span>Low</span>
                          <div class="bar-container">
                            <div class="bar low" [style.width.%]="(triageResult()!.byPriority.low / triageResult()!.totalAlerts) * 100"></div>
                          </div>
                          <span>{{ triageResult()!.byPriority.low }}</span>
                        </div>
                      }
                    </div>
                  </div>
                  
                  @if (triageResult()!.byCategory?.length) {
                    <div class="section">
                      <strong>By Category:</strong>
                      @for (cat of triageResult()!.byCategory; track $index) {
                        <div class="category-item">
                          <strong>{{ cat.category }}</strong>
                          <span>{{ cat.count }} ({{ cat.severity }})</span>
                          <p>{{ cat.impact }}</p>
                        </div>
                      }
                    </div>
                  }
                  
                  @if (triageResult()!.recommendedActions?.length) {
                    <div class="recommendations">
                      <strong>Recommended Actions:</strong>
                      <ul>
                        @for (action of triageResult()!.recommendedActions; track $index) {
                          <li>{{ action }}</li>
                        }
                      </ul>
                    </div>
                  }
                  
                  @if (triageResult()!.escalationRequired) {
                    <div class="escalation-warning">
                      Escalation Required - Please notify supervisor
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('recommend') {
            <div class="feature-section">
              <h4>Session Recommendations</h4>
              <p class="feature-desc">Get AI-powered configuration recommendations</p>
              
              <div class="form-group">
                <label>Site</label>
                <select [(ngModel)]="recommendSite" class="form-control">
                  <option value="SLN2">SLN2</option>
                  <option value="SLN3">SLN3</option>
                </select>
              </div>
              
              <div class="form-group">
                <label>Sender (optional)</label>
                <input [(ngModel)]="recommendSender" placeholder="Sender ID" class="form-control"/>
              </div>
              
              <button class="btn-primary" (click)="getRecommendations()" [disabled]="loading()">
                {{ loading() ? 'Getting...' : 'Get Recommendations' }}
              </button>

              @if (recommendResult()) {
                <div class="result-card">
                  <p class="recommendation-reason">{{ recommendResult()!.reason }}</p>
                  <p class="confidence">Confidence: {{ (recommendResult()!.confidence * 100).toFixed(0) }}%</p>
                  
                  @for (rec of recommendResult()!.recommendations; track $index) {
                    <div class="recommendation-item">
                      <div class="rec-header">
                        <span class="rec-type">{{ rec.type }}</span>
                      </div>
                      <div class="rec-values">
                        <span class="current">Current: {{ rec.current }}</span>
                        <span class="arrow">→</span>
                        <span class="recommended">Recommended: {{ rec.recommended }}</span>
                      </div>
                      <p class="rec-reason">{{ rec.reason }}</p>
                      <small class="rec-impact">Impact: {{ rec.estimatedImpact }}</small>
                    </div>
                  }
                  
                  @if (recommendResult()!.disclaimer) {
                    <p class="disclaimer">{{ recommendResult()!.disclaimer }}</p>
                  }
                </div>
              }
            </div>
          }

          @case ('handoff') {
            <div class="feature-section">
              <h4>Shift Handoff Summary</h4>
              <p class="feature-desc">Generate AI-powered shift handoff notes</p>
              
              <div class="form-row">
                <div class="form-group">
                  <label>Site</label>
                  <select [(ngModel)]="handoffSite" class="form-control">
                    <option value="SLN2">SLN2</option>
                    <option value="SLN3">SLN3</option>
                  </select>
                </div>
                <div class="form-group">
                  <label>Shift</label>
                  <select [(ngModel)]="handoffShift" class="form-control">
                    <option value="DAY">Day Shift</option>
                    <option value="NIGHT">Night Shift</option>
                    <option value="SWING">Swing Shift</option>
                  </select>
                </div>
              </div>
              
              <button class="btn-primary" (click)="getShiftHandoff()" [disabled]="loading()">
                {{ loading() ? 'Generating...' : 'Generate Handoff' }}
              </button>

              @if (handoffResult()) {
                <div class="result-card">
                  <p class="summary-text">{{ handoffResult()!.shiftSummary }}</p>
                  
                  @if (handoffResult()!.handoffNotes?.length) {
                    <div class="section">
                      <strong>Handoff Notes:</strong>
                      <ul>
                        @for (note of handoffResult()!.handoffNotes; track $index) {
                          <li>{{ note }}</li>
                        }
                      </ul>
                    </div>
                  }
                  
                  @if (handoffResult()!.openIssues?.length) {
                    <div class="section issues">
                      <strong>Open Issues:</strong>
                      @for (issue of handoffResult()!.openIssues; track $index) {
                        <li>{{ issue }}</li>
                      }
                    </div>
                  }
                  
                  @if (handoffResult()!.criticalItems?.length) {
                    <div class="section highlights">
                      <strong>Critical Items:</strong>
                      @for (item of handoffResult()!.criticalItems; track $index) {
                        <p>{{ item }}</p>
                      }
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('maintenance') {
            <div class="feature-section">
              <h4>Predictive Maintenance</h4>
              <p class="feature-desc">Predict equipment maintenance needs</p>
              
              <div class="form-group">
                <label>Site</label>
                <select [(ngModel)]="maintenanceSite" class="form-control">
                  <option value="SLN2">SLN2</option>
                  <option value="SLN3">SLN3</option>
                </select>
              </div>
              
              <button class="btn-primary" (click)="getMaintenancePrediction()" [disabled]="loading()">
                {{ loading() ? 'Analyzing...' : 'Predict Maintenance' }}
              </button>

              @if (maintenanceResult()) {
                <div class="result-card">
                  <p class="risk-assessment">{{ maintenanceResult()!.riskAssessment }}</p>
                  
                  @for (pred of maintenanceResult()!.predictions; track $index) {
                    <div class="prediction-item">
                      <div class="prediction-header">
                        <strong>{{ pred.equipmentId }}</strong>
                        <span class="risk-badge" [class]="pred.riskLevel?.toLowerCase()">{{ pred.riskLevel }}</span>
                      </div>
                      <p>Predicted failure: {{ pred.predictedFailureDate }}</p>
                      <small>{{ pred.recommendedAction }}</small>
                    </div>
                  }
                  
                  @if (maintenanceResult()!.recommendations?.length) {
                    <div class="recommendations">
                      <strong>Recommendations:</strong>
                      <ul>
                        @for (rec of maintenanceResult()!.recommendations; track $index) {
                          <li>{{ rec }}</li>
                        }
                      </ul>
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('crosssite') {
            <div class="feature-section">
              <h4>Cross-Site Comparison</h4>
              <p class="feature-desc">Compare performance across sites</p>
              
              <div class="form-group">
                <label>Sites (comma-separated)</label>
                <input [(ngModel)]="crosssiteSites" class="form-control" placeholder="SLN2, SLN3"/>
              </div>
              
              <button class="btn-primary" (click)="compareSites()" [disabled]="loading()">
                {{ loading() ? 'Comparing...' : 'Compare Sites' }}
              </button>

              @if (crosssiteResult()) {
                <div class="result-card">
                  <p class="insights">{{ crosssiteResult()!.insights }}</p>
                  
                  @for (comp of crosssiteResult()!.comparison; track $index) {
                    <div class="comparison-item">
                      <div class="comp-header">
                        <strong>{{ comp.site }}</strong>
                        <span class="rank-badge">Rank #{{ comp.rank }}</span>
                      </div>
                      <div class="score-bar">
                        <div class="score-fill" [style.width.%]="comp.performanceScore"></div>
                      </div>
                      <span>Score: {{ comp.performanceScore.toFixed(0) }}%</span>
                      
                      @if (comp.strengths?.length) {
                        <div class="section highlights">
                          <small>Strengths: {{ comp.strengths.join(', ') }}</small>
                        </div>
                      }
                    </div>
                  }
                  
                  @if (crosssiteResult()!.recommendations?.length) {
                    <div class="recommendations">
                      <strong>Recommendations:</strong>
                      <ul>
                        @for (rec of crosssiteResult()!.recommendations; track $index) {
                          <li>{{ rec }}</li>
                        }
                      </ul>
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('trends') {
            <div class="feature-section">
              <h4>Trend Forecasting</h4>
              <p class="feature-desc">Forecast trends and identify patterns</p>
              
              <div class="form-row">
                <div class="form-group">
                  <label>Site</label>
                  <select [(ngModel)]="trendsSite" class="form-control">
                    <option value="SLN2">SLN2</option>
                    <option value="SLN3">SLN3</option>
                  </select>
                </div>
                <div class="form-group">
                  <label>Forecast Days</label>
                  <input [(ngModel)]="trendsDays" type="number" min="1" max="30" class="form-control"/>
                </div>
              </div>
              
              <button class="btn-primary" (click)="forecastTrends()" [disabled]="loading()">
                {{ loading() ? 'Forecasting...' : 'Forecast Trends' }}
              </button>

              @if (trendsResult()) {
                <div class="result-card">
                  @if (trendsResult()!.insights) {
                    <p class="summary-text">{{ trendsResult()!.insights }}</p>
                  }
                  
                  @for (trend of trendsResult()!.trends; track $index) {
                    <div class="trend-item">
                      <strong>{{ trend.metric }}</strong>
                      <span class="direction-badge" [class]="trend.direction?.toLowerCase()">{{ trend.direction }}</span>
                      <span>{{ trend.changePercent.toFixed(1) }}% change</span>
                      <span class="confidence">{{ (trend.confidence * 100).toFixed(0) }}% confidence</span>
                    </div>
                  }
                  
                  @if (trendsResult()!.patterns?.length) {
                    <div class="section">
                      <strong>Patterns Detected:</strong>
                      @for (pattern of trendsResult()!.patterns; track $index) {
                        <div class="pattern-item">
                          <span class="pattern-type">{{ pattern.patternType }}</span>
                          <p>{{ pattern.description }}</p>
                        </div>
                      }
                    </div>
                  }
                  
                  @if (trendsResult()!.staffingRecommendations?.length) {
                    <div class="recommendations">
                      <strong>Staffing Recommendations:</strong>
                      <ul>
                        @for (rec of trendsResult()!.staffingRecommendations; track $index) {
                          <li>{{ rec }}</li>
                        }
                      </ul>
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('incident') {
            <div class="feature-section">
              <h4>Auto Incident Report</h4>
              <p class="feature-desc">Generate formal incident reports automatically</p>
              
              <div class="form-row">
                <div class="form-group">
                  <label>Severity</label>
                  <select [(ngModel)]="incidentSeverity" class="form-control">
                    <option value="CRITICAL">CRITICAL</option>
                    <option value="HIGH">HIGH</option>
                    <option value="MEDIUM">MEDIUM</option>
                    <option value="LOW">LOW</option>
                  </select>
                </div>
                <div class="form-group">
                  <label>Start Time</label>
                  <input [(ngModel)]="incidentStartTime" type="datetime-local" class="form-control"/>
                </div>
              </div>
              
              <div class="form-group">
                <label>Affected Components (comma-separated)</label>
                <input [(ngModel)]="incidentComponents" placeholder="Sender A, Database" class="form-control"/>
              </div>
              
              <button class="btn-primary" (click)="generateIncidentReport()" [disabled]="loading()">
                {{ loading() ? 'Generating...' : 'Generate Report' }}
              </button>

              @if (incidentResult()) {
                <div class="result-card">
                  <div class="result-header">
                    <strong>{{ incidentResult()!.incidentId }}</strong>
                    <span class="severity" [class]="incidentResult()!.severity?.toLowerCase()">{{ incidentResult()!.severity }}</span>
                  </div>
                  
                  <p>{{ incidentResult()!.executiveSummary }}</p>
                  
                  <div class="section">
                    <strong>Root Cause:</strong>
                    <p>{{ incidentResult()!.rootCauseDescription }}</p>
                  </div>
                  
                  @if (incidentResult()!.resolutionSteps?.length) {
                    <div class="section">
                      <strong>Resolution Steps:</strong>
                      <ol>
                        @for (step of incidentResult()!.resolutionSteps; track $index) {
                          <li>{{ step.action }} - {{ step.result }}</li>
                        }
                      </ol>
                    </div>
                  }
                  
                  @if (incidentResult()!.actionItems?.length) {
                    <div class="section">
                      <strong>Action Items:</strong>
                      <ul>
                        @for (item of incidentResult()!.actionItems; track $index) {
                          <li><strong>{{ item.action }}</strong> - {{ item.owner }} ({{ item.dueDate }})</li>
                        }
                      </ul>
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('batch') {
            <div class="feature-section">
              <h4>Optimal Batch Sizing</h4>
              <p class="feature-desc">Find the optimal batch size for maximum efficiency</p>
              
              <div class="form-group">
                <label>Site</label>
                <select [(ngModel)]="batchSite" class="form-control">
                  <option value="SLN2">SLN2</option>
                  <option value="SLN3">SLN3</option>
                </select>
              </div>
              
              <button class="btn-primary" (click)="getOptimalBatchSize()" [disabled]="loading()">
                {{ loading() ? 'Analyzing...' : 'Get Optimal Size' }}
              </button>

              @if (batchResult()) {
                <div class="result-card">
                  <div class="batch-recommendation">
                    <div class="optimal-size">
                      <span class="size-label">Optimal Batch Size</span>
                      <span class="size-value">{{ batchResult()!.optimalBatchSize }}</span>
                    </div>
                    <div class="range-info">
                      <span>Range: {{ batchResult()!.minRecommendedSize }} - {{ batchResult()!.maxRecommendedSize }}</span>
                      <span>Current avg: {{ batchResult()!.currentAverageBatchSize }}</span>
                    </div>
                  </div>
                  
                  <p class="confidence">Confidence: {{ (batchResult()!.confidence * 100).toFixed(0) }}%</p>
                  
                  @for (rec of batchResult()!.sizeRecommendations; track $index) {
                    <div class="recommendation-item">
                      <span class="rec-type">{{ rec.label }}</span>
                      <span class="recommended">{{ rec.batchSize }} lots</span>
                      <p>{{ rec.description }}</p>
                      <small>Success rate: {{ (rec.expectedSuccessRate * 100).toFixed(0) }}%</small>
                    </div>
                  }
                  
                  @if (batchResult()!.riskFactors?.length) {
                    <div class="section issues">
                      <strong>Risk Factors:</strong>
                      @for (risk of batchResult()!.riskFactors; track $index) {
                        <li>{{ risk }}</li>
                      }
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('cost') {
            <div class="feature-section">
              <h4>Cost Analysis</h4>
              <p class="feature-desc">Analyze operational costs and find savings</p>
              
              <div class="form-row">
                <div class="form-group">
                  <label>Site</label>
                  <select [(ngModel)]="costSite" class="form-control">
                    <option value="SLN2">SLN2</option>
                    <option value="SLN3">SLN3</option>
                  </select>
                </div>
                <div class="form-group">
                  <label>Time Range</label>
                  <select [(ngModel)]="costTimeRange" class="form-control">
                    <option value="1d">Today</option>
                    <option value="7d">Last 7 days</option>
                    <option value="30d">Last 30 days</option>
                  </select>
                </div>
              </div>
              
              <button class="btn-primary" (click)="analyzeCosts()" [disabled]="loading()">
                {{ loading() ? 'Analyzing...' : 'Analyze Costs' }}
              </button>

              @if (costResult()) {
                <div class="result-card">
                  <div class="cost-summary">
                    <div class="cost-total">
                      <span class="cost-label">Total Estimated Cost</span>
                      <span class="cost-value">\${{ costResult()!.totalEstimatedCost.toFixed(2) }}</span>
                    </div>
                    <div class="cost-breakdown">
                      <div class="cost-item">Processing: \${{ costResult()!.totalProcessingCost.toFixed(2) }}</div>
                      <div class="cost-item">Errors: \${{ costResult()!.totalErrorCost.toFixed(2) }}</div>
                      <div class="cost-item">Retries: \${{ costResult()!.totalRetryCost.toFixed(2) }}</div>
                      <div class="cost-item">Labor: \${{ costResult()!.totalLaborCost.toFixed(2) }}</div>
                    </div>
                  </div>
                  
                  @if (costResult()!.majorCostDrivers?.length) {
                    <div class="section">
                      <strong>Major Cost Drivers:</strong>
                      <ul>
                        @for (driver of costResult()!.majorCostDrivers; track $index) {
                          <li>{{ driver }}</li>
                        }
                      </ul>
                    </div>
                  }
                  
                  @if (costResult()!.savingsOpportunities?.length) {
                    <div class="section highlights">
                      <strong>Savings Opportunities:</strong>
                      @for (opp of costResult()!.savingsOpportunities; track $index) {
                        <div class="savings-item">
                          <strong>{{ opp.category }}</strong>
                          <span class="savings-value">Save \${{ opp.potentialSavings.toFixed(2) }}</span>
                          <small>{{ opp.recommendation }}</small>
                        </div>
                      }
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('knowledge') {
            <div class="feature-section">
              <h4>Knowledge Base Search</h4>
              <p class="feature-desc">Search for help and documentation</p>
              
              <div class="form-group">
                <label>Search Query</label>
                <input [(ngModel)]="kbQuery" placeholder="Search for help..." class="form-control"/>
              </div>
              
              <div class="form-group">
                <label>Category (optional)</label>
                <select [(ngModel)]="kbCategory" class="form-control">
                  <option value="">All Categories</option>
                  <option value="Network">Network</option>
                  <option value="Security">Security</option>
                  <option value="Integration">Integration</option>
                  <option value="Reference">Reference</option>
                  <option value="Operations">Operations</option>
                  <option value="Performance">Performance</option>
                  <option value="Data Quality">Data Quality</option>
                </select>
              </div>
              
              <button class="btn-primary" (click)="searchKnowledge()" [disabled]="loading()">
                {{ loading() ? 'Searching...' : 'Search' }}
              </button>

              @if (kbResult()) {
                <div class="result-card">
                  @if (kbResult()!.aiSummary) {
                    <p class="ai-summary">{{ kbResult()!.aiSummary }}</p>
                  }
                  
                  <div class="results-count">{{ kbResult()!.totalResults }} results found</div>
                  
                  @for (result of kbResult()!.results; track $index) {
                    <div class="kb-result">
                      <div class="kb-header">
                        <strong>{{ result.title }}</strong>
                        <span class="kb-category">{{ result.category }}</span>
                      </div>
                      <p>{{ result.summary }}</p>
                      <small>Relevance: {{ (result.relevanceScore * 10).toFixed(0) }}%</small>
                    </div>
                  }
                </div>
              }
            </div>
          }

          @case ('notify') {
            <div class="feature-section">
              <h4>Notifications</h4>
              <p class="feature-desc">Send alerts to Slack, Teams, or Email</p>
              
              <div class="form-row">
                <div class="form-group">
                  <label>Type</label>
                  <select [(ngModel)]="notifyType" class="form-control">
                    <option value="ALERT">Alert</option>
                    <option value="SUMMARY">Summary</option>
                    <option value="INCIDENT">Incident</option>
                    <option value="RECOMMENDATION">Recommendation</option>
                    <option value="CUSTOM">Custom</option>
                  </select>
                </div>
                <div class="form-group">
                  <label>Severity</label>
                  <select [(ngModel)]="notifySeverity" class="form-control">
                    <option value="INFO">Info</option>
                    <option value="WARNING">Warning</option>
                    <option value="CRITICAL">Critical</option>
                  </select>
                </div>
              </div>
              
              <div class="form-group">
                <label>Title</label>
                <input [(ngModel)]="notifyTitle" placeholder="Notification title" class="form-control"/>
              </div>
              
              <div class="form-group">
                <label>Message</label>
                <textarea [(ngModel)]="notifyMessage" rows="3" placeholder="Notification message..." class="form-control"></textarea>
              </div>
              
              <div class="form-group">
                <label>Channels</label>
                <div class="checkbox-group">
                  <label><input type="checkbox" [(ngModel)]="notifyChannels" value="SLACK"/> Slack</label>
                  <label><input type="checkbox" [(ngModel)]="notifyChannels" value="TEAMS"/> Teams</label>
                  <label><input type="checkbox" [(ngModel)]="notifyChannels" value="EMAIL"/> Email</label>
                </div>
              </div>
              
              <button class="btn-primary" (click)="sendNotification()" [disabled]="loading()">
                {{ loading() ? 'Sending...' : 'Send Notification' }}
              </button>

              @if (notifyResult()) {
                <div class="result-card">
                  <div class="result-header">
                    <strong>{{ notifyResult()!.notificationId }}</strong>
                    <span class="status-badge" [class]="notifyResult()!.success ? 'success' : 'error'">
                      {{ notifyResult()!.success ? 'Sent' : 'Failed' }}
                    </span>
                  </div>
                  <p>{{ notifyResult()!.messagePreview }}</p>
                  <small>Channels: {{ notifyResult()!.channelsConfigured?.join(', ') }}</small>
                </div>
              }
            </div>
          }

          @case ('schedules') {
            <div class="feature-section">
              <h4>Scheduled Reports</h4>
              <p class="feature-desc">Manage automated report schedules</p>
              
              <button class="btn-secondary" (click)="loadSchedules()">Refresh Schedules</button>

              @if (schedules().length) {
                <div class="result-card">
                  @for (schedule of schedules(); track $index) {
                    <div class="schedule-item">
                      <div class="schedule-header">
                        <strong>{{ schedule.reportName }}</strong>
                        <span class="status-badge" [class]="schedule.enabled ? 'active' : 'inactive'">
                          {{ schedule.enabled ? 'Active' : 'Inactive' }}
                        </span>
                      </div>
                      <p>{{ schedule.frequency }} at {{ schedule.time }}</p>
                      <small>Next run: {{ schedule.nextRun }}</small>
                      <button class="btn-small" (click)="aiService.generateReportNow(schedule.reportId).subscribe()">
                        Run Now
                      </button>
                    </div>
                  }
                </div>
              } @else {
                <div class="info-box">
                  <p>No scheduled reports configured. Contact admin to set up automated reports.</p>
                </div>
              }
            </div>
          }

          @case ('export') {
            <div class="feature-section">
              <h4>AI-Enhanced Export</h4>
              <p class="feature-desc">Export data with AI-generated insights and charts</p>
              
              <div class="form-row">
                <div class="form-group">
                  <label>Data Type</label>
                  <select [(ngModel)]="exportDataType" class="form-control">
                    <option value="sessions">Sessions</option>
                    <option value="errors">Errors</option>
                    <option value="senders">Senders</option>
                    <option value="performance">Performance</option>
                  </select>
                </div>
                <div class="form-group">
                  <label>Format</label>
                  <select [(ngModel)]="exportFormat" class="form-control">
                    <option value="CSV">CSV</option>
                    <option value="EXCEL">Excel</option>
                    <option value="JSON">JSON</option>
                  </select>
                </div>
              </div>
              
              <button class="btn-primary" (click)="exportData()" [disabled]="loading()">
                {{ loading() ? 'Exporting...' : 'Export Data' }}
              </button>

              @if (exportResult()) {
                <div class="result-card">
                  <div class="result-header">
                    <strong>{{ exportResult()!.exportId }}</strong>
                    <span>{{ exportResult()!.rowCount }} rows</span>
                  </div>
                  
                  @if (exportResult()!.aiContextSummary) {
                    <p class="ai-summary">{{ exportResult()!.aiContextSummary }}</p>
                  }
                  
                  @if (exportResult()!.aiInsights?.length) {
                    <div class="section highlights">
                      <strong>AI Insights:</strong>
                      @for (insight of exportResult()!.aiInsights; track $index) {
                        <p>{{ insight }}</p>
                      }
                    </div>
                  }
                  
                  @if (exportResult()!.chartSuggestions?.length) {
                    <div class="section">
                      <strong>Suggested Charts:</strong>
                      @for (chart of exportResult()!.chartSuggestions; track $index) {
                        <li>{{ chart.chartType }}: {{ chart.title }}</li>
                      }
                    </div>
                  }
                  
                  <div class="columns-preview">
                    <strong>Columns:</strong>
                    <span>{{ exportResult()!.columns?.join(', ') }}</span>
                  </div>
                </div>
              }
            </div>
          }

          @case ('favorites') {
            <div class="feature-section">
              <h4>Favorite Queries</h4>
              <p class="feature-desc">Save and quickly access your favorite queries</p>
              
              <div class="info-box">
                <p>Save your frequently used queries for quick access.</p>
              </div>
              
              <div class="form-group">
                <label>Name</label>
                <input [(ngModel)]="favoriteName" placeholder="Query name" class="form-control"/>
              </div>
              
              <div class="form-group">
                <label>Description</label>
                <input [(ngModel)]="favoriteDescription" placeholder="Brief description" class="form-control"/>
              </div>
              
              <div class="form-group">
                <label>Query / Command</label>
                <textarea [(ngModel)]="favoriteQuery" rows="2" placeholder="Your query or command" class="form-control"></textarea>
              </div>
              
              <div class="form-group">
                <label>Tags (comma-separated)</label>
                <input [(ngModel)]="favoriteTags" placeholder="daily, summary, reports" class="form-control"/>
              </div>
              
              <button class="btn-primary" (click)="saveFavorite()" [disabled]="!favoriteName || !favoriteQuery">
                Save Favorite
              </button>

              @if (favorites().length) {
                <div class="result-card">
                  <strong>Your Favorites:</strong>
                  @for (fav of favorites(); track $index) {
                    <div class="favorite-item">
                      <div class="favorite-header">
                        <strong>{{ fav.name }}</strong>
                        <button class="btn-small danger" (click)="deleteFavorite(fav.id)">Delete</button>
                      </div>
                      <p>{{ fav.description }}</p>
                      <small>Used {{ fav.usageCount }} times | Tags: {{ fav.tags?.join(', ') }}</small>
                    </div>
                  }
                </div>
              }

              <button class="btn-secondary" (click)="loadFavorites()">Load Favorites</button>
            </div>
          }
        }
      </div>
    </div>
  `,
  styles: [`
    .ai-features-panel {
      background: #1a1a2e;
      border-radius: 8px;
      color: #eee;
      padding: 16px;
    }

    .panel-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;
    }

    .panel-header h3 {
      margin: 0;
      color: #fff;
    }

    .ai-badge {
      padding: 4px 12px;
      border-radius: 12px;
      font-size: 12px;
      background: #666;
    }

    .ai-badge.active {
      background: #10b981;
      color: #fff;
    }

    .feature-tabs {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 16px;
    }

    .tab-btn {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 12px;
      border: none;
      border-radius: 6px;
      background: #2d2d44;
      color: #aaa;
      cursor: pointer;
      font-size: 13px;
      transition: all 0.2s;
    }

    .tab-btn:hover {
      background: #3d3d5c;
      color: #fff;
    }

    .tab-btn.active {
      background: #6366f1;
      color: #fff;
    }

    .tab-icon {
      font-size: 16px;
    }

    .feature-section h4 {
      margin: 0 0 4px 0;
      color: #fff;
    }

    .feature-desc {
      color: #888;
      margin-bottom: 16px;
      font-size: 14px;
    }

    .form-group {
      margin-bottom: 12px;
    }

    .form-row {
      display: flex;
      gap: 12px;
    }

    .form-row .form-group {
      flex: 1;
    }

    .form-control {
      width: 100%;
      padding: 8px 12px;
      border: 1px solid #444;
      border-radius: 6px;
      background: #2d2d44;
      color: #fff;
      font-size: 14px;
    }

    .form-control:focus {
      outline: none;
      border-color: #6366f1;
    }

    .btn-primary {
      padding: 10px 20px;
      border: none;
      border-radius: 6px;
      background: #6366f1;
      color: #fff;
      cursor: pointer;
      font-weight: 500;
      transition: background 0.2s;
    }

    .btn-primary:hover:not(:disabled) {
      background: #5558e3;
    }

    .btn-primary:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .info-box {
      padding: 12px;
      background: #2d2d44;
      border-radius: 6px;
      margin-bottom: 16px;
    }

    .info-box p {
      margin: 0;
      color: #aaa;
      font-size: 13px;
    }

    .result-card {
      margin-top: 16px;
      padding: 16px;
      background: #0f0f1a;
      border-radius: 8px;
      border: 1px solid #333;
    }

    .result-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 12px;
      color: #fff;
    }

    .risk-badge {
      padding: 4px 8px;
      border-radius: 4px;
      font-size: 12px;
      font-weight: 600;
    }

    .risk-badge.critical { background: #ef4444; color: #fff; }
    .risk-badge.high { background: #f97316; color: #fff; }
    .risk-badge.medium { background: #eab308; color: #000; }
    .risk-badge.low { background: #10b981; color: #fff; }

    .confidence-badge {
      padding: 4px 8px;
      border-radius: 4px;
      font-size: 12px;
      background: #6366f1;
      color: #fff;
    }

    .priority-badge {
      padding: 4px 8px;
      border-radius: 4px;
      font-size: 12px;
      font-weight: 600;
    }

    .priority-badge.critical { background: #ef4444; color: #fff; }
    .priority-badge.high { background: #f97316; color: #fff; }
    .priority-badge.medium { background: #eab308; color: #000; }
    .priority-badge.low { background: #10b981; color: #fff; }
    .priority-badge.unknown { background: #666; color: #fff; }

    .anomaly-item, .prediction-item, .category-item {
      padding: 12px;
      background: #1a1a2e;
      border-radius: 6px;
      margin-bottom: 8px;
    }

    .anomaly-item .severity {
      display: inline-block;
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 600;
      margin-bottom: 8px;
    }

    .severity.critical { background: #ef4444; color: #fff; }
    .severity.high { background: #f97316; color: #fff; }
    .severity.medium { background: #eab308; color: #000; }
    .severity.low { background: #10b981; color: #fff; }

    .anomaly-info p {
      margin: 4px 0;
      color: #aaa;
      font-size: 13px;
    }

    .anomaly-info small {
      color: #666;
    }

    .prediction-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }

    .prediction-item p {
      margin: 4px 0;
      color: #aaa;
    }

    .prediction-item small {
      color: #6366f1;
    }

    .recommendations, .section {
      margin-top: 12px;
      padding-top: 12px;
      border-top: 1px solid #333;
    }

    .recommendations ul, .section ul {
      margin: 8px 0;
      padding-left: 20px;
    }

    .recommendations li, .section li {
      color: #aaa;
      margin-bottom: 4px;
      font-size: 13px;
    }

    .incidents li {
      color: #888;
      font-style: italic;
    }

    .estimate {
      color: #6366f1;
      margin: 8px 0;
    }

    .summary-stats {
      display: flex;
      gap: 24px;
      margin-bottom: 16px;
    }

    .stat {
      text-align: center;
    }

    .stat-value {
      display: block;
      font-size: 24px;
      font-weight: 600;
      color: #fff;
    }

    .stat-label {
      color: #888;
      font-size: 12px;
    }

    .summary-text {
      padding: 12px;
      background: #2d2d44;
      border-radius: 6px;
      margin-bottom: 12px;
    }

    .summary-text p {
      margin: 8px 0 0 0;
      color: #aaa;
    }

    .issue-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px;
      background: #2d2d44;
      border-radius: 6px;
      margin-bottom: 4px;
    }

    .issue-count {
      font-weight: 600;
      color: #fff;
      min-width: 40px;
    }

    .issue-trend {
      margin-left: auto;
      font-size: 12px;
      padding: 2px 8px;
      border-radius: 4px;
    }

    .issue-trend.increasing { background: #ef4444; }
    .issue-trend.decreasing { background: #10b981; }
    .issue-trend.stable { background: #666; }

    .highlights {
      background: #10b98120;
      padding: 12px;
      border-radius: 6px;
    }

    .highlights p {
      margin: 4px 0;
      color: #10b981;
    }

    .risk-scores {
      margin-bottom: 12px;
    }

    .risk-item {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 4px;
    }

    .risk-value.high {
      color: #ef4444;
      font-weight: 600;
    }

    .quality-header {
      display: flex;
      align-items: center;
      gap: 16px;
      margin-bottom: 16px;
    }

    .grade-badge {
      width: 60px;
      height: 60px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 8px;
      font-size: 28px;
      font-weight: 700;
    }

    .grade-badge.a+, .grade-badge.a { background: #10b981; color: #fff; }
    .grade-badge.b { background: #6366f1; color: #fff; }
    .grade-badge.c { background: #eab308; color: #000; }
    .grade-badge.d, .grade-badge.f { background: #ef4444; color: #fff; }
    .grade-badge.n\\/a, .grade-badge.error { background: #666; color: #fff; }

    .score-info {
      display: flex;
      flex-direction: column;
    }

    .score-value {
      font-size: 32px;
      font-weight: 600;
      color: #fff;
    }

    .ready-status {
      font-size: 14px;
      padding: 2px 8px;
      border-radius: 4px;
      margin-top: 4px;
      display: inline-block;
    }

    .ready-status.ready { background: #10b981; color: #fff; }
    .ready-status.not-ready { background: #ef4444; color: #fff; }

    .quality-stats {
      display: flex;
      gap: 24px;
      margin-bottom: 16px;
    }

    .stat.passed .stat-value { color: #10b981; }
    .stat.failed .stat-value { color: #ef4444; }

    .dimension-scores {
      margin-bottom: 12px;
    }

    .dimension-item {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 8px;
    }

    .score-bar {
      flex: 1;
      height: 8px;
      background: #333;
      border-radius: 4px;
      overflow: hidden;
    }

    .score-fill {
      height: 100%;
      background: #6366f1;
      border-radius: 4px;
      transition: width 0.3s;
    }

    .issues {
      background: #ef444420;
      padding: 12px;
      border-radius: 6px;
    }

    .triage-summary {
      color: #aaa;
      margin: 8px 0 16px 0;
    }

    .priority-breakdown {
      margin-bottom: 16px;
    }

    .priority-bars {
      margin-top: 8px;
    }

    .priority-item {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 8px;
    }

    .priority-item span:first-child {
      min-width: 60px;
      font-size: 13px;
    }

    .bar-container {
      flex: 1;
      height: 20px;
      background: #333;
      border-radius: 4px;
      overflow: hidden;
    }

    .bar {
      height: 100%;
      transition: width 0.3s;
    }

    .bar.critical { background: #ef4444; }
    .bar.high { background: #f97316; }
    .bar.medium { background: #eab308; }
    .bar.low { background: #10b981; }

    .category-item {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }

    .category-item strong {
      margin-right: 8px;
    }

    .category-item p {
      width: 100%;
      margin: 4px 0 0 0;
      color: #888;
      font-size: 12px;
    }

    .escalation-warning {
      padding: 12px;
      background: #ef4444;
      border-radius: 6px;
      color: #fff;
      font-weight: 600;
      text-align: center;
      margin-top: 12px;
    }

    .recommendation-reason {
      color: #6366f1;
      margin-bottom: 4px;
    }

    .confidence {
      color: #888;
      font-size: 13px;
      margin-bottom: 16px;
    }

    .recommendation-item {
      padding: 16px;
      background: #2d2d44;
      border-radius: 6px;
      margin-bottom: 12px;
    }

    .rec-header {
      margin-bottom: 8px;
    }

    .rec-type {
      padding: 2px 8px;
      background: #6366f1;
      border-radius: 4px;
      font-size: 12px;
    }

    .rec-values {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 8px;
    }

    .current {
      color: #888;
      text-decoration: line-through;
    }

    .arrow {
      color: #6366f1;
    }

    .recommended {
      color: #10b981;
      font-weight: 600;
    }

    .rec-reason {
      color: #aaa;
      font-size: 13px;
      margin: 4px 0;
    }

    .rec-impact {
      color: #666;
    }

    .disclaimer {
      margin-top: 16px;
      padding: 12px;
      background: #2d2d44;
      border-radius: 6px;
      font-size: 12px;
      color: #888;
    }

    /* New AI feature styles */
    .insights, .risk-assessment {
      color: #6366f1;
      margin-bottom: 16px;
      font-style: italic;
    }

    .comparison-item {
      padding: 16px;
      background: #1a1a2e;
      border-radius: 6px;
      margin-bottom: 12px;
    }

    .comp-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 12px;
    }

    .rank-badge {
      padding: 2px 8px;
      background: #10b981;
      border-radius: 4px;
      font-size: 12px;
      font-weight: 600;
    }

    .trend-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px;
      background: #2d2d44;
      border-radius: 6px;
      margin-bottom: 8px;
    }

    .direction-badge {
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 600;
    }

    .direction-badge.up { background: #10b981; }
    .direction-badge.down { background: #ef4444; }
    .direction-badge.stable { background: #666; }

    .pattern-item {
      padding: 8px;
      background: #1a1a2e;
      border-radius: 4px;
      margin-bottom: 4px;
    }

    .pattern-type {
      padding: 2px 6px;
      background: #6366f1;
      border-radius: 4px;
      font-size: 11px;
      margin-right: 8px;
    }

    .batch-recommendation {
      padding: 16px;
      background: #2d2d44;
      border-radius: 6px;
      margin-bottom: 16px;
    }

    .optimal-size {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }

    .size-label {
      color: #888;
      font-size: 13px;
    }

    .size-value {
      font-size: 32px;
      font-weight: 600;
      color: #10b981;
    }

    .range-info {
      display: flex;
      gap: 16px;
      font-size: 13px;
      color: #666;
    }

    .cost-summary {
      padding: 16px;
      background: #2d2d44;
      border-radius: 6px;
      margin-bottom: 16px;
    }

    .cost-total {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 12px;
    }

    .cost-label {
      color: #888;
    }

    .cost-value {
      font-size: 28px;
      font-weight: 600;
      color: #fff;
    }

    .cost-breakdown {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 8px;
    }

    .cost-item {
      color: #aaa;
      font-size: 13px;
    }

    .savings-item {
      padding: 12px;
      background: #10b98120;
      border-radius: 6px;
      margin-bottom: 8px;
    }

    .savings-value {
      color: #10b981;
      font-weight: 600;
      float: right;
    }

    .ai-summary {
      padding: 12px;
      background: #6366f120;
      border-radius: 6px;
      margin-bottom: 12px;
      color: #a5b4fc;
      font-style: italic;
    }

    .kb-result {
      padding: 12px;
      background: #1a1a2e;
      border-radius: 6px;
      margin-bottom: 8px;
    }

    .kb-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }

    .kb-category {
      padding: 2px 6px;
      background: #3d3d5c;
      border-radius: 4px;
      font-size: 11px;
    }

    .status-badge {
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 12px;
    }

    .status-badge.success, .status-badge.active { background: #10b981; }
    .status-badge.error, .status-badge.inactive { background: #ef4444; }

    .checkbox-group {
      display: flex;
      gap: 16px;
      padding: 8px 0;
    }

    .checkbox-group label {
      display: flex;
      align-items: center;
      gap: 6px;
      color: #aaa;
      cursor: pointer;
    }

    .schedule-item {
      padding: 12px;
      background: #1a1a2e;
      border-radius: 6px;
      margin-bottom: 8px;
    }

    .schedule-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }

    .results-count {
      color: #888;
      font-size: 13px;
      margin-bottom: 12px;
    }

    .columns-preview {
      padding: 12px;
      background: #2d2d44;
      border-radius: 6px;
      margin-top: 12px;
    }

    .columns-preview span {
      color: #aaa;
      font-size: 12px;
    }

    .favorite-item {
      padding: 12px;
      background: #1a1a2e;
      border-radius: 6px;
      margin-bottom: 8px;
    }

    .favorite-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }

    .btn-secondary {
      padding: 8px 16px;
      border: 1px solid #444;
      border-radius: 6px;
      background: #2d2d44;
      color: #aaa;
      cursor: pointer;
      font-size: 13px;
      margin-right: 8px;
      margin-bottom: 8px;
    }

    .btn-secondary:hover {
      background: #3d3d5c;
      color: #fff;
    }

    .btn-small {
      padding: 4px 8px;
      border: none;
      border-radius: 4px;
      background: #6366f1;
      color: #fff;
      cursor: pointer;
      font-size: 11px;
    }

    .btn-small:hover {
      background: #5558e3;
    }

    .btn-small.danger {
      background: #ef4444;
    }

    .btn-small.danger:hover {
      background: #dc2626;
    }
  `]
})
export class AiFeaturesPanelComponent {
  aiService = inject(AiService);

  // Loading state
  loading = signal(false);

  // Tab management
  activeTab = signal('anomaly');
  
  featureTabs: FeatureTab[] = [
    { id: 'anomaly', label: 'Anomalies', icon: '📊', description: 'Detect unusual patterns' },
    { id: 'rootcause', label: 'Root Cause', icon: '🔍', description: 'Analyze failures' },
    { id: 'summary', label: 'Daily Summary', icon: '📋', description: 'Get daily report' },
    { id: 'predict', label: 'Predictive', icon: '🔮', description: 'Predict failures' },
    { id: 'quality', label: 'Quality', icon: '✓', description: 'Score data quality' },
    { id: 'triage', label: 'Triage', icon: '🎯', description: 'Prioritize alerts' },
    { id: 'recommend', label: 'Recommend', icon: '💡', description: 'Get suggestions' },
    // New AI features
    { id: 'handoff', label: 'Shift Handoff', icon: '🔄', description: 'Generate shift summaries' },
    { id: 'maintenance', label: 'Maintenance', icon: '⚙️', description: 'Predictive maintenance' },
    { id: 'crosssite', label: 'Site Compare', icon: '🌐', description: 'Compare across sites' },
    { id: 'trends', label: 'Trends', icon: '📈', description: 'Forecast trends' },
    { id: 'incident', label: 'Incidents', icon: '🚨', description: 'Auto-generate reports' },
    { id: 'batch', label: 'Batch Size', icon: '📦', description: 'Optimize batch sizing' },
    { id: 'cost', label: 'Cost', icon: '💰', description: 'Analyze costs' },
    { id: 'knowledge', label: 'Knowledge', icon: '📚', description: 'Search knowledge base' },
    { id: 'notify', label: 'Notifications', icon: '📬', description: 'Send alerts to Slack/Teams' },
    { id: 'schedules', label: 'Schedules', icon: '📅', description: 'Manage report schedules' },
    { id: 'export', label: 'Export', icon: '📥', description: 'AI-enhanced data export' },
    { id: 'favorites', label: 'Favorites', icon: '⭐', description: 'Saved queries' }
  ];

  // Anomaly Detection
  anomalySite = '';
  anomalyResult = signal<AnomalyDetectionResponse | null>(null);

  // Root Cause Analysis
  rcaErrorCode = '';
  rcaErrorMessage = '';
  rcaSite = '';
  rcaResult = signal<RootCauseAnalysisResponse | null>(null);

  // Daily Summary
  summaryDate = new Date().toISOString().split('T')[0];
  summaryResult = signal<DailySummaryResponse | null>(null);

  // Predictive Failure
  predictSite = 'SLN2';
  predictLotIds = '';
  predictResult = signal<PredictiveFailureResponse | null>(null);

  // Data Quality Score
  qualitySite = 'SLN2';
  qualityResult = signal<DataQualityScoreResponse | null>(null);

  // Alert Triage
  triageResult = signal<AlertTriageResponse | null>(null);

  // Session Recommendations
  recommendSite = 'SLN2';
  recommendSender = '';
  recommendResult = signal<SessionRecommendationResponse | null>(null);

  // Shift Handoff
  handoffSite = 'SLN2';
  handoffShift: 'DAY' | 'NIGHT' | 'SWING' = 'DAY';
  handoffResult = signal<ShiftHandoffSummary | null>(null);

  // Predictive Maintenance
  maintenanceSite = 'SLN2';
  maintenanceResult = signal<PredictiveMaintenanceResponse | null>(null);

  // Cross-Site Comparison
  crosssiteSites = ['SLN2', 'SLN3'];
  crosssiteMetrics = ['successRate', 'throughput', 'errorRate'];
  crosssiteResult = signal<CrossSiteComparisonResponse | null>(null);

  // Trend Forecasting
  trendsSite = 'SLN2';
  trendsDays = 7;
  trendsResult = signal<TrendForecastingResponse | null>(null);

  // Auto Incident Report
  incidentSeverity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' = 'HIGH';
  incidentStartTime = new Date().toISOString().slice(0, 16);
  incidentComponents = '';
  incidentResult = signal<AutoIncidentReportResponse | null>(null);

  // Optimal Batch Sizing
  batchSite = 'SLN2';
  batchResult = signal<OptimalBatchSizingResponse | null>(null);

  // Cost Analysis
  costSite = 'SLN2';
  costTimeRange = '7d';
  costResult = signal<CostAnalysisResponse | null>(null);

  // Knowledge Base Search
  kbQuery = '';
  kbCategory = '';
  kbResult = signal<KnowledgeBaseSearchResponse | null>(null);

  // Notifications
  notifyType: 'ALERT' | 'SUMMARY' | 'INCIDENT' | 'RECOMMENDATION' | 'CUSTOM' = 'ALERT';
  notifySeverity: 'INFO' | 'WARNING' | 'CRITICAL' = 'WARNING';
  notifyTitle = '';
  notifyMessage = '';
  notifyChannels: ('SLACK' | 'TEAMS' | 'EMAIL')[] = ['EMAIL'];
  notifyResult = signal<NotificationResponse | null>(null);

  // Scheduled Reports
  schedules = signal<ReportSchedule[]>([]);

  // Export
  exportDataType: 'sessions' | 'errors' | 'senders' | 'performance' = 'sessions';
  exportFormat: 'CSV' | 'EXCEL' | 'JSON' = 'CSV';
  exportResult = signal<ExportResponse | null>(null);

  // Favorites
  favorites = signal<FavoriteQuery[]>([]);
  favoriteName = '';
  favoriteDescription = '';
  favoriteQuery = '';
  favoriteTags = '';

  selectTab(tabId: string) {
    this.activeTab.set(tabId);
  }

  // Anomaly Detection
  detectAnomalies() {
    this.loading.set(true);
    this.aiService.detectAnomalies(this.anomalySite || 'ALL').subscribe({
      next: (result) => {
        this.anomalyResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Root Cause Analysis
  analyzeRootCause() {
    if (!this.rcaErrorCode || !this.rcaErrorMessage) {
      return;
    }
    this.loading.set(true);
    this.aiService.analyzeRootCause(this.rcaErrorCode, this.rcaErrorMessage, this.rcaSite || undefined).subscribe({
      next: (result) => {
        this.rcaResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Daily Summary
  getDailySummary() {
    this.loading.set(true);
    this.aiService.getDailySummary(this.summaryDate).subscribe({
      next: (result) => {
        this.summaryResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Predictive Failure
  predictFailures() {
    this.loading.set(true);
    const lotIds = this.predictLotIds ? this.predictLotIds.split(',').map(l => l.trim()) : undefined;
    this.aiService.predictFailures(this.predictSite, lotIds).subscribe({
      next: (result) => {
        this.predictResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Data Quality Score
  scoreDataQuality() {
    this.loading.set(true);
    this.aiService.scoreDataQuality([], this.qualitySite).subscribe({
      next: (result) => {
        this.qualityResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Alert Triage
  triageAlerts() {
    this.loading.set(true);
    // For demo, use empty alerts - in production would fetch from alert service
    this.aiService.triageAlerts([]).subscribe({
      next: (result) => {
        this.triageResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Session Recommendations
  getRecommendations() {
    this.loading.set(true);
    this.aiService.getSessionRecommendations(this.recommendSite, this.recommendSender || undefined).subscribe({
      next: (result) => {
        this.recommendResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Shift Handoff Summary
  getShiftHandoff() {
    this.loading.set(true);
    this.aiService.getShiftHandoffSummary(this.handoffSite, this.handoffShift).subscribe({
      next: (result) => {
        this.handoffResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Predictive Maintenance
  getMaintenancePrediction() {
    this.loading.set(true);
    this.aiService.getMaintenancePrediction(this.maintenanceSite).subscribe({
      next: (result) => {
        this.maintenanceResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Cross-Site Comparison
  compareSites() {
    this.loading.set(true);
    this.aiService.compareSites(this.crosssiteSites, this.crosssiteMetrics).subscribe({
      next: (result) => {
        this.crosssiteResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Trend Forecasting
  forecastTrends() {
    this.loading.set(true);
    this.aiService.forecastTrends(this.trendsSite, this.trendsDays).subscribe({
      next: (result) => {
        this.trendsResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Auto Incident Report
  generateIncidentReport() {
    this.loading.set(true);
    const request = {
      severity: this.incidentSeverity,
      startTime: this.incidentStartTime,
      affectedComponents: this.incidentComponents.split(',').map(c => c.trim()),
      site: this.handoffSite
    };
    this.aiService.generateIncidentReport(request).subscribe({
      next: (result) => {
        this.incidentResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Optimal Batch Sizing
  getOptimalBatchSize() {
    this.loading.set(true);
    this.aiService.getOptimalBatchSize(this.batchSite).subscribe({
      next: (result) => {
        this.batchResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Cost Analysis
  analyzeCosts() {
    this.loading.set(true);
    this.aiService.analyzeCosts(this.costSite, this.costTimeRange).subscribe({
      next: (result) => {
        this.costResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Knowledge Base Search
  searchKnowledge() {
    this.loading.set(true);
    this.aiService.searchKnowledge(this.kbQuery, this.kbCategory || undefined).subscribe({
      next: (result) => {
        this.kbResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Notifications
  sendNotification() {
    this.loading.set(true);
    const request = {
      type: this.notifyType,
      severity: this.notifySeverity,
      title: this.notifyTitle,
      message: this.notifyMessage,
      channels: this.notifyChannels
    };
    this.aiService.sendNotification(request).subscribe({
      next: (result) => {
        this.notifyResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Scheduled Reports
  loadSchedules() {
    this.aiService.getSchedules().subscribe({
      next: (result) => this.schedules.set(result.schedules),
      error: () => {}
    });
  }

  // Export
  exportData() {
    this.loading.set(true);
    const request = {
      dataType: this.exportDataType,
      format: this.exportFormat,
      includeAiContext: true
    };
    this.aiService.exportData(request).subscribe({
      next: (result) => {
        this.exportResult.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  // Favorites
  loadFavorites() {
    this.aiService.getFavorites('default').subscribe({
      next: (result) => this.favorites.set(result.favorites),
      error: () => {}
    });
  }

  saveFavorite() {
    this.aiService.saveFavorite({
      name: this.favoriteName,
      description: this.favoriteDescription,
      query: this.favoriteQuery,
      tags: this.favoriteTags.split(',').map(t => t.trim())
    }).subscribe({
      next: () => {
        this.favoriteName = '';
        this.favoriteDescription = '';
        this.favoriteQuery = '';
        this.favoriteTags = '';
        this.loadFavorites();
      },
      error: () => {}
    });
  }

  deleteFavorite(id: string) {
    this.aiService.deleteFavorite('default', id).subscribe({
      next: () => this.loadFavorites(),
      error: () => {}
    });
  }

  // Helper to iterate over object entries
  objectEntries(obj: Record<string, number>): [string, number][] {
    return Object.entries(obj);
  }
}