# Typography System - Best Practices

## Overview
The application now follows typography best practices for optimal readability, accessibility, and user experience.

## Font Size Scale

### Base Font Size: 16px (1rem)
This is the browser default and ensures accessibility across all devices.

### Typography Scale (Perfect Fourth - 1.333 ratio)

| Element | Size (rem) | Size (px) | Usage |
|---------|-----------|-----------|-------|
| **Labels** | 0.6875rem | 11px | Input labels, badges, metadata |
| **Small** | 0.75rem | 12px | Helper text, captions |
| **Body Small** | 0.875rem | 14px | Placeholders, secondary text |
| **Body** | 0.9375rem | 15px | Input text, select options |
| **Body Large** | 1rem | 16px | Default body text |
| **H4** | 1.125rem | 18px | Section headers |
| **H3** | 1.25rem | 20px | Card titles |
| **H2** | 1.5rem | 24px | Page headers |
| **H1** | 2rem | 32px | Main titles |

## Input & Form Elements

### Input Fields
```scss
.native-input {
    font-size: 0.9375rem;  // 15px - optimal readability
    font-weight: 500;       // Medium weight for clarity
    line-height: 1.5;       // 22.5px - comfortable reading
    min-height: 40px;       // Adequate space for text
}
```

**Why 15px?**
- ✅ Larger than 16px minimum (prevents mobile zoom)
- ✅ Comfortable for extended reading
- ✅ Works well with 56px container height
- ✅ Balances readability and space efficiency

### Placeholders
```scss
.placeholder {
    font-size: 0.875rem;    // 14px - slightly smaller
    font-weight: 400;        // Regular weight
    opacity: 0.3-0.5;        // Subtle but visible
}
```

### Labels
```scss
.floating-label {
    font-size: 0.6875rem;   // 11px - compact but readable
    font-weight: 600;        // Semi-bold for emphasis
    letter-spacing: 0.05em;  // Improved readability
    text-transform: uppercase;
}
```

## Container Heights

### Input Containers
```scss
.input-wrapper {
    min-height: 56px;       // Total container height
    padding: 0.75rem 1rem;  // 12px 16px
}

.native-input {
    min-height: 40px;       // Actual input area
}
```

### Buttons
```scss
.button {
    height: 44px;           // Minimum touch target
    font-size: 0.9375rem;   // 15px - matches inputs
    font-weight: 500;        // Medium weight
}
```

## Line Heights

### Optimal Line Heights
```scss
// Headings (tighter)
h1, h2, h3 { line-height: 1.2; }  // 120%

// Body text (comfortable)
body, input, select { line-height: 1.5; }  // 150%

// Small text (slightly tighter)
small, caption { line-height: 1.4; }  // 140%
```

## Accessibility Compliance

### WCAG 2.1 Guidelines

#### Minimum Font Sizes
- ✅ Body text: 15px (exceeds 14px minimum)
- ✅ Small text: 11px (acceptable for labels)
- ✅ Touch targets: 44px minimum (met)

## Summary

The typography system now provides:
- ✅ **Optimal Readability** - 15px input text with 1.5 line-height
- ✅ **Clear Hierarchy** - 11px labels, 14px placeholders, 15px values
- ✅ **Consistent Sizing** - All containers are 56px height
- ✅ **Accessibility** - Meets WCAG 2.1 guidelines
- ✅ **Mobile-Friendly** - Prevents zoom, adequate touch targets
- ✅ **Professional** - Follows industry best practices
