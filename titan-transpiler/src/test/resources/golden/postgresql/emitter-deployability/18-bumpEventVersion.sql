CREATE OR REPLACE PROCEDURE "test"."bump_event_version"(p_id INTEGER)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    UPDATE "test"."gate_events" SET "version" = ("gate_events"."version" + 1) WHERE COALESCE(("gate_events"."id" = p_id), FALSE);
END;
$$;
