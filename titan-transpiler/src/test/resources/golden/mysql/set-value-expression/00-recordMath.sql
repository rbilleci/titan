DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`record_math`$$
CREATE PROCEDURE `test`.`record_math`(IN p_id INT, IN p_factor INT, IN p_name TEXT)
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_time_zone_pinned BOOLEAN DEFAULT FALSE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
        RESIGNAL;
    END;
    IF @@session.time_zone <> '+00:00' THEN
        SET time_zone = '+00:00';
        SET __titan_time_zone_pinned = TRUE;
    END IF;
    INSERT INTO `test`.`math_events` (`id`, `score`, `label`) VALUES ((p_id + 100), (p_id * p_factor), CONCAT(COALESCE(CAST(p_name AS CHAR), 'null'), COALESCE(CAST('-tag' AS CHAR), 'null')));
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
