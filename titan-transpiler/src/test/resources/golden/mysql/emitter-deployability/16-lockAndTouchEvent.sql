DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`lock_and_touch_event`$$
CREATE PROCEDURE `test`.`lock_and_touch_event`(IN p_id INT, IN p_name TEXT)
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    SELECT `gate_events`.`id` FROM `test`.`gate_events` WHERE COALESCE((`gate_events`.`id` = p_id), FALSE) FOR UPDATE;
    INSERT INTO `test`.`gate_events` (`id`, `name`) VALUES (p_id, p_name) ON DUPLICATE KEY UPDATE `name` = p_name;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
