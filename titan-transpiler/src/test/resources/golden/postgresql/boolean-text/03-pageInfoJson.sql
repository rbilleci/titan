CREATE OR REPLACE FUNCTION "test"."page_info_json"(p_has_next_page BOOLEAN, p_has_previous_page BOOLEAN)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    RETURN (COALESCE(CAST((COALESCE(CAST((COALESCE(CAST((COALESCE(CAST('{"hasNextPage":' AS TEXT), 'null') || COALESCE(CAST(p_has_next_page AS TEXT), 'null')) AS TEXT), 'null') || COALESCE(CAST(',"hasPreviousPage":' AS TEXT), 'null')) AS TEXT), 'null') || COALESCE(CAST(p_has_previous_page AS TEXT), 'null')) AS TEXT), 'null') || COALESCE(CAST('}' AS TEXT), 'null'));
END;
$$;
