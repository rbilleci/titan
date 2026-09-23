DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`string_probe`$$
CREATE FUNCTION `test`.`string_probe`(p_text TEXT, p_needle TEXT)
RETURNS INT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_first TEXT;
    DECLARE v_score INT;
    DECLARE v_at INT;
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_time_zone_pinned BOOLEAN DEFAULT FALSE;
    DECLARE __titan_return_value INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
        RESIGNAL;
    END;
    IF @@session.time_zone <> '+00:00' THEN
        SET time_zone = '+00:00';
        SET __titan_time_zone_pinned = TRUE;
    END IF;
    SET v_first = SUBSTRING(p_text, 1, 1);
    SET v_score = (ASCII(v_first) - ASCII(CHAR(97 USING utf8mb4)));
    SET v_at = (CASE WHEN p_needle = '' THEN LEAST(GREATEST(1, 0), CHAR_LENGTH(p_text)) WHEN GREATEST(1, 0) > CHAR_LENGTH(p_text) THEN -1 WHEN LOCATE(p_needle, p_text, GREATEST(1, 0) + 1) = 0 THEN -1 ELSE (LOCATE(p_needle, p_text, GREATEST(1, 0) + 1) - 1) END);
    IF COALESCE((CASE WHEN 2 < 0 THEN FALSE WHEN (2 + CHAR_LENGTH(p_needle)) > CHAR_LENGTH(p_text) THEN FALSE ELSE SUBSTRING(p_text, 2 + 1, CHAR_LENGTH(p_needle)) = p_needle END), FALSE) THEN
        -- titan:source:build/golden-fixtures/emitter-deployability.java:40
        IF v_score IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:40'; END IF;
        SET v_score = (v_score + 10);
    END IF;
    IF COALESCE(CASE WHEN p_needle IS NULL THEN FALSE ELSE (BINARY p_text = BINARY p_needle) END, FALSE) THEN
        -- titan:source:build/golden-fixtures/emitter-deployability.java:40
        IF v_score IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:40'; END IF;
        SET v_score = (v_score + 100);
    END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:40
    IF v_score IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:40'; END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:40
    IF v_at IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:40'; END IF;
    SET __titan_return_value = (v_score + v_at);
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
    RETURN __titan_return_value;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
