CREATE OR REPLACE PROCEDURE "test"."touch"(p_id INTEGER)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = "test", public, pg_temp
AS $$
BEGIN
    INSERT INTO "test"."tz_events" ("id", "name") VALUES (p_id, 'touched');
END;
$$;
