# AI Integration Plan - ExensioReload

**Document Version:** 1.0  
**Date:** 2026-06-02  
**Architecture Level:** Lead Architect  
**Status:** Draft for Review

---

## 1. Executive Summary

This document outlines a phased approach to integrate AI capabilities into the ExensioReload manufacturing data staging application. The goal is to enhance operational efficiency through intelligent data analysis, natural language search, and proactive monitoring.

### 1.1 Scope of AI Integration

| Phase | Features | Priority |
|-------|----------|----------|
| **Phase 1** | AI Chat Assistant, Alert Summarization | High |
| **Phase 2** | Natural Language Search, Session Recommendations | Medium |
| **Phase 3** | Anomaly Detection, Predictive Failure | Low |

### 1.2 Decision Record

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **LLM Provider** | Anthropic Claude API | Best cost/quality for structured data analysis, generous free tier |
| **Integration Pattern** | External API (Gateway Pattern) | Flexibility to swap providers, no infrastructure overhead |
| **Frontend Pattern** | Chat Panel Component | Reusable across features, familiar UX pattern |
| **Data Flow** | Backend proxies requests | Security, token management, response caching |

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Frontend (Angular)                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐  │
│  │  AI Chat Panel  │  │  Alert Summary  │  │  NL Search Interface        │  │
│  │  (Expandable)   │  │  (Dashboard)    │  │  (Session/Search Page)      │  │
│  └────────┬────────┘  └────────┬────────┘  └──────────────┬──────────────┘  │
└───────────┼────────────────────┼─────────────────────────┼──────────────────┘
            │                    │                         │
            ▼                    ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Backend (Spring Boot)                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                        AI Service Layer                              │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐   │    │
│  │  │ AiChatService│  │ AiSummarize  │  │ AiSearchService          │   │    │
│  │  │              │  │ Service      │  │                          │   │    │
│  │  └──────┬───────┘  └──────┬───────┘  └──────────────┬───────────┘   │    │
│  └─────────┼─────────────────┼─────────────────────────┼───────────────┘    │
│            │                 │                         │                    │
│  ┌─────────▼─────────────────▼─────────────────────────▼───────────────┐    │
│  │                     AI Gateway (AiGatewayService)                   │    │
│  │  - Provider abstraction (Claude/OpenAI)                             │    │
│  │  - Token management, rate limiting, caching                         │    │
│  │  - Response parsing, error handling                                 │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                    │                                        │
└────────────────────────────────────┼────────────────────────────────────────┘
                                     │
                                     ▼
                    ┌────────────────────────────────┐
                    │     External LLM Provider       │
                    │     (Anthropic Claude API)      │
                    └────────────────────────────────┘
```

---

## 3. Technical Specifications

### 3.1 LLM Configuration

**Provider:** Anthropic Claude (claude-sonnet-4-20250514 model)

```properties
# application-ai.properties
ai.provider=anthropic
ai.api-key=${ANTHROPIC_API_KEY}
ai.model=claude-sonnet-4-20250514
ai.max-tokens=1024
ai.temperature=0.7
ai.timeout-ms=30000
ai.max-retries=3
```

### 3.2 Data Context for AI

The AI will receive structured context about:

| Data Type | Source | Purpose |
|-----------|--------|---------|
| **Session Stats** | `MetricsService`, `StageMonitorService` | Historical analysis |
| **Alert Data** | `AlertController` | Summarization |
| **Load Results** | `SenderDispatchService` | Failure pattern detection |
| **User Queries** | Frontend input | Natural language understanding |

### 3.3 Security Considerations

1. **API Key Management** - Store in environment variables, never in code
2. **User Context** - AI only sees anonymized session data, no PII
3. **Rate Limiting** - Per-user limits to prevent abuse
4. **Audit Logging** - Log all AI requests and responses

---

## 4. Implementation Phases

### Phase 1: Foundation (Week 1-2)

#### Step 1.1: Backend AI Service Layer

**Files to Create:**

```
backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/
├── config/
│   └── AiProperties.java              # Configuration properties class
├── service/
│   └── ai/
│       ├── AiGatewayService.java      # Main gateway, provider abstraction
│       ├── AiChatService.java         # Chat conversation handling
│       ├── AiSummarizeService.java    # Alert/data summarization
│       └── AiSearchService.java       # Natural language search
└── controller/
    └── AiController.java              # REST endpoints
```

**AiGatewayService.java** - Core responsibilities:
- Abstract LLM provider (support Claude initially, extensibility for others)
- Build system prompt with context about the application
- Handle API calls with retry logic
- Parse structured responses
- Implement caching for repeated queries

#### Step 1.2: Configuration Setup

**application.yml additions:**

```yaml
ai:
  enabled: true
  provider: anthropic
  model: claude-sonnet-4-20250514
  max-tokens: 1024
  temperature: 0.7
  timeout-ms: 30000
  rate-limit:
    requests-per-minute: 20
    requests-per-hour: 200
  cache:
    enabled: true
    ttl-minutes: 15
```

#### Step 1.3: Frontend AI Components

**Files to Create in frontend/src/app:**

```
src/app/
├── ai/
│   ├── ai-chat/
│   │   ├── ai-chat.component.ts       # Main chat panel
│   │   ├── ai-chat.component.html
│   │   └── ai-chat.component.scss
│   ├── ai.service.ts                  # Backend communication
│   ├── ai.module.ts                   # Module registration
│   └── ai.types.ts                    # TypeScript interfaces
```

**Component Features:**
- Expandable/collapsible chat panel (floating button trigger)
- Message history with timestamps
- Loading states and typing indicators
- Error handling with retry options
- Markdown rendering for AI responses

---

### Phase 2: Feature Integration (Week 3-4)

#### Step 2.1: Dashboard AI Integration

Add AI summary widget to the main dashboard:
- Real-time alert summarization
- Anomaly highlights
- Quick action suggestions

#### Step 2.2: Session Search Enhancement

Natural language search across:
- Staging sessions (status, date range, lot numbers)
- Send history (success/failure patterns)
- User activity logs

---

### Phase 3: Advanced Features (Week 5-8)

#### Step 3.1: Anomaly Detection Service

- Integrate with `MetricsService` for historical data
- Statistical anomaly detection (z-score, IQR)
- AI-assisted root cause analysis

#### Step 3.2: Recommendation Engine

- Session filter suggestions based on past behavior
- Optimal timing recommendations for batch operations

---

## 5. File Specifications

### 5.1 Backend - AiGatewayService.java

```java
package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class AiGatewayService {
    
    private final AiProperties properties;
    private final WebClient webClient;
    
    // System prompt that gives AI context about ExensioReload
    private static final String SYSTEM_PROMPT = """
        You are an AI assistant for ExensioReload, a manufacturing data staging application.
        You help operators monitor lot processing, manage staging sessions, and troubleshoot issues.
        
        Key concepts:
        - Lots: Manufacturing lot identifiers (e.g., "LOT12345")
        - Wafers: Individual wafers within lots
        - Senders: Data transmission endpoints
        - Staging Sessions: Data filtering and staging workflows
        - Exensio: Target data management system
        
        Always be concise and actionable in your responses.
        """;
    
    public String sendMessage(String userMessage, Map<String, Object> context) {
        // Build prompt with context
        // Call Anthropic API
        // Parse and return response
    }
}
```

### 5.2 Backend - AiController.java

```java
@RestController
@RequestMapping("/api/ai")
public class AiController {
    
    private final AiChatService chatService;
    private final AiSummarizeService summarizeService;
    
    @PostMapping("/chat")
    public ResponseEntity<AiChatResponse> chat(@RequestBody AiChatRequest request) {
        return ResponseEntity.ok(chatService.process(request));
    }
    
    @PostMapping("/summarize/alerts")
    public ResponseEntity<AiSummaryResponse> summarizeAlerts(
            @RequestBody List<AlertData> alerts) {
        return ResponseEntity.ok(summarizeService.summarizeAlerts(alerts));
    }
}
```

### 5.3 Frontend - ai.service.ts

```typescript
@Injectable({ providedIn: 'root' })
export class AiService {
  private apiUrl = '/api/ai';
  
  chat(message: string, context?: SessionContext): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.apiUrl}/chat`, {
      message,
      context
    });
  }
  
  summarizeAlerts(alerts: Alert[]): Observable<AiSummary> {
    return this.http.post<AiSummary>(`${this.apiUrl}/summarize/alerts`, alerts);
  }
}
```

### 5.4 Frontend - ai-chat.component.ts

```typescript
@Component({
  selector: 'app-ai-chat',
  templateUrl: './ai-chat.component.html',
  styleUrls: ['./ai-chat.component.scss']
})
export class AiChatComponent implements OnInit {
  messages: ChatMessage[] = [];
  isOpen = false;
  
  send(message: string) {
    // Add user message
    // Call AI service
    // Add AI response with typing animation
  }
}
```

---

## 6. API Specifications

### 6.1 Chat Endpoint

**POST /api/ai/chat**

Request:
```json
{
  "message": "Show me lots that failed in the last 24 hours",
  "context": {
    "currentSessionId": 123,
    "userRole": "OPERATOR"
  }
}
```

Response:
```json
{
  "reply": "Found 3 lots with failures in the last 24 hours:\n- LOT12345 (Sender A, 2:30 PM)\n- LOT67890 (Sender B, 4:15 PM)\n- LOT11111 (Sender C, 11:00 PM)",
  "suggestedActions": [
    {"label": "Retry LOT12345", "action": "retry", "lotId": "LOT12345"}
  ],
  "confidence": 0.95
}
```

### 6.2 Summarize Alerts Endpoint

**POST /api/ai/summarize/alerts**

Request:
```json
{
  "alerts": [
    {"sender": "SENDER_A", "error": "Connection timeout", "timestamp": "..."},
    {"sender": "SENDER_B", "error": "Auth failure", "timestamp": "..."},
    {"sender": "SENDER_A", "error": "Connection timeout", "timestamp": "..."}
  ]
}
```

Response:
```json
{
  "summary": "SENDER_A has 2 connection timeout errors (possibly network issue). SENDER_B has 1 auth failure.",
  "groups": [
    {"issue": "Connection timeout", "count": 2, "senders": ["SENDER_A"], "recommendation": "Check network connectivity"}
  ],
  "priority": "MEDIUM"
}
```

---

## 7. Testing Strategy

### 7.1 Unit Tests

```java
@SpringBootTest
class AiGatewayServiceTest {
    
    @Test
    void shouldRetryOnTransientErrors() { }
    
    @Test
    void shouldParseStructuredResponse() { }
    
    @Test
    void shouldRespectRateLimits() { }
    
    @Test
    void shouldUseCacheForRepeatedQueries() { }
}
```

### 7.2 Integration Tests

- Mock LLM API responses
- Test full conversation flow
- Verify error handling

### 7.3 E2E Tests

- User opens chat, sends message, receives response
- Dashboard AI summary updates correctly

---

## 8. Rollout Plan

### 8.1 Environment Strategy

| Environment | Purpose | LLM Provider |
|-------------|---------|--------------|
| **Development** | Local testing | Mock API responses |
| **Staging** | QA testing | Free tier API |
| **Production** | Live users | Paid tier with rate limits |

### 8.2 Feature Flags

```java
ai:
  enabled: ${AI_ENABLED:false}
  features:
    chat: ${AI_CHAT_ENABLED:true}
    summarize: ${AI_SUMMARIZE_ENABLED:true}
    search: ${AI_SEARCH_ENABLED:false}
```

### 8.3 Monitoring

- Log all AI requests with latency metrics
- Track error rates by feature
- Monitor API costs

---

## 9. Risk Mitigation

| Risk | Mitigation |
|------|------------|
| **API costs spiral** | Rate limiting, caching, cost alerts |
| **AI provides wrong info** | Confidence scores, fallback to search |
| **Latency impact** | Async responses, streaming, caching |
| **API outage** | Graceful degradation, offline mode |

---

## 10. Future Considerations

1. **Self-hosted models** - Move to local Llama/Mistral for privacy/cost
2. **Fine-tuning** - Train on historical support tickets for better context
3. **Multi-modal** - Analyze wafer map images for defect patterns
4. **Agent capabilities** - Let AI take actions (resend, configure alerts)

---

## 11. Checklist

### Backend
- [x] Create AiProperties configuration class (`config/AiProperties.java`)
- [x] Create AiGatewayService (`service/ai/AiGatewayService.java`)
- [x] Create AiChatService (`service/ai/AiChatService.java`)
- [x] Create AiSummarizeService (`service/ai/AiSummarizeService.java`)
- [x] Create AiController (`controller/AiController.java`)
- [x] Add unit tests (`AiPropertiesTest.java`, `AiGatewayServiceTest.java`)
- [x] Add health check endpoint (`/api/ai/health`)

### Frontend
- [x] Create TypeScript interfaces (`ai/ai.types.ts`)
- [x] Create AiService (`ai/ai.service.ts`)
- [x] Create AiChatComponent (`ai/ai-chat.component.ts/html/scss`)
- [x] Create AI dashboard widget (`ai-dashboard-widget.component.ts/html/scss`)
- [x] Create AI status indicator (`ai-status-indicator.component.ts`)
- [x] Add error handling UI

### Infrastructure
- [x] Add AI configuration to application.yml
- [x] Set environment variables (AI_API_KEY, AI_ENABLED)
- [x] Configure feature flags (already in application.yml)
- [x] Document for operations team (this document + AI_QUICK_START.md)

## 12. Implementation Summary (Completed Phase 1)

### Files Created

**Backend (Java/Spring Boot):**
```
backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/
├── config/
│   └── AiProperties.java
├── service/ai/
│   ├── AiGatewayService.java
│   ├── AiChatService.java
│   └── AiSummarizeService.java
├── controller/
│   └── AiController.java
└── dto/ai/
    ├── AiChatRequest.java
    ├── AiChatResponse.java
    ├── AiSummarizeRequest.java
    └── AiSummarizeResponse.java
```

**Frontend (Angular):**
```
frontend/src/app/ai/
├── ai.types.ts
├── ai.service.ts
├── ai-chat.component.ts
├── ai-chat.component.html
└── ai-chat.component.scss
```

**Configuration:**
- `application.yml` - AI configuration section added

**App Integration:**
- `app.ts` - Added AiChatComponent to imports
- `app.html` - Added `<app-ai-chat>` component

## 13. Next Steps (To Enable AI)

1. **Get API Key:**
   - Sign up at https://console.anthropic.com/ for Claude API
   - Or use OpenAI API key

2. **Set Environment Variables:**
   ```bash
   export AI_API_KEY=your-api-key-here
   export AI_ENABLED=true
   ```

3. **Restart Backend:**
   ```bash
   cd backend && mvn spring-boot:run
   ```

4. **Test:**
   - Open the application in browser
   - Look for the AI chat button in bottom-right corner
   - Click to open chat and start asking questions

## 14. API Endpoints

### Core Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/ai/status` | Get AI service status |
| GET | `/api/ai/health` | Health check |
| POST | `/api/ai/chat` | Send chat message |
| POST | `/api/ai/summarize/alerts` | Summarize alerts |
| DELETE | `/api/ai/conversation/{id}` | Clear conversation |

### Feature Endpoints (All AI Features)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/ai/search` | Natural language search |
| POST | `/api/ai/alerts/triage` | Smart alert triage |
| POST | `/api/ai/recommendations/session` | Session recommendations |
| POST | `/api/ai/anomaly/detect` | Detect anomalies |
| POST | `/api/ai/analysis/root-cause` | Root cause analysis |
| POST | `/api/ai/summary/daily` | Daily summary report |
| POST | `/api/ai/predict/failure` | Predictive failure analysis |
| POST | `/api/ai/quality/score` | Data quality scoring |
| POST | `/api/ai/routing/optimal` | Intelligent routing |

---

## 15. Complete AI Feature Implementation (Phase 1-3 Complete)

All AI features have been implemented as of 2026-06-02.

### Backend Services
| Service | Purpose | Endpoint |
|---------|---------|----------|
| AiGatewayService | LLM API gateway | All AI endpoints |
| AiChatService | Chat conversation handling | /api/ai/chat |
| AiSummarizeService | Alert summarization | /api/ai/summarize/alerts |
| NaturalLanguageSearchService | NL search to SQL | /api/ai/search |
| SmartAlertTriageService | Alert prioritization | /api/ai/alerts/triage |
| SessionRecommendationService | Config recommendations | /api/ai/recommendations/session |
| AnomalyDetectionService | Pattern anomaly detection | /api/ai/anomaly/detect |
| RootCauseAnalysisService | Failure root cause | /api/ai/analysis/root-cause |
| DailySummaryService | Daily report generation | /api/ai/summary/daily |
| PredictiveFailureService | Failure prediction | /api/ai/predict/failure |
| DataQualityScoreService | Data quality scoring | /api/ai/quality/score |
| IntelligentRoutingService | Optimal routing | /api/ai/routing/optimal |

### Frontend Components
| Component | Purpose |
|-----------|---------|
| ai.service.ts | Backend communication for all features |
| ai.types.ts | All TypeScript interfaces |
| ai-chat.component | Chat panel UI |
| ai-dashboard-widget | Dashboard insights |
| ai-status-indicator | Header status |
| ai-features-panel | All AI features UI |

### All DTOs Created
- AiChatRequest, AiChatResponse
- AiSummarizeRequest, AiSummarizeResponse
- NaturalLanguageSearchRequest, NaturalLanguageSearchResponse
- AlertTriageRequest, AlertTriageResponse
- SessionRecommendationRequest, SessionRecommendationResponse
- AnomalyDetectionRequest, AnomalyDetectionResponse
- RootCauseAnalysisRequest, RootCauseAnalysisResponse
- DailySummaryRequest, DailySummaryResponse
- PredictiveFailureRequest, PredictiveFailureResponse
- DataQualityScoreRequest, DataQualityScoreResponse
- IntelligentRoutingRequest, IntelligentRoutingResponse
- AiPromptRequest, AiSearchResult

---

## 12. Appendix: Environment Variables

```bash
# Required
ANTHROPIC_API_KEY=sk-ant-xxxxx

# Optional
AI_ENABLED=true
AI_CHAT_ENABLED=true
AI_SUMMARIZE_ENABLED=true
AI_SEARCH_ENABLED=false
AI_MAX_REQUESTS_PER_HOUR=200
```

---

## 12. Appendix: Environment Variables

### Quick Start - Free Options

**Option 1: Groq (Recommended for FREE testing)**
```bash
# Sign up at https://console.groq.com (free tier)
export AI_ENABLED=true
export AI_PRESET=groq
export AI_API_KEY=gsk_your_key_here
```

**Option 2: Ollama (Local, FREE forever)**
```bash
# Install from https://ollama.com, then:
# Pull a model: ollama pull llama3
export AI_ENABLED=true
export AI_PRESET=ollama
# No API key needed - runs locally
```

**Option 3: Gemini (Google, free tier)**
```bash
# Sign up at https://aistudio.google.com (generous free tier)
export AI_ENABLED=true
export AI_PRESET=gemini
export AI_API_KEY=your_gemini_key
```

**Option 4: Anthropic Claude (Paid but best quality)**
```bash
# Sign up at https://console.anthropic.com
export AI_ENABLED=true
export AI_PRESET=anthropic
export AI_API_KEY=sk-ant-your_key_here
```

### All Available Presets

| Preset | Provider | API Key Required | Cost | Speed |
|--------|----------|------------------|------|-------|
| `anthropic` | Claude | Yes | Paid | Medium |
| `groq` | Groq (Llama) | Yes | FREE | Fast |
| `ollama` | Local models | No | FREE | Depends on hardware |
| `gemini` | Google Gemini | Yes | FREE tier | Medium |
| `openai` | OpenAI | Yes | Paid | Fast |

### Environment Variables Reference

```bash
# Core settings
AI_ENABLED=true                    # Enable AI features
AI_PRESET=groq                     # Provider preset (anthropic, groq, ollama, gemini)

# Credentials (not needed for ollama)
AI_API_KEY=your_api_key_here

# Optional overrides (auto-set by preset)
AI_PROVIDER=openai                 # Manual provider override
AI_MODEL=llama-3.1-70b-versatile   # Model override
AI_BASE_URL=https://api.groq.com/...  # Base URL override

# Performance tuning
AI_MAX_TOKENS=1024
AI_TEMPERATURE=0.7
AI_TIMEOUT_MS=30000
```

---

*Document prepared by: AI Architecture Team*  
*Implementation Status: Phase 1 Complete (2026-06-02)*  
*Updated: Added free preset options (2026-06-02)*