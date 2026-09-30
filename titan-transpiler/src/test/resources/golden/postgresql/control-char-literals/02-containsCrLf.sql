CREATE OR REPLACE FUNCTION "test"."contains_cr_lf"(p_text TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = "test", public, pg_temp
AS $$
BEGIN
    RETURN (STRPOS(p_text, E'\r\n') > 0);
END;
$$;
