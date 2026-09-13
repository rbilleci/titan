CREATE OR REPLACE PROCEDURE "test"."store_event_document"(p_id INTEGER, p_name TEXT, p_document TEXT)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    INSERT INTO "test"."gate_events" ("id", "name", "document") VALUES (p_id, p_name, CAST(p_document AS JSONB)) ON CONFLICT ("id") DO UPDATE SET "document" = CAST(p_document AS JSONB);
END;
$$;
