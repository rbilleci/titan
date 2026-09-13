CREATE OR REPLACE PROCEDURE "test"."lock_and_touch_event"(p_id INTEGER, p_name TEXT)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    PERFORM "gate_events"."id" FROM "test"."gate_events" WHERE COALESCE(("gate_events"."id" = p_id), FALSE) FOR UPDATE;
    INSERT INTO "test"."gate_events" ("id", "name") VALUES (p_id, p_name) ON CONFLICT ("id") DO UPDATE SET "name" = p_name;
END;
$$;
