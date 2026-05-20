# Rebuild and Test - SSE Fix

## Quick Commands

```bash
# 1. Rebuild backend
cd backend
mvn clean package -DskipTests

# 2. Restart your Spring Boot application
# (Stop and start it)

# 3. Clear browser cache
# Press Ctrl+Shift+Delete in browser
# Select "Cached images and files"
# Click "Clear data"

# 4. Refresh browser
# Press Ctrl+F5 (hard refresh)
```

## Test in Browser Console

### Test 1: Basic SSE (No Auth)
```javascript
const es = new EventSource('/exensioreload/api/stage/test-sse');
es.onopen = () => console.log('✅ SSE WORKS!');
es.onerror = (e) => console.error('❌ FAILED:', es.readyState);
es.addEventListener('TEST', (e) => console.log('📨', e.data));
```

### Test 2: Monitoring SSE (With Auth)
```javascript
const token = localStorage.getItem('token');
const sessionId = 'YOUR_SESSION_ID'; // Replace with actual
const es = new EventSource(`/exensioreload/api/stage/sessions/${sessionId}/monitor?token=${token}`);
es.onopen = () => console.log('✅ MONITORING WORKS!');
es.addEventListener('HEARTBEAT', (e) => console.log('💓', e.data));
```

## What Changed

1. **Backend headers** - Explicit SSE headers set
2. **Async events** - Events sent after response committed
3. **Test endpoint** - `/api/stage/test-sse` for debugging

## Expected Results

✅ Test SSE opens and receives TEST event
✅ Monitoring SSE opens and receives HEARTBEAT
✅ Files load and display in UI
✅ No stuck "Connecting..." spinner

## If It Still Doesn't Work

Check `FIX_SSE_FINAL.md` for detailed debugging steps.
