CREATE OR REPLACE PROCEDURE "test"."record_event"(p_id INTEGER, p_name TEXT)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    INSERT INTO "test"."gate_events" ("id", "name") VALUES (p_id, p_name) ON CONFLICT ("id") DO NOTHING;
END;
$$;
