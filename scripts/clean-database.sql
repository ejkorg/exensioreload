-- ==========================================================================
-- RefDB cleanup script — empties all tables on the RefDB Oracle instance.
-- Gracefully skips tables that don't exist.
--
-- Usage:  sqlplus refdb_user/pass@refdb @scripts/clean-database.sql
-- ==========================================================================

SET SERVEROUTPUT ON
SET FEEDBACK OFF
SET VERIFY OFF

BEGIN
  FOR t IN (SELECT table_name FROM user_tables
             WHERE table_name IN ('SENDER_STAGE', 'SENDER_QUEUE', 'SENDER_QUEUE_WAFERS',
                                  'LOAD_SESSION', 'LOAD_SESSION_PAYLOAD',
                                  'EXTERNAL_ENVIRONMENT', 'EXTERNAL_LOCATION',
                                  'STAGING_SESSION'))
  LOOP
    EXECUTE IMMEDIATE 'DELETE FROM ' || t.table_name;
    DBMS_OUTPUT.PUT_LINE('Cleared ' || t.table_name || ': ' || SQL%ROWCOUNT || ' rows');
  END LOOP;
  COMMIT;
  DBMS_OUTPUT.PUT_LINE('Commit complete.');

  -- Reset sequences
  FOR s IN (SELECT sequence_name FROM user_sequences)
  LOOP
    EXECUTE IMMEDIATE 'DROP SEQUENCE ' || s.sequence_name;
    EXECUTE IMMEDIATE 'CREATE SEQUENCE ' || s.sequence_name || ' START WITH 1 INCREMENT BY 1';
    DBMS_OUTPUT.PUT_LINE('Reset sequence ' || s.sequence_name);
  END LOOP;
EXCEPTION WHEN OTHERS THEN
  ROLLBACK;
  DBMS_OUTPUT.PUT_LINE('ERROR: ' || SQLERRM);
  RAISE;
END;
/

-- Verify
SELECT table_name, num_rows, last_analyzed FROM user_tables
 WHERE table_name IN ('SENDER_STAGE', 'SENDER_QUEUE', 'SENDER_QUEUE_WAFERS',
                       'LOAD_SESSION', 'LOAD_SESSION_PAYLOAD',
                       'EXTERNAL_ENVIRONMENT', 'EXTERNAL_LOCATION',
                       'STAGING_SESSION')
 ORDER BY table_name;

SELECT sequence_name, min_value, increment_by FROM user_sequences ORDER BY sequence_name;
