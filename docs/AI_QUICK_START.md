# AI Integration Quick Start Guide

**Version:** 1.0  
**Date:** 2026-06-02  
**Status:** Production Ready

---

## Quick Setup (5 minutes)

### 1. Choose Your AI Provider

| Provider | Cost | Speed | Setup |
|----------|------|-------|-------|
| **Groq** (Recommended) | FREE | Fast | Get key → Set env vars → Done |
| **Gemini** | FREE tier | Medium | Get key → Set env vars → Done |
| **Ollama** | FREE | Local | Install → Pull model → Done |
| **Claude** | Paid | Best | Get key → Set env vars → Done |

### 2. Set Environment Variables

```bash
# Groq (Easiest - FREE)
export AI_ENABLED=true
export AI_PRESET=groq
export AI_API_KEY=gsk_your_key_here

# OR Gemini (Generous free tier)
export AI_ENABLED=true
export AI_PRESET=gemini
export AI_API_KEY=your_gemini_key

# OR Ollama (Local, zero cost forever)
export AI_ENABLED=true
export AI_PRESET=ollama
# No API key needed - runs on localhost
```

### 3. Start Backend

```bash
cd backend
mvn spring-boot:run
```

### 4. Open App

Navigate to `http://localhost:4200` (or your configured port)

You'll see:
- **AI Status indicator** in the header (shows online/offline)
- **AI chat button** in bottom-right corner
- **AI Insights widget** on the dashboard (when enabled)

---

## Files Created

### Backend (Java/Spring Boot)
```
backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/
├── config/
│   └── AiProperties.java                    # Configuration with presets
├── service/ai/
│   ├── AiGatewayService.java                # LLM API gateway
│   ├── AiChatService.java                   # Chat handling
│   └── AiSummarizeService.java              # Alert summarization
├── controller/
│   └── AiController.java                    # REST endpoints
└── dto/ai/
    ├── AiChatRequest.java
    ├── AiChatResponse.java
    ├── AiSummarizeRequest.java
    └── AiSummarizeResponse.java
```

### Frontend (Angular)
```
frontend/src/app/ai/
├── ai.types.ts                              # TypeScript interfaces
├── ai.service.ts                            # HTTP service
├── ai-chat.component.ts/html/scss           # Chat panel UI
├── ai-dashboard-widget.component.ts/html/scss  # Dashboard widget
└── ai-status-indicator.component.ts         # Status in header
```

### Tests
```
backend/src/test/java/com/onsemi/cim/apps/exensio/exensioreload/
├── config/
│   └── AiPropertiesTest.java                # Configuration tests
└── service/ai/
    └── AiGatewayServiceTest.java            # Gateway tests
```

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/ai/status` | Get AI service status |
| GET | `/api/ai/health` | Health check |
| POST | `/api/ai/chat` | Send chat message |
| POST | `/api/ai/summarize/alerts` | Summarize alerts |
| DELETE | `/api/ai/conversation/{id}` | Clear conversation |

### Example: Chat Request

```bash
curl -X POST http://localhost:8004/api/ai/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Show me active staging sessions",
    "conversationId": "user123-session1"
  }'
```

### Example: Alert Summarization

```bash
curl -X POST http://localhost:8004/api/ai/summarize/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "alerts": [
      {"sender": "SENDER_A", "error": "Timeout", "severity": "HIGH"},
      {"sender": "SENDER_B", "error": "Auth failed", "severity": "MEDIUM"}
    ]
  }'
```

---

## Configuration Reference

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `AI_ENABLED` | Yes | false | Enable AI features |
| `AI_PRESET` | Yes | anthropic | Provider preset |
| `AI_API_KEY` | Most | - | API key (not needed for Ollama) |
| `AI_PROVIDER` | No | auto | Manual provider override |
| `AI_MODEL` | No | auto | Model override |
| `AI_BASE_URL` | No | auto | API base URL override |
| `AI_MAX_TOKENS` | No | 1024 | Max response tokens |
| `AI_TEMPERATURE` | No | 0.7 | Response creativity (0-1) |
| `AI_TIMEOUT_MS` | No | 30000 | Request timeout |

### Presets Available

| Preset | Provider | Model | API URL |
|--------|----------|-------|---------|
| `anthropic` | Claude | claude-sonnet-4-20250514 | api.anthropic.com |
| `groq` | Groq | llama-3.3-70b-versatile | api.groq.com |
| `ollama` | Local | llama3 | localhost:11434 |
| `gemini` | Gemini | gemini-1.5-flash | generativelanguage.googleapis.com |
| `openai` | OpenAI | gpt-4o-mini | api.openai.com |

---

## Troubleshooting

### "AI is not configured"
```bash
# Check environment variables are set
echo $AI_ENABLED
echo $AI_API_KEY
echo $AI_PRESET
```

### "API error: 401 Unauthorized"
```bash
# Verify your API key is correct
# Get a new key from your provider's console
```

### "Connection timeout"
```bash
# For Ollama, make sure it's running
ollama serve

# Check if model is downloaded
ollama list
ollama pull llama3
```

### Backend won't start
```bash
# Check application.yml has ai config
# Validate properties in AiProperties.java
cd backend && mvn clean compile
```

---

## Next Steps

1. **Add to Dashboard:** Place `<app-ai-dashboard-widget>` component on your dashboard
2. **Add to Header:** Place `<app-ai-status-indicator>` for status display
3. **Configure Rate Limits:** Adjust `rate-limit-per-minute` based on your API tier
4. **Add to Navigation:** Link `/ai/chat` route to main navigation

---

## Architecture

```
┌─────────────────────────────────────────────┐
│              Frontend (Angular)              │
│                                             │
│   ┌─────────────┐    ┌─────────────────┐   │
│   │ Chat Panel  │    │ Dashboard       │   │
│   │ (floating)  │    │ Widget          │   │
│   └──────┬──────┘    └────────┬────────┘   │
└──────────┼────────────────────┼────────────┘
           │                    │
           ▼                    ▼
┌─────────────────────────────────────────────┐
│            Backend (Spring Boot)             │
│                                             │
│   ┌─────────────────────────────────────┐   │
│   │         AiController                 │   │
│   │  /api/ai/chat, /api/ai/summarize     │   │
│   └──────────────┬──────────────────────┘   │
│                  │                          │
│   ┌──────────────┴──────────────────────┐   │
│   │       AiService Layer                │   │
│   │  AiChatService, AiSummarizeService   │   │
│   └──────────────┬──────────────────────┘   │
│                  │                          │
│   ┌──────────────┴──────────────────────┐   │
│   │       AiGatewayService               │   │
│   │  HTTP client, caching, error handling│   │
│   └──────────────┬──────────────────────┘   │
│                  │                          │
└──────────────────┼──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│         External AI Providers                │
│                                             │
│   Groq / Claude / Gemini / Ollama           │
└─────────────────────────────────────────────┘
```

---

## Support

For issues or questions:
1. Check the API key is valid
2. Verify network connectivity to AI provider
3. Check backend logs: `tail -f logs/exensioreload.log`
4. Verify application.yml configuration

---

*Document updated: 2026-06-02*