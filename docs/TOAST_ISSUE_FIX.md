# Toast Notification Issue - Troubleshooting Guide

## Issue Description
Toast notification appears when clicking "Reconnect Live" but cannot be closed or appears to hang.

## Root Causes

### 1. Toast Auto-Dismiss Timing
**Problem**: The reconnect toast uses default 5-second duration, but if reconnection takes longer, it may appear stuck.

**Fix Applied**: Reduced duration to 3 seconds in `reconnectMonitoring()` method.

### 2. Close Button Not Working
**Possible Causes**:
- Z-index conflicts with other UI elements
- Click event not propagating
- Toast positioned outside clickable area

## Quick Fixes to Try

### Option 1: Clear All Toasts (Keyboard Shortcut)
Add this to your browser console:
```javascript
// Clear all toasts immediately
window.angular?.getComponent(document.querySelector('app-root'))?.toastService?.clear();
```

### Option 2: Force Dismiss via DevTools
1. Open browser DevTools (F12)
2. Go to Console tab
3. Run: `document.querySelector('.toast-close')?.click()`

### Option 3: Refresh the Page
If toast is truly stuck, refresh the browser page. The monitoring will reconnect automatically.

## Permanent Fixes

### Fix 1: Add Global Toast Clear Button
Add a "Clear Notifications" button to the UI that calls `toastService.clear()`.

### Fix 2: Improve Toast Z-Index
Ensure toast container has highest z-index:
```css
.toast-container {
  z-index: 99999; /* Increased from 9999 */
}
```

### Fix 3: Add Escape Key Handler
Allow users to dismiss toasts with ESC key:
```typescript
@HostListener('document:keydown.escape')
onEscapeKey() {
  this.toastService.clear();
}
```

### Fix 4: Shorter Auto-Dismiss for Info Toasts
Info toasts (like reconnect messages) should auto-dismiss faster:
```typescript
this.toast.info('Attempting to reconnect...', 2000); // 2 seconds instead of 5
```

## Debugging Steps

### Check if Toast is Actually Stuck
1. Open DevTools Console
2. Run: `document.querySelectorAll('.toast').length`
3. If returns > 0, toasts exist
4. Run: `document.querySelector('.toast-close')`
5. If returns null, close button is missing

### Check Toast Service State
1. In Console, run:
```javascript
// Get Angular component
const app = document.querySelector('app-root');
const component = ng.getComponent(app);
console.log('Active toasts:', component.toastService.toasts());
```

### Check for JavaScript Errors
1. Open DevTools Console
2. Look for any red error messages
3. Common issues:
   - "Cannot read property 'dismiss' of undefined"
   - "Zone.js error"
   - Click event not firing

## Prevention

### Best Practices for Toast Usage

1. **Always specify duration for transient messages**:
```typescript
// Good
this.toast.info('Reconnecting...', 3000);

// Bad (uses default 5000ms)
this.toast.info('Reconnecting...');
```

2. **Use appropriate durations by message type**:
- Success: 3000ms (quick confirmation)
- Info: 3000ms (transient status)
- Warning: 5000ms (needs attention)
- Error: 7000ms (critical, needs reading)

3. **Provide alternative feedback for long operations**:
```typescript
// Instead of long-lived toast
this.toast.info('Reconnecting...', 3000);

// Use UI state indicator
this.isReconnecting.set(true);
// ... reconnect logic ...
this.isReconnecting.set(false);
```

4. **Clear toasts before showing new ones for same action**:
```typescript
reconnectMonitoring() {
  // Clear any existing reconnect toasts
  this.toastService.clear();
  
  this.toast.info('Attempting to reconnect...', 3000);
  this.stagingSession.connectToSession(sessionId);
}
```

## Current Implementation Status

✅ **Fixed**: Reconnect toast now auto-dismisses after 3 seconds
✅ **Working**: Close button exists and should be clickable
⚠️ **Needs Testing**: Verify close button works in production build
⚠️ **Enhancement**: Consider adding ESC key handler for better UX

## If Issue Persists

1. **Check browser console for errors**
2. **Try different browser** (Chrome vs Firefox vs Edge)
3. **Clear browser cache** and reload
4. **Check if running in production mode** (ng build --prod)
5. **Verify Angular zone is running** (toast updates require zone)

## Emergency Workaround

If toasts are completely broken, you can disable them temporarily:

```typescript
// In toast.service.ts, modify show() method:
private show(type: Toast['type'], message: string, duration: number = 5000) {
  console.log(`[TOAST] ${type}: ${message}`); // Log instead of showing
  return; // Skip toast display
  
  // ... rest of method
}
```

This will log toast messages to console instead of displaying them, allowing you to continue working while investigating the root cause.
