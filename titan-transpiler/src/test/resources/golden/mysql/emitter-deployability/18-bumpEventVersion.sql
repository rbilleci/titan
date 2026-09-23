DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`bump_event_version`$$
CREATE PROCEDURE `test`.`bump_event_version`(IN p_id INT)
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
    UPDATE `test`.`gate_events` SET `version` = (`gate_events`.`version` + 1) WHERE COALESCE((`gate_events`.`id` = p_id), FALSE);
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
