CREATE OR REPLACE FUNCTION "test"."has_more"(p_count INTEGER, p_limit INTEGER)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = "test", public, pg_temp
AS $$
BEGIN
    RETURN (p_count > p_limit);
END;
$$;
