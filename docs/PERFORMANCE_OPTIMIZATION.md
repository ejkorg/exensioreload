# Frontend Performance Issues - Diagnosis & Solutions

## Problem: High CPU/RAM Usage During Build

### Common Causes

1. **Angular 21 Production Build** - Modern Angular uses esbuild which is CPU-intensive
2. **Excessive Console Logging** - Debug logs in effects/computed signals
3. **Large Dependencies** - ApexCharts, ECharts add significant bundle size
4. **Effect() Infinite Loops** - Reactive effects triggering themselves
5. **Memory Leaks** - Unsubscribed observables

## Current Issues Found

### 1. Excessive Console Logging in Effect
**Location**: `stepper.component.ts` - `setupReactiveSenderLookup()`

**Problem**:
```typescript
effect(() => {
    console.log('[Sender Lookup Effect] Filter state:', {...}); // Logs on EVERY change
    console.log('[Sender Lookup Effect] Should trigger:', shouldTrigger);
    console.log('[Sender Lookup Effect] Triggering sender lookup...');
});
```

**Impact**:
- Console.log is expensive (serializes objects)
- Triggers on every signal change
- Can cause memory pressure
- Slows down change detection

**Solution**: Remove or conditionally enable debug logs

### 2. Angular 21 Build Performance
**Current**: Using `@angular/build:application` (esbuild-based)

**Characteristics**:
- First build is slow (compiles everything)
- Subsequent builds are faster (incremental)
- High CPU usage is normal for production builds
- Memory usage: 1-2GB is typical

## Solutions

### Immediate Fixes

#### 1. Remove Debug Console Logs
```typescript
// BEFORE (BAD)
effect(() => {
    console.log('[Sender Lookup Effect] Filter state:', {...});
    // ... more logs
});

// AFTER (GOOD)
effect(() => {
    // Only log in development mode
    if (!environment.production) {
        console.log('[Sender Lookup Effect] Filter state:', {...});
    }
});

// BEST: Use environment flag
effect(() => {
    if (environment.showDebugLogs) {
        console.log('[Sender Lookup Effect] Filter state:', {...});
    }
});
```

#### 2. Optimize Build Command
```json
// package.json
{
  "scripts": {
    "build:prod:deploy": "node --max-old-space-size=4096 ./node_modules/@angular/cli/bin/ng build --configuration production --base-href /exensioreload/ --deploy-url /exensioreload/"
  }
}
```

**Explanation**:
- `--max-old-space-size=4096` - Allocates 4GB RAM to Node.js
- Prevents out-of-memory errors
- Allows faster builds

#### 3. Enable Build Cache
```json
// angular.json
{
  "cli": {
    "cache": {
      "enabled": true,
      "path": ".angular/cache",
      "environment": "all"
    }
  }
}
```

### Long-Term Optimizations

#### 1. Lazy Load Heavy Dependencies
```typescript
// BEFORE: Import at top level
import { ApexCharts } from 'apexcharts';

// AFTER: Dynamic import
async loadChart() {
    const { ApexCharts } = await import('apexcharts');
    // Use ApexCharts
}
```

#### 2. Use OnPush Change Detection
```typescript
@Component({
    selector: 'app-stepper',
    changeDetection: ChangeDetectionStrategy.OnPush, // Add this
    // ...
})
```

**Benefits**:
- Reduces change detection cycles
- Improves runtime performance
- Lower CPU usage

#### 3. Unsubscribe from Observables
```typescript
// BEFORE (Memory Leak)
ngOnInit() {
    this.backend.getData().subscribe(data => {
        // ...
    });
}

// AFTER (Proper Cleanup)
private destroy$ = new Subject<void>();

ngOnInit() {
    this.backend.getData()
        .pipe(takeUntil(this.destroy$))
        .subscribe(data => {
            // ...
        });
}

ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
}
```

#### 4. Optimize Bundle Size
```bash
# Analyze bundle
npm run build:prod -- --stats-json
npx webpack-bundle-analyzer dist/exensio-reload/stats.json
```

**Common Optimizations**:
- Remove unused dependencies
- Use tree-shakeable imports
- Lazy load routes
- Compress assets

## Build Performance Benchmarks

### Expected Build Times (Production)
- **First build**: 2-5 minutes (normal)
- **Incremental build**: 30-60 seconds
- **Development build**: 10-30 seconds

### Expected Resource Usage
- **CPU**: 80-100% during build (normal)
- **RAM**: 1-2GB for Angular 21
- **Disk**: 500MB-1GB for node_modules

### When to Worry
❌ Build takes >10 minutes
❌ RAM usage >4GB
❌ Build fails with out-of-memory
❌ CPU stays at 100% after build completes

## Quick Fixes to Try Now

### 1. Clear Build Cache
```bash
# Windows
rmdir /s /q .angular
rmdir /s /q node_modules\.cache
rmdir /s /q dist

# Rebuild
npm run build:prod:deploy
```

### 2. Update Node.js
```bash
# Check version
node --version

# Should be v18.x or v20.x for Angular 21
# Download from: https://nodejs.org/
```

### 3. Increase Node Memory
```bash
# Windows (PowerShell)
$env:NODE_OPTIONS="--max-old-space-size=4096"
npm run build:prod:deploy
```

### 4. Disable Source Maps (Production)
```json
// angular.json - production config
{
  "sourceMap": false,  // Faster builds
  "optimization": true,
  "buildOptimizer": true
}
```

## Monitoring Build Performance

### Add Build Timing
```json
// package.json
{
  "scripts": {
    "build:prod:deploy": "echo Build started at %time% && ng build --configuration production --base-href /exensioreload/ --deploy-url /exensioreload/ && echo Build completed at %time%"
  }
}
```

### Check Memory Usage
```bash
# Windows Task Manager
# Look for "Node.js" process during build
# Normal: 1-2GB
# High: >3GB
```

## Recommended Configuration

### angular.json (Optimized)
```json
{
  "cli": {
    "cache": {
      "enabled": true
    }
  },
  "projects": {
    "exensio-reload": {
      "architect": {
        "build": {
          "options": {
            "optimization": true,
            "sourceMap": false,
            "namedChunks": false,
            "extractLicenses": true,
            "vendorChunk": true,
            "buildOptimizer": true
          }
        }
      }
    }
  }
}
```

### package.json (Optimized)
```json
{
  "scripts": {
    "build:prod:deploy": "node --max-old-space-size=4096 ./node_modules/@angular/cli/bin/ng build --configuration production --base-href /exensioreload/ --deploy-url /exensioreload/"
  }
}
```

## Summary

**Normal Behavior**:
- High CPU during build (80-100%)
- 1-2GB RAM usage
- 2-5 minute first build

**Action Items**:
1. ✅ Remove excessive console.log statements
2. ✅ Increase Node.js memory limit
3. ✅ Enable build cache
4. ✅ Clear cache and rebuild
5. ⚠️ Monitor for memory leaks in runtime

**If Still Slow**:
- Check antivirus (exclude node_modules)
- Close other applications
- Use SSD (not HDD)
- Upgrade RAM (8GB minimum, 16GB recommended)
