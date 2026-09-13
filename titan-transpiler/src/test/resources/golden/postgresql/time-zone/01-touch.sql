CREATE OR REPLACE PROCEDURE "test"."touch"(p_id INTEGER)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    INSERT INTO "test"."tz_events" ("id", "name") VALUES (p_id, 'touched');
END;
$$;
