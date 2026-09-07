-- =============================================================================
-- Admin Cleanup Script: Remove Failed Records (by User and/or Session ID)
-- =============================================================================
-- Database: REFDB (Oracle / PL/SQL)
-- Target Tables: SENDER_STAGE, STAGING_SESSION
--
-- Supported Target Statuses:
--   'CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT'
--
-- How to run in SQL*Plus or Oracle SQL Developer / DBeaver:
--   1. Set parameters below:
--        - p_username: e.g. 'fg8n8x' (case-insensitive) or NULL for all users
--        - p_session_id: e.g. '1e5b5de3-3b2b-4d51-928d-ed92e7e9a8f3' or NULL
--        - p_dry_run: TRUE to preview what will be deleted, FALSE to execute
--        - p_remove_empty_sessions: TRUE to delete session if 0 records remain
--   2. Execute:
--        sqlplus refdb_user/pass@refdb @scripts/cleanup-failed-records.sql
-- =============================================================================

SET SERVEROUTPUT ON SIZE UNLIMITED;
SET DEFINE OFF;
SET FEEDBACK OFF;

DECLARE
  -- ===========================================================================
  -- CONFIGURATION PARAMETERS (Edit these as needed before running)
  -- ===========================================================================
  p_username              VARCHAR2(128) := NULL;  -- e.g. 'fg8n8x' or NULL for all
  p_session_id            VARCHAR2(128) := NULL;  -- e.g. '1e5b5de3-3b2b-4d51-928d-ed92e7e9a8f3' or NULL
  p_dry_run               BOOLEAN       := FALSE;  -- Set to TRUE for preview only, FALSE to delete
  p_remove_empty_sessions BOOLEAN       := TRUE;  -- If TRUE, deletes STAGING_SESSION if all records are gone
  -- ===========================================================================

  v_count_failed          NUMBER := 0;
  v_count_sessions        NUMBER := 0;
  v_count_sessions_del    NUMBER := 0;
  v_count_sessions_upd    NUMBER := 0;
  v_user_filter           VARCHAR2(128);
  v_session_filter        VARCHAR2(128);
BEGIN
  v_user_filter := TRIM(p_username);
  v_session_filter := TRIM(p_session_id);

  DBMS_OUTPUT.PUT_LINE('=============================================================================');
  DBMS_OUTPUT.PUT_LINE('       EXENSIO RELOAD ADMIN CLEANUP: FAILED RECORDS');
  DBMS_OUTPUT.PUT_LINE('=============================================================================');
  IF p_dry_run THEN
    DBMS_OUTPUT.PUT_LINE('MODE: *** DRY RUN (PREVIEW ONLY - NO DATA WILL BE MODIFIED) ***');
  ELSE
    DBMS_OUTPUT.PUT_LINE('MODE: *** LIVE EXECUTION (CHANGES WILL BE COMMITTED) ***');
  END IF;
  DBMS_OUTPUT.PUT_LINE('User Filter    : ' || NVL(v_user_filter, '(ALL USERS)'));
  DBMS_OUTPUT.PUT_LINE('Session Filter : ' || NVL(v_session_filter, '(ALL SESSIONS)'));
  DBMS_OUTPUT.PUT_LINE('Target Statuses: CP_FAILED, LOAD_FAILED, FAILED, ERROR, CP_TIMEOUT');
  DBMS_OUTPUT.PUT_LINE('-----------------------------------------------------------------------------');

  -- Safety check: require at least user or session filter, unless explicitly confirmed
  IF v_user_filter IS NULL AND v_session_filter IS NULL THEN
    DBMS_OUTPUT.PUT_LINE('NOTICE: No user or session filter provided - targeting ALL failed records system-wide.');
  END IF;

  -- 1. Identify and count failed records to delete
  SELECT COUNT(*)
    INTO v_count_failed
    FROM sender_stage s
   WHERE s.status IN ('CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT')
     AND (v_user_filter IS NULL OR UPPER(NVL(s.staged_by, s.last_requested_by)) = UPPER(v_user_filter))
     AND (v_session_filter IS NULL OR s.request_id = v_session_filter);

  DBMS_OUTPUT.PUT_LINE('Failed records matched in SENDER_STAGE: ' || v_count_failed);

  IF v_count_failed = 0 THEN
    DBMS_OUTPUT.PUT_LINE('No failed records found matching the specified criteria.');
    DBMS_OUTPUT.PUT_LINE('Cleanup completed.');
    RETURN;
  END IF;

  -- Detail breakdown by failure status
  DBMS_OUTPUT.PUT_LINE('');
  DBMS_OUTPUT.PUT_LINE('Breakdown by status:');
  FOR r IN (
    SELECT s.status, COUNT(*) AS cnt
      FROM sender_stage s
     WHERE s.status IN ('CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT')
       AND (v_user_filter IS NULL OR UPPER(NVL(s.staged_by, s.last_requested_by)) = UPPER(v_user_filter))
       AND (v_session_filter IS NULL OR s.request_id = v_session_filter)
     GROUP BY s.status
     ORDER BY cnt DESC
  ) LOOP
    DBMS_OUTPUT.PUT_LINE('  - ' || RPAD(r.status, 20) || ': ' || r.cnt || ' record(s)');
  END LOOP;

  -- Collect affected sessions
  DBMS_OUTPUT.PUT_LINE('');
  DBMS_OUTPUT.PUT_LINE('Affected Session IDs:');
  FOR sess IN (
    SELECT DISTINCT s.request_id AS sid, COUNT(*) AS failed_cnt
      FROM sender_stage s
     WHERE s.status IN ('CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT')
       AND (v_user_filter IS NULL OR UPPER(NVL(s.staged_by, s.last_requested_by)) = UPPER(v_user_filter))
       AND (v_session_filter IS NULL OR s.request_id = v_session_filter)
       AND s.request_id IS NOT NULL
     GROUP BY s.request_id
  ) LOOP
    v_count_sessions := v_count_sessions + 1;
    DBMS_OUTPUT.PUT_LINE('  - Session: ' || sess.sid || ' (' || sess.failed_cnt || ' failed records)');
  END LOOP;

  IF p_dry_run THEN
    DBMS_OUTPUT.PUT_LINE('-----------------------------------------------------------------------------');
    DBMS_OUTPUT.PUT_LINE('DRY RUN COMPLETE: ' || v_count_failed || ' record(s) across ' || v_count_sessions || ' session(s) would be deleted.');
    DBMS_OUTPUT.PUT_LINE('To execute the deletion, change p_dry_run := FALSE; and rerun this script.');
    RETURN;
  END IF;

  -- 2. Execute deletion of failed records from SENDER_STAGE
  DELETE FROM sender_stage s
   WHERE s.status IN ('CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT')
     AND (v_user_filter IS NULL OR UPPER(NVL(s.staged_by, s.last_requested_by)) = UPPER(v_user_filter))
     AND (v_session_filter IS NULL OR s.request_id = v_session_filter);

  DBMS_OUTPUT.PUT_LINE('');
  DBMS_OUTPUT.PUT_LINE('Successfully deleted ' || SQL%ROWCOUNT || ' record(s) from SENDER_STAGE.');

  -- 3. Synchronize STAGING_SESSION table for affected sessions
  FOR sess IN (
    SELECT ss.id, ss.total_files, ss.files_failed, ss.status
      FROM staging_session ss
     WHERE (v_session_filter IS NULL OR ss.id = v_session_filter)
       AND (v_user_filter IS NULL OR UPPER(ss.username) = UPPER(v_user_filter))
       AND (v_session_filter IS NOT NULL OR ss.id IN (
             SELECT DISTINCT s2.request_id FROM sender_stage s2 WHERE s2.request_id IS NOT NULL
           ))
  ) LOOP
    DECLARE
      v_rem_total  NUMBER := 0;
      v_rem_failed NUMBER := 0;
      v_rem_done   NUMBER := 0;
    BEGIN
      SELECT COUNT(*),
             COUNT(CASE WHEN status IN ('CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT') THEN 1 END),
             COUNT(CASE WHEN status IN ('DONE', 'COMPLETED', 'COMPLETED_MANUAL_VERIFICATION_REQUIRED') THEN 1 END)
        INTO v_rem_total, v_rem_failed, v_rem_done
        FROM sender_stage
       WHERE request_id = sess.id;

      IF v_rem_total = 0 AND p_remove_empty_sessions THEN
        -- All records were deleted: remove empty session
        DELETE FROM staging_session WHERE id = sess.id;
        v_count_sessions_del := v_count_sessions_del + 1;
        DBMS_OUTPUT.PUT_LINE('  - Deleted empty session ' || sess.id);
      ELSE
        -- Update session counts and status
        UPDATE staging_session
           SET total_files  = v_rem_total,
               files_failed = v_rem_failed,
               files_done   = v_rem_done,
               status       = CASE
                                WHEN v_rem_total = 0 THEN 'EMPTY'
                                WHEN v_rem_failed = 0 AND v_rem_done = v_rem_total THEN 'COMPLETED'
                                WHEN v_rem_failed > 0 AND (v_rem_done + v_rem_failed) = v_rem_total THEN 'COMPLETED_WITH_ERRORS'
                                ELSE status
                              END,
               updated_at   = SYSTIMESTAMP
         WHERE id = sess.id;
        v_count_sessions_upd := v_count_sessions_upd + 1;
        DBMS_OUTPUT.PUT_LINE('  - Updated session ' || sess.id || ': remaining total=' || v_rem_total || ', failed=' || v_rem_failed || ', done=' || v_rem_done);
      END IF;
    END;
  END LOOP;

  COMMIT;
  DBMS_OUTPUT.PUT_LINE('-----------------------------------------------------------------------------');
  DBMS_OUTPUT.PUT_LINE('CLEANUP COMMITTED SUCCESSFULLY:');
  DBMS_OUTPUT.PUT_LINE('  - Failed records deleted: ' || v_count_failed);
  DBMS_OUTPUT.PUT_LINE('  - Sessions updated      : ' || v_count_sessions_upd);
  DBMS_OUTPUT.PUT_LINE('  - Empty sessions removed: ' || v_count_sessions_del);
  DBMS_OUTPUT.PUT_LINE('=============================================================================');

EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    DBMS_OUTPUT.PUT_LINE('ERROR during cleanup: ' || SQLERRM);
    DBMS_OUTPUT.PUT_LINE('Transaction rolled back.');
    RAISE;
END;
/
