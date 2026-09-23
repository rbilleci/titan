DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`store_event_document`$$
CREATE PROCEDURE `test`.`store_event_document`(IN p_id INT, IN p_name TEXT, IN p_document TEXT)
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
    INSERT INTO `test`.`gate_events` (`id`, `name`, `document`) VALUES (p_id, p_name, CAST(p_document AS JSON)) ON DUPLICATE KEY UPDATE `document` = CAST(p_document AS JSON);
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
