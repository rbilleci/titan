DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`int_remainder`$$
CREATE FUNCTION `test`.`int_remainder`(p_dividend INT, p_divisor INT)
RETURNS INT
SQL SECURITY INVOKER
BEGIN
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
    -- titan:source:build/golden-fixtures/emitter-deployability.java:242
    IF p_dividend IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:242'; END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:242
    IF p_divisor IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:242'; END IF;
    SET __titan_return_value = titan_rt_java_mod(p_dividend, p_divisor);
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
    RETURN __titan_return_value;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
