-- =============================================================================
-- Admin Cleanup Script: Remove Failed Records (by User and/or Session ID)
-- =============================================================================
-- Database: REFDB (PostgreSQL)
-- Target Tables: SENDER_STAGE, STAGING_SESSION
--
-- Supported Target Statuses:
--   'CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT'
--
-- How to run in psql or DBeaver:
--   1. Set variables inside the DECLARE block below:
--        - p_username: e.g. 'fg8n8x' (case-insensitive) or NULL for all users
--        - p_session_id: e.g. '1e5b5de3-3b2b-4d51-928d-ed92e7e9a8f3' or NULL
--        - p_dry_run: true to preview what will be deleted, false to execute
--        - p_remove_empty_sessions: true to delete session if 0 records remain
--   2. Execute the DO block.
-- =============================================================================

DO $$
DECLARE
  -- ===========================================================================
  -- CONFIGURATION PARAMETERS (Edit these as needed before running)
  -- ===========================================================================
  p_username              VARCHAR := NULL;  -- e.g. 'fg8n8x' or NULL for all
  p_session_id            VARCHAR := NULL;  -- e.g. '1e5b5de3-3b2b-4d51-928d-ed92e7e9a8f3' or NULL
  p_dry_run               BOOLEAN := FALSE;  -- Set to TRUE for preview only, FALSE to delete
  p_remove_empty_sessions BOOLEAN := TRUE;  -- If TRUE, deletes STAGING_SESSION if all records are gone
  -- ===========================================================================

  v_count_failed          INTEGER := 0;
  v_count_sessions        INTEGER := 0;
  v_count_sessions_del    INTEGER := 0;
  v_count_sessions_upd    INTEGER := 0;
  v_user_filter           VARCHAR;
  v_session_filter        VARCHAR;
  
  -- Cursor variables for loop
  r RECORD;
  sess RECORD;
  
  v_rem_total  INTEGER := 0;
  v_rem_failed INTEGER := 0;
  v_rem_done   INTEGER := 0;
BEGIN
  v_user_filter := TRIM(p_username);
  v_session_filter := TRIM(p_session_id);

  RAISE NOTICE '=============================================================================';
  RAISE NOTICE '       EXENSIO RELOAD ADMIN CLEANUP: FAILED RECORDS (POSTGRESQL)     ';
  RAISE NOTICE '=============================================================================';
  IF p_dry_run THEN
    RAISE NOTICE 'MODE: *** DRY RUN (PREVIEW ONLY - NO DATA WILL BE MODIFIED) ***';
  ELSE
    RAISE NOTICE 'MODE: *** LIVE EXECUTION (CHANGES WILL BE COMMITTED) ***';
  END IF;
  RAISE NOTICE 'User Filter    : %', COALESCE(v_user_filter, '(ALL USERS)');
  RAISE NOTICE 'Session Filter : %', COALESCE(v_session_filter, '(ALL SESSIONS)');
  RAISE NOTICE 'Target Statuses: CP_FAILED, LOAD_FAILED, FAILED, ERROR, CP_TIMEOUT';
  RAISE NOTICE '-----------------------------------------------------------------------------';

  IF v_user_filter IS NULL AND v_session_filter IS NULL THEN
    RAISE NOTICE 'NOTICE: No user or session filter provided - targeting ALL failed records system-wide.';
  END IF;

  -- 1. Identify and count failed records to delete
  SELECT COUNT(*)
    INTO v_count_failed
    FROM sender_stage s
   WHERE s.status IN ('CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT')
     AND (v_user_filter IS NULL OR UPPER(COALESCE(s.staged_by, s.last_requested_by)) = UPPER(v_user_filter))
     AND (v_session_filter IS NULL OR s.request_id = v_session_filter);

  RAISE NOTICE 'Failed records matched in SENDER_STAGE: %', v_count_failed;

  IF v_count_failed = 0 THEN
    RAISE NOTICE 'No failed records found matching the specified criteria.';
    RAISE NOTICE 'Cleanup completed.';
    RETURN;
  END IF;

  -- Detail breakdown by failure status
  RAISE NOTICE '';
  RAISE NOTICE 'Breakdown by status:';
  FOR r IN (
    SELECT s.status, COUNT(*) AS cnt
      FROM sender_stage s
     WHERE s.status IN ('CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT')
       AND (v_user_filter IS NULL OR UPPER(COALESCE(s.staged_by, s.last_requested_by)) = UPPER(v_user_filter))
       AND (v_session_filter IS NULL OR s.request_id = v_session_filter)
     GROUP BY s.status
     ORDER BY cnt DESC
  ) LOOP
    RAISE NOTICE '  - %: % record(s)', RPAD(r.status::text, 20), r.cnt;
  END LOOP;

  -- Collect affected sessions
  RAISE NOTICE '';
  RAISE NOTICE 'Affected Session IDs:';
  FOR sess IN (
    SELECT DISTINCT s.request_id AS sid, COUNT(*) AS failed_cnt
      FROM sender_stage s
     WHERE s.status IN ('CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT')
       AND (v_user_filter IS NULL OR UPPER(COALESCE(s.staged_by, s.last_requested_by)) = UPPER(v_user_filter))
       AND (v_session_filter IS NULL OR s.request_id = v_session_filter)
       AND s.request_id IS NOT NULL
     GROUP BY s.request_id
  ) LOOP
    v_count_sessions := v_count_sessions + 1;
    RAISE NOTICE '  - Session: % (% failed records)', sess.sid, sess.failed_cnt;
  END LOOP;

  IF p_dry_run THEN
    RAISE NOTICE '-----------------------------------------------------------------------------';
    RAISE NOTICE 'DRY RUN COMPLETE: % record(s) across % session(s) would be deleted.', v_count_failed, v_count_sessions;
    RAISE NOTICE 'To execute the deletion, change p_dry_run := FALSE; and rerun this script.';
    RETURN;
  END IF;

  -- 2. Execute deletion of failed records from SENDER_STAGE
  DELETE FROM sender_stage s
   WHERE s.status IN ('CP_FAILED', 'LOAD_FAILED', 'FAILED', 'ERROR', 'CP_TIMEOUT')
     AND (v_user_filter IS NULL OR UPPER(COALESCE(s.staged_by, s.last_requested_by)) = UPPER(v_user_filter))
     AND (v_session_filter IS NULL OR s.request_id = v_session_filter);

  GET DIAGNOSTICS v_count_failed = ROW_COUNT;

  RAISE NOTICE '';
  RAISE NOTICE 'Successfully deleted % record(s) from SENDER_STAGE.', v_count_failed;

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
      RAISE NOTICE '  - Deleted empty session %', sess.id;
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
             updated_at   = CURRENT_TIMESTAMP
       WHERE id = sess.id;
      v_count_sessions_upd := v_count_sessions_upd + 1;
      RAISE NOTICE '  - Updated session %: remaining total=%, failed=%, done=%', sess.id, v_rem_total, v_rem_failed, v_rem_done;
    END IF;
  END LOOP;

  RAISE NOTICE '-----------------------------------------------------------------------------';
  RAISE NOTICE 'CLEANUP COMMITTED SUCCESSFULLY:';
  RAISE NOTICE '  - Failed records deleted: %', v_count_failed;
  RAISE NOTICE '  - Sessions updated      : %', v_count_sessions_upd;
  RAISE NOTICE '  - Empty sessions removed: %', v_count_sessions_del;
  RAISE NOTICE '=============================================================================';

EXCEPTION
  WHEN OTHERS THEN
    RAISE NOTICE 'ERROR during cleanup: %', SQLERRM;
    RAISE;
END;
$$ LANGUAGE plpgsql;
