DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`touch`$$
CREATE PROCEDURE `test`.`touch`(IN p_id INT)
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    INSERT INTO `test`.`tz_events` (`id`, `name`) VALUES (p_id, 'touched');
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
