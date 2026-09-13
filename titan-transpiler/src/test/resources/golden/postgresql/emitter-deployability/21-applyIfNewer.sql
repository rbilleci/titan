CREATE OR REPLACE PROCEDURE "test"."apply_if_newer"(p_id INTEGER, p_new_version INTEGER, p_name TEXT)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_current INTEGER;
BEGIN
    SELECT "gate_events"."version" INTO v_current FROM "test"."gate_events" WHERE COALESCE(("gate_events"."id" = p_id), FALSE) LIMIT 1;
    IF COALESCE((v_current >= p_new_version), FALSE) THEN
        RETURN;
    END IF;
    INSERT INTO "test"."gate_events" ("id", "name", "version") VALUES (p_id, p_name, p_new_version) ON CONFLICT ("id") DO UPDATE SET "name" = p_name, "version" = p_new_version;
END;
$$;
