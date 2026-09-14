package com.onsemi.cim.apps.exensio.exensioreload.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * WaferNormalizerUtilsTest
 *
 * Tests for wafer normalization logic that cleans wafer identifiers
 * and pads single digits with leading zeros.
 */
public class WaferNormalizerUtilsTest {

    @Test
    public void testSingleDigitPadding() {
        // Single digits should be padded to 2 digits with leading zero
        assertEquals("01", WaferNormalizerUtils.normalizeWafer("1"));
        assertEquals("02", WaferNormalizerUtils.normalizeWafer("2"));
        assertEquals("09", WaferNormalizerUtils.normalizeWafer("9"));
    }

    @Test
    public void testTwoDigits() {
        // Two-digit numbers should remain unchanged
        assertEquals("10", WaferNormalizerUtils.normalizeWafer("10"));
        assertEquals("42", WaferNormalizerUtils.normalizeWafer("42"));
        assertEquals("99", WaferNormalizerUtils.normalizeWafer("99"));
    }

    @Test
    public void testRemoveNonDigits() {
        // Non-digit characters should be stripped
        assertEquals("01", WaferNormalizerUtils.normalizeWafer("W1"));
        assertEquals("01", WaferNormalizerUtils.normalizeWafer("Wafer1"));
        assertEquals("09", WaferNormalizerUtils.normalizeWafer("W-9"));
        assertEquals("10", WaferNormalizerUtils.normalizeWafer("wafer_10"));
    }

    @Test
    public void testLeadingZeros() {
        // Leading zeros should be removed (parsed as int then reformatted)
        assertEquals("01", WaferNormalizerUtils.normalizeWafer("001"));
        assertEquals("09", WaferNormalizerUtils.normalizeWafer("009"));
        assertEquals("10", WaferNormalizerUtils.normalizeWafer("0010"));
    }

    @Test
    public void testComplexCases() {
        // Complex real-world examples
        assertEquals("01", WaferNormalizerUtils.normalizeWafer("Wafer-1"));
        assertEquals("42", WaferNormalizerUtils.normalizeWafer("W_42_Test"));
        assertEquals("05", WaferNormalizerUtils.normalizeWafer("wafer005"));
    }

    @Test
    public void testNullAndEmpty() {
        // Null and empty strings should return null
        assertNull(WaferNormalizerUtils.normalizeWafer(null));
        assertNull(WaferNormalizerUtils.normalizeWafer(""));
        assertNull(WaferNormalizerUtils.normalizeWafer("   "));
    }

    @Test
    public void testNoDigits() {
        // Strings with no digits should return null
        assertNull(WaferNormalizerUtils.normalizeWafer("NoDigits"));
        assertNull(WaferNormalizerUtils.normalizeWafer("---"));
        assertNull(WaferNormalizerUtils.normalizeWafer("abcXYZ"));
    }

    @Test
    public void testMixedContent() {
        // Prefix and suffix with digits in middle
        assertEquals("05", WaferNormalizerUtils.normalizeWafer("Prefix5Suffix"));
        assertEquals("42", WaferNormalizerUtils.normalizeWafer("W42X"));
    }
}
