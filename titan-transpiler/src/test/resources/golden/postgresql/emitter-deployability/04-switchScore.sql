CREATE OR REPLACE FUNCTION "test"."switch_score"(p_input INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_score INTEGER;
BEGIN
    v_score := 0;
    DECLARE
        __titan_switch_value_1922 INTEGER := p_input;
    BEGIN
        IF (__titan_switch_value_1922 = 1) THEN
            v_score := 10;
        ELSIF (__titan_switch_value_1922 = 2) THEN
            v_score := 20;
        ELSE
            v_score := -1;
        END IF;
    END;
    RETURN v_score;
END;
$$;
