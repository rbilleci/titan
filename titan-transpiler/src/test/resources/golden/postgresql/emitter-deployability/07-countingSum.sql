CREATE OR REPLACE FUNCTION "test"."counting_sum"(p_from INTEGER, p_up_to INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_sum INTEGER;
BEGIN
    v_sum := 0;
    DECLARE
        v_i INTEGER;
    BEGIN
        FOR v_i IN p_from..p_up_to LOOP
            IF COALESCE((v_i = (p_from + 2)), FALSE) THEN
                CONTINUE;
            END IF;
            v_sum := (v_sum + v_i);
        END LOOP;
    END;
    DECLARE
        v_j INTEGER;
    BEGIN
        FOR v_j IN 0..3 LOOP
            v_sum := (v_sum + 100);
        END LOOP;
    END;
    RETURN v_sum;
END;
$$;
