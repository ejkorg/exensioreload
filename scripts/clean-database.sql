-- ==========================================================================
-- RefDB cleanup script — empties all tables on the RefDB Oracle instance.
-- User/auth tables live on the primary datasource and are not touched.
--
-- Usage:  sqlplus refdb_user/pass@refdb @scripts/clean-database.sql
-- ==========================================================================

-- Clear child tables first (FK dependencies)
DELETE FROM load_session_payload;
DELETE FROM sender_queue_wafers;
DELETE FROM external_location;

-- Clear parent tables
DELETE FROM SENDER_STAGE;
DELETE FROM sender_queue;
DELETE FROM load_session;
DELETE FROM external_environment;
DELETE FROM staging_session;

COMMIT;

-- Reset sequences
BEGIN
  EXECUTE IMMEDIATE 'DROP SEQUENCE SENDER_STAGE_SEQ';
  EXECUTE IMMEDIATE 'CREATE SEQUENCE SENDER_STAGE_SEQ START WITH 1 INCREMENT BY 1';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'DROP SEQUENCE app_seq_load_session';
  EXECUTE IMMEDIATE 'CREATE SEQUENCE app_seq_load_session START WITH 1 INCREMENT BY 1';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'DROP SEQUENCE app_seq_load_session_payload';
  EXECUTE IMMEDIATE 'CREATE SEQUENCE app_seq_load_session_payload START WITH 1 INCREMENT BY 1';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

-- Verify
SELECT 'SENDER_STAGE' AS table_name, COUNT(*) AS rows FROM SENDER_STAGE
UNION ALL SELECT 'sender_queue', COUNT(*) FROM sender_queue
UNION ALL SELECT 'sender_queue_wafers', COUNT(*) FROM sender_queue_wafers
UNION ALL SELECT 'load_session', COUNT(*) FROM load_session
UNION ALL SELECT 'load_session_payload', COUNT(*) FROM load_session_payload
UNION ALL SELECT 'external_environment', COUNT(*) FROM external_environment
UNION ALL SELECT 'external_location', COUNT(*) FROM external_location
UNION ALL SELECT 'staging_session', COUNT(*) FROM staging_session;
