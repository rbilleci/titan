CREATE OR REPLACE FUNCTION "test"."join_with_newline"(p_left TEXT, p_right TEXT)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    RETURN (COALESCE(CAST((COALESCE(CAST(p_left AS TEXT), 'null') || COALESCE(CAST(E'\n' AS TEXT), 'null')) AS TEXT), 'null') || COALESCE(CAST(p_right AS TEXT), 'null'));
END;
$$;
