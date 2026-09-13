CREATE OR REPLACE FUNCTION "test"."nested_loop_score"(p_rows INTEGER, p_cols INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_score INTEGER;
    v_r INTEGER;
BEGIN
    v_score := 0;
    v_r := 0;
    WHILE COALESCE((v_r < p_rows), FALSE) LOOP
        DECLARE
            v_c INTEGER;
        BEGIN
            v_c := 0;
            WHILE COALESCE((v_c < p_cols), FALSE) LOOP
                v_c := (v_c + 1);
                IF (v_c = 3) THEN
                    CONTINUE;
                END IF;
                IF (v_c = 5) THEN
                    EXIT;
                END IF;
                v_score := (v_score + v_c);
            END LOOP;
            v_r := (v_r + 1);
            IF (v_score > 100) THEN
                EXIT;
            END IF;
        END;
    END LOOP;
    RETURN v_score;
END;
$$;
