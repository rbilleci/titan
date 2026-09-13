CREATE OR REPLACE PROCEDURE "test"."record_math"(p_id INTEGER, p_factor INTEGER, p_name TEXT)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    INSERT INTO "test"."math_events" ("id", "score", "label") VALUES ((p_id + 100), (p_id * p_factor), (COALESCE(CAST(p_name AS TEXT), 'null') || COALESCE(CAST('-tag' AS TEXT), 'null')));
END;
$$;
