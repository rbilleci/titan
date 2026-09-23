DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`apply_if_newer`$$
CREATE PROCEDURE `test`.`apply_if_newer`(IN p_id INT, IN p_new_version INT, IN p_name TEXT)
SQL SECURITY INVOKER
BEGIN
    DECLARE v_current INT;
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
    proc_body: BEGIN
        SELECT `gate_events`.`version` INTO v_current FROM `test`.`gate_events` WHERE COALESCE((`gate_events`.`id` = p_id), FALSE) LIMIT 1;
        IF COALESCE((v_current >= p_new_version), FALSE) THEN
            LEAVE proc_body;
        END IF;
        INSERT INTO `test`.`gate_events` (`id`, `name`, `version`) VALUES (p_id, p_name, p_new_version) ON DUPLICATE KEY UPDATE `name` = p_name, `version` = p_new_version;
    END;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
