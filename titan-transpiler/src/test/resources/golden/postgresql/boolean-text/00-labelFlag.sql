CREATE OR REPLACE FUNCTION "test"."label_flag"(p_name TEXT, p_flag BOOLEAN)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    RETURN (COALESCE(CAST((COALESCE(CAST(p_name AS TEXT), 'null') || COALESCE(CAST('=' AS TEXT), 'null')) AS TEXT), 'null') || COALESCE(CAST(p_flag AS TEXT), 'null'));
END;
$$;
