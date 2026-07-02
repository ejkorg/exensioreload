/**
 * Utilities for parsing bulk lot identifier input.
 * Handles comma, newline, semicolon, and mixed delimiters.
 */

export interface ParsedLotInput {
  /** All parsed lot identifiers, trimmed, with empty entries removed */
  lots: string[];
  /** Raw input string before parsing */
  originalInput: string;
  /** Primary delimiter detected in the input */
  delimiter: 'comma' | 'newline' | 'semicolon' | 'mixed' | 'none';
}

/**
 * Parses a raw text input string into individual lot identifiers.
 *
 * - Splits on commas, semicolons, newlines and carriage returns (any combination).
 * - Trims leading/trailing whitespace from every token (Requirement 2.5).
 * - Filters out empty entries (Requirement 2.6).
 * - Preserves original casing (Requirement 2.7).
 * - Detects the primary delimiter for informational purposes.
 *
 * @param input Raw text pasted or loaded from a file.
 * @returns ParsedLotInput with the extracted lot list and metadata.
 */
export function parseLotInput(input: string): ParsedLotInput {
  if (!input || !input.trim()) {
    return { lots: [], originalInput: input, delimiter: 'none' };
  }

  // Count occurrences of each delimiter to identify the primary one.
  const commaCount = (input.match(/,/g) ?? []).length;
  const semicolonCount = (input.match(/;/g) ?? []).length;
  const newlineCount = (input.match(/[\n\r]/g) ?? []).length;

  let delimiter: ParsedLotInput['delimiter'];
  if (commaCount === 0 && semicolonCount === 0 && newlineCount === 0) {
    // Single token, no delimiters present
    delimiter = 'none';
  } else if (commaCount >= semicolonCount && commaCount >= newlineCount) {
    delimiter = commaCount > 0 && (semicolonCount > 0 || newlineCount > 0) ? 'mixed' : 'comma';
  } else if (semicolonCount >= commaCount && semicolonCount >= newlineCount) {
    delimiter = semicolonCount > 0 && (commaCount > 0 || newlineCount > 0) ? 'mixed' : 'semicolon';
  } else {
    delimiter = newlineCount > 0 && (commaCount > 0 || semicolonCount > 0) ? 'mixed' : 'newline';
  }

  // Split by all supported delimiters simultaneously (Requirements 2.1–2.4).
  const lots = input
    .split(/[,;\n\r]+/) // split on comma, semicolon, newline, carriage return
    .map((token) => token.trim()) // trim surrounding whitespace (Requirement 2.5)
    .filter((token) => token.length > 0); // drop empty tokens (Requirement 2.6)
  // Casing is preserved because we never call toLowerCase/toUpperCase (Requirement 2.7).

  return { lots, originalInput: input, delimiter };
}

export interface ValidationResult {
  /** Non-empty lot identifiers that passed validation (may be truncated to maxLots). */
  valid: string[];
  /** Tokens that were empty after trimming (should be rare given parseLotInput filters these). */
  invalid: string[];
  /** Lot identifiers that appear more than once within the input. */
  duplicates: string[];
  /** Human-readable warning messages describing any issues found. */
  warnings: string[];
}

const MAX_LOTS = 1000;

/**
 * Validates a parsed lot input against business rules.
 *
 * - Detects empty entries (Requirement 4.1, 4.2).
 * - Detects duplicates within the input (Requirement 4.3, 4.4).
 * - Detects lots that already exist in the caller-supplied existingLots list (Requirement 4.3).
 * - Truncates to MAX_LOTS (1000) and emits a warning when exceeded (Requirement 8.1, 8.2).
 * - Generates human-readable warning messages for all issues found (Requirement 4.1).
 *
 * @param parsedInput  The result of parseLotInput().
 * @param existingLots Lot identifiers already present in the stepper (for duplicate-against-existing detection).
 * @returns ValidationResult with categorised entries and warning messages.
 */
export function validateLots(parsedInput: ParsedLotInput, existingLots: string[]): ValidationResult {
  const { lots } = parsedInput;

  const valid: string[] = [];
  const invalid: string[] = [];
  const seenInInput = new Set<string>();
  const duplicatesInInput = new Set<string>();
  const warnings: string[] = [];

  const existingSet = new Set(existingLots);

  for (const lot of lots) {
    // An empty string here would be a bug in parseLotInput, but guard anyway (Req 4.2).
    if (lot.length === 0) {
      invalid.push(lot);
      continue;
    }

    // Track duplicates within the pasted/uploaded input (Req 4.3).
    if (seenInInput.has(lot)) {
      duplicatesInInput.add(lot);
    }
    seenInInput.add(lot);

    valid.push(lot);
  }

  // Enforce maximum lot limit (Req 8.1, 8.2).
  if (valid.length > MAX_LOTS) {
    warnings.push(`Input exceeds the ${MAX_LOTS} lot limit. Only the first ${MAX_LOTS} will be added.`);
    valid.splice(MAX_LOTS);
  }

  // Warn about duplicates within the input (Req 4.3).
  if (duplicatesInInput.size > 0) {
    warnings.push(
      `${duplicatesInInput.size} duplicate lot${duplicatesInInput.size === 1 ? '' : 's'} detected in your input.`,
    );
  }

  // Warn about lots that already exist in the stepper list (Req 4.3).
  const conflictsWithExisting = valid.filter((lot) => existingSet.has(lot));
  if (conflictsWithExisting.length > 0) {
    warnings.push(
      `${conflictsWithExisting.length} lot${conflictsWithExisting.length === 1 ? '' : 's'} already exist in the filter list and will create duplicate entries.`,
    );
  }

  return {
    valid,
    invalid,
    duplicates: Array.from(duplicatesInInput),
    warnings,
  };
}
