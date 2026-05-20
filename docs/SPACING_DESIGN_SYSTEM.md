# Spacing Design System - 8px Grid

## Overview
The application now follows the **8px grid system**, a design best practice used by Material Design, Apple HIG, and most modern design systems.

## Why 8px Grid?

### Benefits
1. **Visual Consistency** - Creates harmonious spacing throughout the UI
2. **Scalability** - Works well across different screen sizes
3. **Developer Efficiency** - Easy to remember and implement
4. **Design-Dev Alignment** - Matches industry standards
5. **Accessibility** - Provides adequate touch targets (44px = 5.5 × 8px)

### Industry Adoption
- ✅ Material Design (Google)
- ✅ Human Interface Guidelines (Apple)
- ✅ Fluent Design (Microsoft)
- ✅ Carbon Design (IBM)
- ✅ Ant Design (Alibaba)

## Spacing Scale

### Base Unit: 8px (0.5rem)

| Value | rem | px | Usage |
|-------|-----|----|----|
| 0.5rem | 0.5 | 8px | Tight spacing, small gaps |
| 1rem | 1.0 | 16px | Standard spacing, default gap |
| 1.5rem | 1.5 | 24px | Medium spacing, section padding |
| 2rem | 2.0 | 32px | Large spacing, major sections |
| 2.5rem | 2.5 | 40px | Extra large spacing |
| 3rem | 3.0 | 48px | Maximum spacing |

### Applied in Stepper Component

#### Config Card
```scss
.config-card-unified {
    padding: 1.5rem; // 24px - comfortable breathing room
    gap: 1rem;       // 16px - standard section spacing
}
```

#### Lot/Wafer Pairs
```scss
.pairs-list-compact {
    gap: 0.5rem;     // 8px - tight spacing between rows
    padding: 0.5rem; // 8px - minimal container padding
}

.pair-row-compact {
    gap: 1rem;       // 16px - standard gap between inputs
    padding: 1rem;   // 16px - comfortable internal padding
}
```

#### Section Headers
```scss
.section-header-inline {
    margin-bottom: 0.5rem; // 8px - tight spacing to content
}
```

#### Buttons
```scss
.add-pair-btn {
    margin-top: 0.5rem; // 8px - minimal separation
    height: 44px;        // 44px - accessible touch target
}

.delete-btn-compact {
    width: 44px;         // 44px - square touch target
    height: 44px;
}
```

## Touch Target Sizes

### Minimum Sizes (WCAG 2.1 Level AAA)
- **Minimum**: 44px × 44px
- **Comfortable**: 48px × 48px
- **Large**: 56px × 56px

### Applied Sizes
```scss
// All interactive elements follow 44px minimum
input { height: 44px; }
button { height: 44px; min-width: 44px; }
select { height: 44px; }
```

## Responsive Spacing

### Desktop (>768px)
```scss
.config-card-unified {
    padding: 1.5rem; // 24px
}

.pair-row-compact {
    padding: 1rem;   // 16px
    gap: 1rem;       // 16px
}
```

### Tablet (768px)
```scss
.config-card-unified {
    padding: 1rem;   // 16px - reduced
}

.pair-row-compact {
    padding: 1rem;   // 16px - maintained
    gap: 0.5rem;     // 8px - tighter
}
```

### Mobile (<480px)
```scss
.config-card-unified {
    padding: 1rem;   // 16px
}

.pair-row-compact {
    padding: 1rem;   // 16px
    gap: 1rem;       // 16px - maintained for readability
}
```

## Visual Rhythm

### Vertical Spacing Hierarchy
```
Section Title
    ↓ 8px (0.5rem)
Section Content
    ↓ 16px (1rem)
Next Section Title
    ↓ 8px (0.5rem)
Next Section Content
```

### Horizontal Spacing
```
[Input 1] ←16px→ [Input 2] ←16px→ [Button]
```

## Common Patterns

### Form Fields
```scss
.form-row {
    gap: 1rem; // 16px between fields
}
```

### Cards
```scss
.card {
    padding: 1.5rem; // 24px internal padding
    gap: 1rem;       // 16px between elements
}
```

### Lists
```scss
.list {
    gap: 0.5rem; // 8px between items (tight)
    // OR
    gap: 1rem;   // 16px between items (comfortable)
}
```

### Buttons
```scss
.button-group {
    gap: 1rem; // 16px between buttons
}
```

## Anti-Patterns (Avoid)

### ❌ Off-Grid Values
```scss
// BAD
padding: 0.75rem;  // 12px - not on 8px grid
gap: 0.625rem;     // 10px - not on 8px grid
margin: 1.25rem;   // 20px - not on 8px grid

// GOOD
padding: 1rem;     // 16px ✅
gap: 0.5rem;       // 8px ✅
margin: 1.5rem;    // 24px ✅
```

### ❌ Inconsistent Spacing
```scss
// BAD - mixing different values
.section-1 { gap: 0.75rem; }
.section-2 { gap: 1rem; }
.section-3 { gap: 0.5rem; }

// GOOD - consistent pattern
.section-1 { gap: 1rem; }
.section-2 { gap: 1rem; }
.section-3 { gap: 1rem; }
```

### ❌ Too Tight Spacing
```scss
// BAD - hard to tap on mobile
button {
    width: 32px;
    height: 32px;
}

// GOOD - accessible touch target
button {
    width: 44px;
    height: 44px;
}
```

## Implementation Checklist

- [x] All spacing values use 8px grid (0.5rem, 1rem, 1.5rem, 2rem)
- [x] Touch targets are minimum 44px × 44px
- [x] Consistent gap values across similar components
- [x] Responsive spacing adjusts appropriately
- [x] Visual hierarchy is clear through spacing
- [x] No arbitrary spacing values (0.75rem, 1.25rem, etc.)

## Tools & Resources

### Design Tools
- **Figma**: Enable 8px grid in View → Layout Grids
- **Sketch**: Set grid to 8px in View → Canvas → Grid Settings
- **Adobe XD**: Use 8px grid overlay

### Browser DevTools
```css
/* Add this to visualize 8px grid */
body::before {
    content: '';
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background-image: 
        repeating-linear-gradient(0deg, rgba(255,0,0,0.1) 0px, transparent 1px, transparent 8px),
        repeating-linear-gradient(90deg, rgba(255,0,0,0.1) 0px, transparent 1px, transparent 8px);
    pointer-events: none;
    z-index: 9999;
}
```

### CSS Variables (Future Enhancement)
```scss
// Define spacing scale as CSS variables
:root {
    --space-xs: 0.5rem;  // 8px
    --space-sm: 1rem;    // 16px
    --space-md: 1.5rem;  // 24px
    --space-lg: 2rem;    // 32px
    --space-xl: 2.5rem;  // 40px
    --space-2xl: 3rem;   // 48px
}

// Usage
.card {
    padding: var(--space-md);
    gap: var(--space-sm);
}
```

## Before & After Comparison

### Before (Inconsistent)
```scss
.config-card { padding: 1.25rem; }  // 20px ❌
.pairs-list { gap: 0.75rem; }       // 12px ❌
.pair-row { padding: 0.5rem; }      // 8px ✅
```

### After (8px Grid)
```scss
.config-card { padding: 1.5rem; }   // 24px ✅
.pairs-list { gap: 0.5rem; }        // 8px ✅
.pair-row { padding: 1rem; }        // 16px ✅
```

## Summary

The spacing system now follows industry best practices:
- ✅ 8px grid system throughout
- ✅ Consistent spacing scale
- ✅ Accessible touch targets (44px minimum)
- ✅ Clear visual hierarchy
- ✅ Responsive spacing adjustments
- ✅ Professional, polished appearance

This creates a more harmonious, predictable, and accessible user interface that aligns with modern design standards.
