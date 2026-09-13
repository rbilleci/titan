DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`upsert_event_version`$$
CREATE PROCEDURE `test`.`upsert_event_version`(IN p_id INT, IN p_name TEXT)
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    INSERT INTO `test`.`gate_events` (`id`, `name`, `version`) VALUES (p_id, p_name, 1) ON DUPLICATE KEY UPDATE `version` = (`gate_events`.`version` + 1);
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
