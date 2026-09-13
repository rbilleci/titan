CREATE OR REPLACE FUNCTION "test"."describe_order"(p_left INTEGER, p_right INTEGER)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    RETURN (COALESCE(CAST('ascending=' AS TEXT), 'null') || COALESCE(CAST((p_left <= p_right) AS TEXT), 'null'));
END;
$$;
