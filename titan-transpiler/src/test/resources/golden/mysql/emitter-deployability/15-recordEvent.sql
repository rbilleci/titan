DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`record_event`$$
CREATE PROCEDURE `test`.`record_event`(IN p_id INT, IN p_name TEXT)
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    INSERT INTO `test`.`gate_events` (`id`, `name`) VALUES (p_id, p_name) ON DUPLICATE KEY UPDATE `id` = `id`;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
