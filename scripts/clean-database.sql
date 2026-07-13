-- ==========================================================================
-- RefDB cleanup — empties all RefDB tables and resets sequences.
-- Only touches tables on the REFDB datasource. User/auth tables live
-- on a different datasource and are not affected.
--
-- Usage:  sqlplus refdb_user/pass@refdb @scripts/clean-database.sql
-- ==========================================================================

SET SERVEROUTPUT ON

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

  FOR s IN (SELECT sequence_name FROM user_sequences
             WHERE sequence_name IN (
               'SENDER_STAGE_SEQ', 'SENDER_QUEUE_SEQ', 'SENDER_QUEUE_WAFERS_SEQ',
               'LOAD_SESSION_SEQ', 'LOAD_SESSION_PAYLOAD_SEQ',
               'EXTERNAL_ENVIRONMENT_SEQ', 'EXTERNAL_LOCATION_SEQ',
               'STAGING_SESSION_SEQ',
               'APP_SEQ_LOAD_SESSION', 'APP_SEQ_LOAD_SESSION_PAYLOAD'))
  LOOP
    EXECUTE IMMEDIATE 'DROP SEQUENCE ' || s.sequence_name;
    EXECUTE IMMEDIATE 'CREATE SEQUENCE ' || s.sequence_name || ' START WITH 1 INCREMENT BY 1';
    DBMS_OUTPUT.PUT_LINE('Reset sequence ' || s.sequence_name);
  END LOOP;
END;
/
