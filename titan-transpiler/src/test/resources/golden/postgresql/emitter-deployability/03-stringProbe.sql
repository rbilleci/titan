CREATE OR REPLACE FUNCTION "test"."string_probe"(p_text TEXT, p_needle TEXT)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_first TEXT;
    v_score INTEGER;
    v_at INTEGER;
BEGIN
    v_first := SUBSTRING(p_text FROM 1 FOR 1);
    v_score := (ASCII(v_first) - ASCII('a'));
    v_at := (CASE WHEN p_needle = '' THEN LEAST(GREATEST(1, 0), CHAR_LENGTH(p_text)) WHEN GREATEST(1, 0) > CHAR_LENGTH(p_text) THEN -1 WHEN STRPOS(SUBSTRING(p_text FROM (GREATEST(1, 0) + 1)), p_needle) = 0 THEN -1 ELSE (GREATEST(1, 0) + STRPOS(SUBSTRING(p_text FROM (GREATEST(1, 0) + 1)), p_needle) - 1) END);
    IF COALESCE((CASE WHEN 2 < 0 THEN FALSE WHEN (2 + CHAR_LENGTH(p_needle)) > CHAR_LENGTH(p_text) THEN FALSE ELSE SUBSTRING(p_text FROM (2 + 1) FOR CHAR_LENGTH(p_needle)) = p_needle END), FALSE) THEN
        -- titan:source:build/golden-fixtures/emitter-deployability.java:40
        IF v_score IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:40'; END IF;
        v_score := (v_score + 10);
    END IF;
    IF COALESCE(CASE WHEN p_needle IS NULL THEN FALSE ELSE (p_text = p_needle) END, FALSE) THEN
        -- titan:source:build/golden-fixtures/emitter-deployability.java:40
        IF v_score IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:40'; END IF;
        v_score := (v_score + 100);
    END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:40
    IF v_score IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:40'; END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:40
    IF v_at IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:40'; END IF;
    RETURN (v_score + v_at);
END;
$$;
