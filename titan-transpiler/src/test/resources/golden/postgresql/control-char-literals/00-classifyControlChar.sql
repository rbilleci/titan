CREATE OR REPLACE FUNCTION "test"."classify_control_char"(p_text TEXT)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_first TEXT;
    v_kind INTEGER;
BEGIN
    v_first := SUBSTRING(p_text FROM 1 FOR 1);
    v_kind := 0;
    IF COALESCE((v_first = E'\n'), FALSE) THEN
        v_kind := 1;
    END IF;
    IF COALESCE((v_first = E'\t'), FALSE) THEN
        v_kind := 2;
    END IF;
    IF COALESCE((v_first = E'\r'), FALSE) THEN
        v_kind := 3;
    END IF;
    RETURN v_kind;
END;
$$;
