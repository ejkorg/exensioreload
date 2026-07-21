package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.onsemi.cim.apps.exensio.exensioreload.dto.PreCheckBlock;

/**
 * Consolidated SQL utility service for Exensio queries.
 * 
 * <p>Consolidates SQL building utilities previously duplicated across ExensioClient, 
 * ExensioPreCheckService, and ExensioRawSqlService to ensure consistency and reduce 
 * maintenance burden.</p>
 * 
 * <p>Feature: lot-existence-verification, Property: SQL Utilities Consolidation</p>
 */
@Service
public class ExensioSqlUtilService {

    /**
     * Builds the WHERE clause for year-only date filtering (e.g., year 2026).
     * Matches records where end_time is within the calendar year.
     * 
     * @param year the year to filter by
     * @return Oracle SQL WHERE fragment
     */
    public static String yearOnlyClause(int year) {
        return "TRUNC(ol.end_time) >= TO_DATE('" + year + "-01-01','YYYY-MM-DD') " +
               "AND TRUNC(ol.end_time) < TO_DATE('" + (year + 1) + "-01-01','YYYY-MM-DD')";
    }

    /**
     * Builds the WHERE clause for year-month date filtering (e.g., 2026-07).
     * Matches records where end_time is within the calendar month.
     * 
     * @param year the year to filter by
     * @param month the month to filter by (1-12)
     * @return Oracle SQL WHERE fragment
     */
    public static String yearMonthClause(int year, int month) {
        String monthStr = String.format("%02d", month);
        String dateStr = year + "-" + monthStr + "-01";
        return "TRUNC(ol.end_time) >= TO_DATE('" + dateStr + "','YYYY-MM-DD') " +
               "AND TRUNC(ol.end_time) < ADD_MONTHS(TO_DATE('" + dateStr + "','YYYY-MM-DD'), 1)";
    }

    /**
     * Escapes SQL string literal by doubling single quotes.
     * Prevents SQL injection in dynamic SQL generation.
     * 
     * @param value the string to escape
     * @return escaped string suitable for SQL literal
     */
    public static String escapeSql(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }

    /**
     * Determines if a pgc_key represents a wafer-level class.
     * Wafer-level: Class 1 (pgc_key 1), Class 4 (pgc_key 4), Class 5 (pgc_key 5), Class 14 (pgc_key 14).
     * Lot-level: Class 2 (pgc_key 2).
     * 
     * @param pgcKey the PGC_KEY to check
     * @return true if wafer-level, false if lot-level
     */
    public static boolean isWaferLevelClass(int pgcKey) {
        return pgcKey == 1 || pgcKey == 4 || pgcKey == 5 || pgcKey == 14;
    }

    /**
     * Builds date range WHERE clauses from a list of PreCheckBlocks.
     * Each block generates a YEAR or YEAR-MONTH clause.
     * Multiple blocks are OR'd together.
     * 
     * @param blocks optional list of date range blocks
     * @return list of WHERE fragments (empty list if no blocks)
     */
    public static List<String> buildDateRangeClauses(List<PreCheckBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> clauses = new ArrayList<>();

        for (PreCheckBlock block : blocks) {
            if (block.year() == null) continue;

            String key = block.year() + "-" + (block.month() != null ? block.month() : "null");
            if (!seen.add(key)) continue;

            if (block.month() == null) {
                clauses.add(yearOnlyClause(block.year()));
            } else {
                clauses.add(yearMonthClause(block.year(), block.month()));
            }
        }

        return clauses;
    }
}
