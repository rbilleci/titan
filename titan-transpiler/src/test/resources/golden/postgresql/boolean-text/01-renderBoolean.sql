CREATE OR REPLACE FUNCTION "test"."render_boolean"(p_flag BOOLEAN)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = "test", public, pg_temp
AS $$
BEGIN
    RETURN CAST(p_flag AS TEXT);
END;
$$;
