DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`bump_event_version`$$
CREATE PROCEDURE `test`.`bump_event_version`(IN p_id INT)
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    UPDATE `test`.`gate_events` SET `version` = (`gate_events`.`version` + 1) WHERE COALESCE((`gate_events`.`id` = p_id), FALSE);
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
