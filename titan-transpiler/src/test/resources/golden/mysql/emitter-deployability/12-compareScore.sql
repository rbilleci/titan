DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`compare_score`$$
CREATE FUNCTION `test`.`compare_score`(p_current DECIMAL(38,10), p_threshold DECIMAL(38,10))
RETURNS INT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_cmp INT;
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
    -- titan:source:build/golden-fixtures/emitter-deployability.java:220
    IF p_current IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:220'; END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:220
    IF p_threshold IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:220'; END IF;
    SET v_cmp = CASE WHEN (p_current < p_threshold) THEN -1 WHEN (p_current > p_threshold) THEN 1 WHEN (p_current = p_threshold) THEN 0 END;
    IF COALESCE((v_cmp > 0), FALSE) THEN
        SET __titan_return_value = 1;
        IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
        RETURN __titan_return_value;
    END IF;
    IF COALESCE((v_cmp < 0), FALSE) THEN
        SET __titan_return_value = -1;
        IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
        RETURN __titan_return_value;
    END IF;
    SET __titan_return_value = 0;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
    RETURN __titan_return_value;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
