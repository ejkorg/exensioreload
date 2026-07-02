# Design Document: Bulk Lot Input

## Overview

This document describes the design for adding bulk lot input functionality to the Exensio Reload discovery stepper. The feature allows users to paste or upload lists of lot identifiers in Step 1 (Configuration), significantly reducing the manual effort required when working with large datasets.

The design follows the existing Angular standalone component architecture and integrates seamlessly with the current `StepperComponent` and its `lotWaferPairs` signal-based state management.

## Architecture

The bulk lot input feature follows a modal-based pattern consistent with existing dialogs in the application (e.g., `ConfirmStageAllDialogComponent`, `DuplicateWarningDialogComponent`). The architecture consists of:

1. **Entry Point**: A "Bulk Add Lots" button in the Lot/Wafer Filters section of `StepperComponent`
2. **Modal Component**: A new `BulkLotInputDialogComponent` for input collection and validation
3. **Service Integration**: Leverages existing `GlassDialogService` for modal management
4. **State Management**: Updates the existing `lotWaferPairs` signal in `StepperComponent`

### Component Hierarchy

```
StepperComponent
├── [Existing lot/wafer pair inputs]
├── "Bulk Add Lots" button (new)
└── BulkLotInputDialogComponent (new modal)
    ├── Textarea for pasting lots
    ├── File upload button
    ├── Validation feedback display
    └── Action buttons (Add Lots / Cancel)
```

## Components and Interfaces

### BulkLotInputDialogComponent

**Purpose**: Provides a modal dialog for users to paste or upload bulk lot identifiers.

**Template Structure**:

```html
<div class="bulk-lot-dialog glass-modal">
  <header>
    <h2>Bulk Add Lots</h2>
    <button (click)="onCancel()">✕</button>
  </header>

  <section class="input-section">
    <label>Paste or upload lot identifiers</label>
    <textarea
      [(ngModel)]="rawInput"
      (input)="onInputChange()"
      placeholder="Paste lot IDs (comma, newline, or semicolon separated)..."
    ></textarea>

    <button (click)="triggerFileUpload()">
      <app-glass-icon name="upload"></app-glass-icon>
      Upload File
    </button>
    <input #fileInput type="file" accept=".txt,.csv" (change)="onFileSelected($event)" />
  </section>

  <section class="validation-section">
    <div class="stats">
      <span>Valid: {{ validCount }}</span>
      <span>Invalid: {{ invalidCount }}</span>
      <span>Duplicates: {{ duplicateCount }}</span>
    </div>
    <div class="warnings" *ngIf="warnings.length">
      <!-- Display validation warnings -->
    </div>
  </section>

  <footer class="actions">
    <app-glass-button variant="secondary" (clicked)="onCancel()">Cancel</app-glass-button>
    <app-glass-button variant="primary" (clicked)="onAddLots()" [disabled]="validCount === 0">
      Add {{ validCount }} Lot{{ validCount === 1 ? '' : 's' }}
    </app-glass-button>
  </footer>
</div>
```

**Component Class**:

```typescript
export interface BulkLotInputDialogData {
  existingLots: string[]; // For duplicate detection
}

export interface BulkLotInputDialogResult {
  lots: string[]; // Validated, trimmed lot identifiers
}

export interface ValidationResult {
  valid: string[];
  invalid: string[];
  duplicates: string[];
  warnings: string[];
}

@Component({
  selector: 'app-bulk-lot-input-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, GlassButtonComponent, GlassIconComponent],
  templateUrl: './bulk-lot-input-dialog.component.html',
  styleUrls: ['./bulk-lot-input-dialog.component.scss'],
})
export class BulkLotInputDialogComponent implements OnInit {
  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;

  data: BulkLotInputDialogData = inject(MAT_DIALOG_DATA);
  dialogRef = inject(MatDialogRef<BulkLotInputDialogComponent>);

  rawInput = signal<string>('');
  validCount = signal<number>(0);
  invalidCount = signal<number>(0);
  duplicateCount = signal<number>(0);
  warnings = signal<string[]>([]);

  private maxLots = 1000;

  ngOnInit(): void {
    // Focus textarea when modal opens
  }

  onInputChange(): void {
    const result = this.validateInput(this.rawInput());
    this.updateValidationState(result);
  }

  onFileSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (e) => {
      this.rawInput.set(e.target?.result as string);
      this.onInputChange();
    };
    reader.onerror = () => {
      // Display error toast
    };
    reader.readAsText(file);
  }

  triggerFileUpload(): void {
    this.fileInput.nativeElement.click();
  }

  validateInput(input: string): ValidationResult {
    // Parse and validate input (implementation in next section)
  }

  updateValidationState(result: ValidationResult): void {
    this.validCount.set(result.valid.length);
    this.invalidCount.set(result.invalid.length);
    this.duplicateCount.set(result.duplicates.length);
    this.warnings.set(result.warnings);
  }

  onAddLots(): void {
    const result = this.validateInput(this.rawInput());
    this.dialogRef.close({ lots: result.valid } as BulkLotInputDialogResult);
  }

  onCancel(): void {
    this.dialogRef.close();
  }
}
```

### StepperComponent Integration

**New Method**:

```typescript
onBulkAddLotsClick(): void {
  const existingLots = this.lotWaferPairs().map(p => p.lot);

  const dialogRef = this.glassDialog.open(BulkLotInputDialogComponent, {
    data: { existingLots } as BulkLotInputDialogData,
    width: '600px',
    maxHeight: '80vh'
  });

  dialogRef.afterClosed().subscribe((result: BulkLotInputDialogResult | undefined) => {
    if (result && result.lots.length > 0) {
      this.addBulkLots(result.lots);
    }
  });
}

private addBulkLots(lots: string[]): void {
  const newPairs = lots.map(lot => ({ lot, wafer: '' }));
  this.lotWaferPairs.update(existing => [...existing, ...newPairs]);

  // Show success toast
  this.toastService.show({
    message: `Added ${lots.length} lot${lots.length === 1 ? '' : 's'}`,
    type: 'success'
  });

  // Expand lot/wafer section if collapsed
  if (!this.showLotWaferFilters()) {
    this.showLotWaferFilters.set(true);
  }

  // Scroll to show new entries (optional, based on UX preference)
}
```

**Template Addition**:

```html
<!-- In the lot-wafer-section, add before pairs-list-compact -->
<button
  class="bulk-add-btn"
  (click)="onBulkAddLotsClick()"
  aria-label="Bulk add lots"
  title="Paste or upload multiple lots at once"
>
  <app-glass-icon name="upload_file" [size]="18"></app-glass-icon>
  <span>Bulk Add Lots</span>
</button>
```

## Data Models

### Input Parsing Result

```typescript
interface ParsedLotInput {
  lots: string[]; // All parsed lot identifiers (trimmed)
  originalInput: string; // Raw input for debugging
  delimiter: 'comma' | 'newline' | 'semicolon' | 'mixed';
}
```

### Validation Result

```typescript
interface ValidationResult {
  valid: string[]; // Valid, non-empty lot IDs
  invalid: string[]; // Empty or whitespace-only entries
  duplicates: string[]; // Lots that appear multiple times in input
  warnings: string[]; // Human-readable warning messages
}
```

### Dialog Data and Result

```typescript
interface BulkLotInputDialogData {
  existingLots: string[]; // Lots already in lotWaferPairs (for duplicate detection)
}

interface BulkLotInputDialogResult {
  lots: string[]; // Final validated list of lots to add
}
```

## Input Parsing Algorithm

The parsing algorithm handles multiple delimiters and edge cases:

```typescript
function parseLotInput(input: string): ParsedLotInput {
  // Step 1: Detect primary delimiter
  const commaCount = (input.match(/,/g) || []).length;
  const semicolonCount = (input.match(/;/g) || []).length;
  const newlineCount = (input.match(/\n/g) || []).length;

  let delimiter: ParsedLotInput['delimiter'];
  if (commaCount > semicolonCount && commaCount > newlineCount) {
    delimiter = 'comma';
  } else if (semicolonCount > commaCount && semicolonCount > newlineCount) {
    delimiter = 'semicolon';
  } else if (newlineCount > 0) {
    delimiter = 'newline';
  } else {
    delimiter = 'mixed';
  }

  // Step 2: Split by all common delimiters
  const lots = input
    .split(/[,;\n\r]+/) // Split by comma, semicolon, newline, carriage return
    .map((lot) => lot.trim()) // Trim whitespace
    .filter((lot) => lot.length > 0); // Remove empty entries

  return { lots, originalInput: input, delimiter };
}
```

## Validation Logic

```typescript
function validateLots(parsedInput: ParsedLotInput, existingLots: string[]): ValidationResult {
  const { lots } = parsedInput;
  const maxLots = 1000;

  const valid: string[] = [];
  const invalid: string[] = [];
  const duplicatesWithinInput = new Set<string>();
  const warnings: string[] = [];

  const seen = new Set<string>();
  const existingSet = new Set(existingLots);

  for (const lot of lots) {
    // Check if empty (should have been filtered, but double-check)
    if (lot.length === 0) {
      invalid.push(lot);
      continue;
    }

    // Check for duplicates within the input
    if (seen.has(lot)) {
      duplicatesWithinInput.add(lot);
    }
    seen.add(lot);

    valid.push(lot);
  }

  // Apply maximum limit
  if (valid.length > maxLots) {
    warnings.push(`Input exceeds ${maxLots} lot limit. Only the first ${maxLots} will be added.`);
    valid.splice(maxLots);
  }

  // Check for duplicates with existing lots
  const duplicatesWithExisting = valid.filter((lot) => existingSet.has(lot));
  if (duplicatesWithExisting.length > 0) {
    warnings.push(
      `${duplicatesWithExisting.length} lot(s) already exist in the filter list and will create duplicate entries.`,
    );
  }

  // Report duplicates within input
  if (duplicatesWithinInput.size > 0) {
    warnings.push(`${duplicatesWithinInput.size} duplicate lot(s) detected in your input.`);
  }

  return {
    valid,
    invalid,
    duplicates: Array.from(duplicatesWithinInput),
    warnings,
  };
}
```

## Error Handling

### File Upload Errors

- **File read failure**: Display toast notification with error message
- **Unsupported file type**: Show validation message in dialog
- **File too large** (>1MB): Show warning and attempt to read first 1MB

### Validation Errors

- **All lots invalid**: Disable "Add Lots" button, show prominent message
- **Partial validation failures**: Show warnings but allow user to proceed
- **Exceeds maximum limit**: Show warning, automatically truncate to 1000

### Integration Errors

- **Dialog service failure**: Fall back to browser alert (rare edge case)
- **State update failure**: Log error, show toast notification to user

## Testing Strategy

### Unit Tests

- Test `parseLotInput()` with various delimiter combinations
- Test `validateLots()` with edge cases (empty, duplicates, exceeding max)
- Test file reading success and failure scenarios
- Test component signal updates on input changes

### Integration Tests

- Test opening and closing the modal from StepperComponent
- Test data flow from dialog to parent component
- Test toast notifications after bulk add
- Test lot/wafer section expansion after bulk add

## Correctness Properties

A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.

### Property 1: Delimiter Parsing

_For any_ input string containing lot identifiers separated by commas, newlines, semicolons, or a mix of these delimiters, the parsing function should extract all non-empty lot identifiers correctly.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4**

### Property 2: Whitespace Trimming

_For any_ lot identifier with leading or trailing whitespace, parsing should produce a trimmed result with no leading or trailing whitespace.

**Validates: Requirements 2.5**

### Property 3: Empty Entry Filtering

_For any_ input containing empty entries (consecutive delimiters, blank lines), the parsed result should contain only non-empty lot identifiers.

**Validates: Requirements 2.6**

### Property 4: Case Preservation

_For any_ lot identifier with mixed or specific casing, parsing should preserve the exact character casing of the original identifier.

**Validates: Requirements 2.7**

### Property 5: File Content Parsing Equivalence

_For any_ text content, parsing file-uploaded content should produce the same result as parsing pasted content when the content strings are identical.

**Validates: Requirements 3.4**

### Property 6: Validation Correctness

_For any_ input string, the validation function should correctly identify valid lots (non-empty after trimming) and invalid lots (empty after trimming), with counts matching the actual number of valid and invalid entries.

**Validates: Requirements 4.2, 4.4**

### Property 7: Duplicate Detection

_For any_ input containing duplicate lot identifiers (case-sensitive comparison), the validation function should detect and count all duplicates accurately.

**Validates: Requirements 4.3, 4.4**

### Property 8: Append Preserves Existing

_For any_ existing lotWaferPairs array and any list of new lots, adding the new lots should append them to the array while preserving all existing entries in their original positions with unchanged values.

**Validates: Requirements 5.1, 5.3**

### Property 9: Wafer Field Initialization

_For any_ list of bulk-added lots, each new entry in the lotWaferPairs array should have an empty string as its wafer field value.

**Validates: Requirements 5.2**

### Property 10: Duplicate Lot Addition Allowed

_For any_ lot identifier that already exists in the lotWaferPairs array, adding it again via bulk input should create a new entry, resulting in two separate entries with the same lot identifier.

**Validates: Requirements 5.4**

### Property 11: Toast Count Accuracy

_For any_ successful bulk lot addition, the toast notification should display a count that exactly matches the number of lots added to the lotWaferPairs array.

**Validates: Requirements 6.1**

### Property 12: UI State Synchronization

_For any_ bulk lot addition, the visible lot/wafer pair list in the UI should reflect all newly added entries immediately after the operation completes.

**Validates: Requirements 6.2**

### Property 13: Maximum Limit Enforcement

_For any_ input exceeding 1000 lot identifiers, the system should truncate to exactly 1000 lots and display an appropriate warning message.

**Validates: Requirements 8.2**
