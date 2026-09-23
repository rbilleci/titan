DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`tier_rank`$$
CREATE FUNCTION `test`.`tier_rank`(p_tier TEXT)
RETURNS INT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_rank INT;
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
    SET v_rank = -1;
    BEGIN
    DECLARE __titan_switch_value_2326 TEXT DEFAULT p_tier;
    IF COALESCE((__titan_switch_value_2326 = 'BASIC'), FALSE) THEN
        SET v_rank = 1;
    ELSEIF COALESCE((__titan_switch_value_2326 = 'PRO'), FALSE) THEN
        SET v_rank = 2;
    ELSE
        SET v_rank = 0;
    END IF;
    END;
    SET __titan_return_value = v_rank;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
    RETURN __titan_return_value;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
