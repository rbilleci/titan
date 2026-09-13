CREATE OR REPLACE PROCEDURE "test"."upsert_event_version"(p_id INTEGER, p_name TEXT)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    INSERT INTO "test"."gate_events" ("id", "name", "version") VALUES (p_id, p_name, 1) ON CONFLICT ("id") DO UPDATE SET "version" = ("gate_events"."version" + 1);
END;
$$;
