CREATE OR REPLACE FUNCTION "test"."page_info_from_routine"(p_count INTEGER, p_limit INTEGER)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    RETURN (COALESCE(CAST((COALESCE(CAST('{"hasNextPage":' AS TEXT), 'null') || COALESCE(CAST(has_more(p_count, p_limit) AS TEXT), 'null')) AS TEXT), 'null') || COALESCE(CAST('}' AS TEXT), 'null'));
END;
$$;
