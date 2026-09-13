CREATE OR REPLACE PROCEDURE "test"."idempotent_import"(p_id INTEGER, p_name TEXT, p_key TEXT)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_already_applied BOOLEAN;
BEGIN
    SELECT EXISTS (SELECT "gate_idempotency"."idempotency_key" FROM "test"."gate_idempotency" WHERE COALESCE(("gate_idempotency"."idempotency_key" = p_key), FALSE)) INTO v_already_applied;
    IF COALESCE(v_already_applied, FALSE) THEN
        RETURN;
    END IF;
    INSERT INTO "test"."gate_events" ("id", "name") VALUES (p_id, p_name) ON CONFLICT ("id") DO UPDATE SET "name" = p_name;
    INSERT INTO "test"."gate_audit" ("idempotency_key", "name") VALUES (p_key, p_name);
    INSERT INTO "test"."gate_idempotency" ("idempotency_key") VALUES (p_key);
END;
$$;
