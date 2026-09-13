CREATE OR REPLACE FUNCTION "test"."exception_dispatch"(p_code INTEGER)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_result TEXT;
BEGIN
    v_result := 'ran';
    DECLARE __titan_saved_state TEXT := NULL;
    DECLARE __titan_saved_message TEXT := NULL;
    DECLARE v_stale TEXT := NULL;
    BEGIN
        BEGIN
        IF COALESCE((p_code = 1), FALSE) THEN
            RAISE EXCEPTION USING ERRCODE = '45002', MESSAGE = COALESCE((COALESCE(CAST('TITAN_QUOTA:' AS TEXT), 'null') || COALESCE(CAST(p_code AS TEXT), 'null')), 'Java throw');
        END IF;
        IF COALESCE((p_code = 2), FALSE) THEN
            RAISE EXCEPTION USING ERRCODE = '45003', MESSAGE = 'TITAN_STALE';
        END IF;
        EXCEPTION
            WHEN SQLSTATE '45003' THEN
                v_stale := SQLERRM;
                v_result := 'stale-caught';
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
