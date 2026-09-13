CREATE OR REPLACE FUNCTION "test"."guarded_label"(p_input TEXT, p_fail_fast BOOLEAN)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_label TEXT;
BEGIN
    v_label := 'start';
    DECLARE __titan_saved_state TEXT := NULL;
    DECLARE __titan_saved_message TEXT := NULL;
    DECLARE v_error TEXT := NULL;
    BEGIN
        BEGIN
        IF COALESCE(p_fail_fast, FALSE) THEN
            RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'TITAN_GATE_FAIL_FAST';
        END IF;
        v_label := (COALESCE(CAST(v_label AS TEXT), 'null') || COALESCE(CAST((COALESCE(CAST(':' AS TEXT), 'null') || COALESCE(CAST(p_input AS TEXT), 'null')) AS TEXT), 'null'));
        EXCEPTION
            WHEN SQLSTATE '55000' THEN
                v_error := SQLERRM;
                v_label := 'caught';
            WHEN OTHERS THEN
            __titan_saved_state := SQLSTATE;
            __titan_saved_message := SQLERRM;
        END;
        v_label := (COALESCE(CAST(v_label AS TEXT), 'null') || COALESCE(CAST(':done' AS TEXT), 'null'));
        IF __titan_saved_state IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = __titan_saved_state, MESSAGE = __titan_saved_message;
        END IF;
    END;
    RETURN v_label;
END;
$$;
