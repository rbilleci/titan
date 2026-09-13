CREATE OR REPLACE FUNCTION "test"."tier_rank"(p_tier TEXT)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_rank INTEGER;
BEGIN
    v_rank := -1;
    DECLARE
        __titan_switch_value_2326 TEXT := p_tier;
    BEGIN
        IF COALESCE((__titan_switch_value_2326 = 'BASIC'), FALSE) THEN
            v_rank := 1;
        ELSIF COALESCE((__titan_switch_value_2326 = 'PRO'), FALSE) THEN
            v_rank := 2;
        ELSE
            v_rank := 0;
        END IF;
    END;
    RETURN v_rank;
END;
$$;
