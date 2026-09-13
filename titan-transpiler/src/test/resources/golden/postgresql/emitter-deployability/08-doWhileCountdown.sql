CREATE OR REPLACE FUNCTION "test"."do_while_countdown"(p_start INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_total INTEGER;
    v_n INTEGER;
BEGIN
    v_total := 0;
    v_n := p_start;
    DECLARE
        __titan_dowhile_cond_4290 BOOLEAN;
        __titan_dowhile_first_4290 BOOLEAN;
    BEGIN
        __titan_dowhile_first_4290 := TRUE;
        LOOP
            IF __titan_dowhile_first_4290 THEN
                __titan_dowhile_first_4290 := FALSE;
            ELSE
                __titan_dowhile_cond_4290 := (v_n > 0);
                IF (__titan_dowhile_cond_4290 IS NULL OR (__titan_dowhile_cond_4290 = FALSE)) THEN
                    EXIT;
                END IF;
            END IF;
            v_n := (v_n - 1);
            IF (v_n = 2) THEN
                CONTINUE;
            END IF;
            v_total := (v_total + v_n);
        END LOOP;
    END;
    RETURN v_total;
END;
$$;
