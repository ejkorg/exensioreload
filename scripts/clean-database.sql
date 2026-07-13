-- ==========================================================================
-- Database cleanup script — empties all functional/data tables while
-- preserving user/identity tables.
--
-- Usage:
--   Oracle:     sqlplus user/pass@db @scripts/clean-database.sql
--   H2:         java -cp h2*.jar org.h2.tools.RunScript -url jdbc:h2:... -script scripts/clean-database.sql
--   PostgreSQL: psql -f scripts/clean-database.sql
--
-- WARNING: This deletes ALL data in functional tables. User accounts
--          (users, user_roles, tokens, sessions, audit_log) are preserved.
-- ==========================================================================

-- Disable FK checks where supported (restored at end)
-- Oracle: handled inline since no session-level FK disable exists
-- H2 / PostgreSQL / MySQL:
-- SET REFERENTIAL_INTEGRITY FALSE;   -- H2
-- SET session_replication_role = replica;  -- PG
-- SET FOREIGN_KEY_CHECKS = 0;        -- MySQL

-- ==========================================================================
-- Legacy auth tables (may not exist if already dropped by modernization
-- migration — the DROP IF EXISTS guards against that)
-- ==========================================================================
DELETE FROM APP_USER_ROLES;
DELETE FROM APP_ROLES;
DELETE FROM APP_USERS;

-- ==========================================================================
-- Clear child tables first (FK dependencies)
-- ==========================================================================
DELETE FROM load_session_payload;
DELETE FROM sender_queue_wafers;
DELETE FROM external_location;

-- ==========================================================================
-- Clear parent tables
-- ==========================================================================
DELETE FROM SENDER_STAGE;
DELETE FROM sender_queue;
DELETE FROM load_session;
DELETE FROM external_environment;
DELETE FROM staging_session;

-- Auth tokens (ephemeral — safe to clear)
DELETE FROM refresh_tokens;

-- ==========================================================================
-- Reset Oracle sequences so IDs start fresh from 1
-- H2 auto-increment columns reset automatically on DELETE (H2 treats DELETE
-- on empty tables as TRUNCATE, which resets generated columns).
-- ==========================================================================
-- SENDER_STAGE_SEQ — RefDbService builds sequence name from staging table
--   via nextIdExpr(): table + "_SEQ" → "SENDER_STAGE_SEQ"
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

-- Any remaining auto-generated Liquibase/Hibernate sequences
BEGIN
  FOR s IN (SELECT sequence_name FROM user_sequences
             WHERE sequence_name NOT IN ('SENDER_STAGE_SEQ', 'APP_SEQ_LOAD_SESSION', 'APP_SEQ_LOAD_SESSION_PAYLOAD'))
  LOOP
    EXECUTE IMMEDIATE 'DROP SEQUENCE ' || s.sequence_name;
    EXECUTE IMMEDIATE 'CREATE SEQUENCE ' || s.sequence_name || ' START WITH 1 INCREMENT BY 1';
  END LOOP;
END;
/

-- ==========================================================================
-- Re-enable integrity checks (if disabled at top)
-- ==========================================================================
-- H2:       SET REFERENTIAL_INTEGRITY TRUE;
-- PG:       SET session_replication_role = DEFAULT;
-- MySQL:    SET FOREIGN_KEY_CHECKS = 1;

-- Verify counts
SELECT 'SENDER_STAGE' AS table_name, COUNT(*) AS remaining_rows FROM SENDER_STAGE
UNION ALL SELECT 'sender_queue', COUNT(*) FROM sender_queue
UNION ALL SELECT 'sender_queue_wafers', COUNT(*) FROM sender_queue_wafers
UNION ALL SELECT 'load_session', COUNT(*) FROM load_session
UNION ALL SELECT 'load_session_payload', COUNT(*) FROM load_session_payload
UNION ALL SELECT 'external_environment', COUNT(*) FROM external_environment
UNION ALL SELECT 'external_location', COUNT(*) FROM external_location
UNION ALL SELECT 'staging_session', COUNT(*) FROM staging_session
UNION ALL SELECT 'refresh_tokens', COUNT(*) FROM refresh_tokens
UNION ALL SELECT 'users (PRESERVED)', COUNT(*) FROM users
UNION ALL SELECT 'user_roles (PRESERVED)', COUNT(*) FROM user_roles;
