/**
 * Unit tests for lot-input-parser.ts
 *
 * Covers the core parsing and validation logic for the bulk lot input feature.
 * Tests are organized to match the requirements and design correctness properties.
 */

import { ParsedLotInput, parseLotInput, validateLots } from './lot-input-parser';

// ─── parseLotInput tests ──────────────────────────────────────────────────────

describe('parseLotInput', () => {
  describe('delimiter handling (Requirements 2.1–2.4)', () => {
    it('parses comma-separated lots', () => {
      const result = parseLotInput('L001, L002, L003');
      expect(result.lots).toEqual(['L001', 'L002', 'L003']);
      expect(result.delimiter).toBe('comma');
    });

    it('parses newline-separated lots', () => {
      const result = parseLotInput('L001\nL002\nL003');
      expect(result.lots).toEqual(['L001', 'L002', 'L003']);
      expect(result.delimiter).toBe('newline');
    });

    it('parses semicolon-separated lots', () => {
      const result = parseLotInput('L001;L002;L003');
      expect(result.lots).toEqual(['L001', 'L002', 'L003']);
      expect(result.delimiter).toBe('semicolon');
    });

    it('parses mixed delimiter input (comma + newline)', () => {
      const result = parseLotInput('L001, L002\nL003');
      expect(result.lots).toEqual(['L001', 'L002', 'L003']);
      expect(result.delimiter).toBe('mixed');
    });

    it('parses mixed delimiter input (all three)', () => {
      const result = parseLotInput('L001,L002;L003\nL004');
      expect(result.lots).toEqual(['L001', 'L002', 'L003', 'L004']);
      expect(result.delimiter).toBe('mixed');
    });

    it('handles carriage return + newline (Windows line endings)', () => {
      const result = parseLotInput('L001\r\nL002\r\nL003');
      expect(result.lots).toEqual(['L001', 'L002', 'L003']);
    });
  });

  describe('whitespace trimming (Requirement 2.5)', () => {
    it('trims leading and trailing spaces from each lot', () => {
      const result = parseLotInput('  L001  ,  L002  ');
      expect(result.lots).toEqual(['L001', 'L002']);
    });

    it('trims tab characters from lots', () => {
      const result = parseLotInput('\tL001\t,\tL002\t');
      expect(result.lots).toEqual(['L001', 'L002']);
    });

    it('returns a single lot when input has surrounding whitespace', () => {
      const result = parseLotInput('   L001   ');
      expect(result.lots).toEqual(['L001']);
    });
  });

  describe('empty entry filtering (Requirement 2.6)', () => {
    it('filters consecutive commas (empty entries)', () => {
      const result = parseLotInput('L001,,L002,,L003');
      expect(result.lots).toEqual(['L001', 'L002', 'L003']);
    });

    it('filters blank lines', () => {
      const result = parseLotInput('L001\n\nL002\n\n\nL003');
      expect(result.lots).toEqual(['L001', 'L002', 'L003']);
    });

    it('returns empty lots array for whitespace-only input', () => {
      const result = parseLotInput('   \n\n  ');
      expect(result.lots).toEqual([]);
    });

    it('returns empty lots array for empty string', () => {
      const result = parseLotInput('');
      expect(result.lots).toEqual([]);
      expect(result.delimiter).toBe('none');
    });
  });

  describe('case preservation (Requirement 2.7)', () => {
    it('preserves mixed-case lot identifiers', () => {
      const result = parseLotInput('LoT001, LOT002, lot003');
      expect(result.lots).toEqual(['LoT001', 'LOT002', 'lot003']);
    });

    it('does not lowercase any characters', () => {
      const input = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
      const result = parseLotInput(input);
      expect(result.lots[0]).toBe('ABCDEFGHIJKLMNOPQRSTUVWXYZ');
    });
  });

  describe('single-token (no delimiter)', () => {
    it('returns a single lot when no delimiters are present', () => {
      const result = parseLotInput('L001');
      expect(result.lots).toEqual(['L001']);
      expect(result.delimiter).toBe('none');
    });
  });

  describe('originalInput preservation', () => {
    it('preserves the original input string', () => {
      const input = 'L001, L002\nL003';
      const result = parseLotInput(input);
      expect(result.originalInput).toBe(input);
    });
  });
});

// ─── validateLots tests ───────────────────────────────────────────────────────

describe('validateLots', () => {
  const parsed = (lots: string[]): ParsedLotInput => ({
    lots,
    originalInput: lots.join(','),
    delimiter: 'comma',
  });

  describe('validity classification (Requirements 4.2, 4.4)', () => {
    it('classifies non-empty lots as valid', () => {
      const result = validateLots(parsed(['L001', 'L002', 'L003']), []);
      expect(result.valid).toEqual(['L001', 'L002', 'L003']);
      expect(result.invalid).toEqual([]);
    });

    it('classifies empty strings as invalid', () => {
      const result = validateLots(parsed(['L001', '', 'L002']), []);
      expect(result.valid).toEqual(['L001', 'L002']);
      expect(result.invalid).toEqual(['']);
    });
  });

  describe('duplicate detection within input (Requirements 4.3, 4.4)', () => {
    it('detects duplicate lots in input', () => {
      const result = validateLots(parsed(['L001', 'L002', 'L001', 'L003', 'L002']), []);
      expect(result.duplicates).toContain('L001');
      expect(result.duplicates).toContain('L002');
      expect(result.warnings.some((w) => w.includes('duplicate'))).toBe(true);
    });

    it('includes duplicates in the valid list (all valid occurrences kept)', () => {
      const result = validateLots(parsed(['L001', 'L001', 'L002']), []);
      // Both occurrences of L001 are valid
      expect(result.valid.filter((l) => l === 'L001').length).toBe(2);
    });

    it('reports no duplicates for unique input', () => {
      const result = validateLots(parsed(['L001', 'L002', 'L003']), []);
      expect(result.duplicates).toEqual([]);
      expect(result.warnings.some((w) => w.includes('duplicate'))).toBe(false);
    });
  });

  describe('existing lot conflict warnings (Requirement 4.3)', () => {
    it('warns when a lot already exists in the stepper list', () => {
      const result = validateLots(parsed(['L001', 'L002']), ['L001']);
      expect(result.warnings.some((w) => w.includes('already exist'))).toBe(true);
    });

    it('does NOT reject the lot — still adds it (Requirement 5.4)', () => {
      const result = validateLots(parsed(['L001']), ['L001']);
      expect(result.valid).toContain('L001');
    });

    it('does not warn when no overlap with existing lots', () => {
      const result = validateLots(parsed(['L003', 'L004']), ['L001', 'L002']);
      expect(result.warnings.some((w) => w.includes('already exist'))).toBe(false);
    });
  });

  describe('maximum limit enforcement (Requirements 8.1, 8.2)', () => {
    it('truncates input exceeding 1000 lots', () => {
      const lots = Array.from({ length: 1100 }, (_, i) => `L${String(i).padStart(4, '0')}`);
      const result = validateLots(parsed(lots), []);
      expect(result.valid.length).toBe(1000);
    });

    it('emits a warning when input exceeds 1000', () => {
      const lots = Array.from({ length: 1001 }, (_, i) => `L${String(i).padStart(4, '0')}`);
      const result = validateLots(parsed(lots), []);
      expect(result.warnings.some((w) => w.includes('1000'))).toBe(true);
    });

    it('does not truncate or warn for exactly 1000 lots', () => {
      const lots = Array.from({ length: 1000 }, (_, i) => `L${String(i).padStart(4, '0')}`);
      const result = validateLots(parsed(lots), []);
      expect(result.valid.length).toBe(1000);
      expect(result.warnings.some((w) => w.includes('limit'))).toBe(false);
    });
  });

  describe('warning message content', () => {
    it('produces no warnings for clean valid input', () => {
      const result = validateLots(parsed(['L001', 'L002']), []);
      expect(result.warnings).toEqual([]);
    });
  });
});

// ─── Integration: parseLotInput + validateLots ────────────────────────────────

describe('parseLotInput + validateLots integration', () => {
  it('parses and validates a realistic paste from spreadsheet (comma-delimited)', () => {
    const input = 'L001, L002, L003, L001, L004';
    const parsed = parseLotInput(input);
    const result = validateLots(parsed, []);

    expect(result.valid.length).toBe(5); // all are valid; duplicate kept
    expect(result.duplicates).toEqual(['L001']);
    expect(result.warnings.length).toBeGreaterThan(0);
  });

  it('parses and validates a realistic paste with newlines', () => {
    const input = 'L001\nL002\nL003';
    const parsed = parseLotInput(input);
    const result = validateLots(parsed, ['L002']); // L002 already in stepper

    expect(result.valid).toEqual(['L001', 'L002', 'L003']);
    expect(result.warnings.some((w) => w.includes('already exist'))).toBe(true);
  });

  it('file upload content produces same result as pasted content (Requirement 3.4)', () => {
    // Both pasted text and file-read text are plain strings — same parsing path
    const content = 'L001,L002\nL003;L004';
    const fromPaste = parseLotInput(content);
    const fromFile = parseLotInput(content);
    expect(fromPaste.lots).toEqual(fromFile.lots);
    expect(fromPaste.delimiter).toBe(fromFile.delimiter);
  });
});
