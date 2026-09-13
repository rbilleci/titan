CREATE OR REPLACE FUNCTION "test"."try_side_effects"(p_explode BOOLEAN)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_result TEXT;
BEGIN
    v_result := 'start';
    DECLARE __titan_saved_state TEXT := NULL;
    DECLARE __titan_saved_message TEXT := NULL;
    DECLARE v_error TEXT := NULL;
    BEGIN
        BEGIN
        v_result := (COALESCE(CAST(v_result AS TEXT), 'null') || COALESCE(CAST(':before' AS TEXT), 'null'));
        IF COALESCE(p_explode, FALSE) THEN
            RAISE EXCEPTION USING ERRCODE = '45000', MESSAGE = 'TITAN_E5_BOOM';
        END IF;
        v_result := (COALESCE(CAST(v_result AS TEXT), 'null') || COALESCE(CAST(':after' AS TEXT), 'null'));
        EXCEPTION
            WHEN SQLSTATE '22023' OR SQLSTATE '55000' OR SQLSTATE '22012' OR SQLSTATE '45001' OR SQLSTATE '2202E' OR SQLSTATE '0A000' OR SQLSTATE '45000' THEN
                v_error := SQLERRM;
                v_result := (COALESCE(CAST(v_result AS TEXT), 'null') || COALESCE(CAST(':caught' AS TEXT), 'null'));
            WHEN OTHERS THEN
            __titan_saved_state := SQLSTATE;
            __titan_saved_message := SQLERRM;
        END;
        IF __titan_saved_state IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = __titan_saved_state, MESSAGE = __titan_saved_message;
        END IF;
    END;
    RETURN v_result;
END;
$$;
