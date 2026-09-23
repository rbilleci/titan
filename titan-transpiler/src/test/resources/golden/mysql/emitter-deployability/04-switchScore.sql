DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`switch_score`$$
CREATE FUNCTION `test`.`switch_score`(p_input INT)
RETURNS INT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_score INT;
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
    SET v_score = 0;
    BEGIN
    DECLARE __titan_switch_value_1922 INT DEFAULT p_input;
    IF (__titan_switch_value_1922 = 1) THEN
        SET v_score = 10;
    ELSEIF (__titan_switch_value_1922 = 2) THEN
        SET v_score = 20;
    ELSE
        SET v_score = -1;
    END IF;
    END;
    SET __titan_return_value = v_score;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
    RETURN __titan_return_value;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
