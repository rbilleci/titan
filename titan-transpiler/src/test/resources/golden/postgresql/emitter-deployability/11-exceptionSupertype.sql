CREATE OR REPLACE FUNCTION "test"."exception_supertype"(p_code INTEGER)
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
    DECLARE v_any TEXT := NULL;
    BEGIN
        BEGIN
        IF COALESCE((p_code = 1), FALSE) THEN
            RAISE EXCEPTION USING ERRCODE = '45002', MESSAGE = 'TITAN_QUOTA_SUPER';
        END IF;
        IF COALESCE((p_code = 2), FALSE) THEN
            RAISE EXCEPTION USING ERRCODE = '45003', MESSAGE = 'TITAN_STALE_SUPER';
        END IF;
        EXCEPTION
            WHEN SQLSTATE '22023' OR SQLSTATE '55000' OR SQLSTATE '22012' OR SQLSTATE '45001' OR SQLSTATE '2202E' OR SQLSTATE '0A000' OR SQLSTATE '45000' OR SQLSTATE '45002' OR SQLSTATE '45003' THEN
                v_any := SQLERRM;
                v_result := 'caught';
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
