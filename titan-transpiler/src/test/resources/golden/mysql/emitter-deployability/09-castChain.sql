DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`cast_chain`$$
CREATE FUNCTION `test`.`cast_chain`(p_small INT, p_ratio DOUBLE)
RETURNS BIGINT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_widened BIGINT;
    DECLARE v_scaled DOUBLE;
    DECLARE v_truncated BIGINT;
    DECLARE v_identity INT;
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_time_zone_pinned BOOLEAN DEFAULT FALSE;
    DECLARE __titan_return_value BIGINT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
        RESIGNAL;
    END;
    IF @@session.time_zone <> '+00:00' THEN
        SET time_zone = '+00:00';
        SET __titan_time_zone_pinned = TRUE;
    END IF;
    SET v_widened = CAST(p_small AS SIGNED);
    -- titan:source:build/golden-fixtures/emitter-deployability.java:157
    IF p_ratio IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:157'; END IF;
    SET v_scaled = (CAST(v_widened AS DOUBLE) * p_ratio);
    SET v_truncated = CAST(TRUNCATE(v_scaled, 0) AS SIGNED);
    SET v_identity = p_small;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:157
    IF v_widened IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:157'; END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:157
    IF v_truncated IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:157'; END IF;
    SET __titan_return_value = ((v_widened + v_truncated) + v_identity);
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
    RETURN __titan_return_value;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
