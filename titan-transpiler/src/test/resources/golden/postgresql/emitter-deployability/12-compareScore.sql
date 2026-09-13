CREATE OR REPLACE FUNCTION "test"."compare_score"(p_current NUMERIC(38,10), p_threshold NUMERIC(38,10))
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_cmp INTEGER;
BEGIN
    -- titan:source:build/golden-fixtures/emitter-deployability.java:220
    IF p_current IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:220'; END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:220
    IF p_threshold IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:220'; END IF;
    v_cmp := CASE WHEN (p_current < p_threshold) THEN -1 WHEN (p_current > p_threshold) THEN 1 WHEN (p_current = p_threshold) THEN 0 END;
    IF COALESCE((v_cmp > 0), FALSE) THEN
        RETURN 1;
    END IF;
    IF COALESCE((v_cmp < 0), FALSE) THEN
        RETURN -1;
    END IF;
    RETURN 0;
END;
$$;
