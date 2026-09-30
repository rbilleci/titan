CREATE OR REPLACE PROCEDURE "test"."upsert_event_version"(p_id INTEGER, p_name TEXT)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = "test", public, pg_temp
AS $$
BEGIN
    INSERT INTO "test"."gate_events" ("id", "name", "version") VALUES (p_id, p_name, 1) ON CONFLICT ("id") DO UPDATE SET "version" = ("gate_events"."version" + 1);
END;
$$;
