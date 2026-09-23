DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`surface_query`$$
CREATE PROCEDURE `test`.`surface_query`()
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
    SELECT DISTINCT `e`.`name`, CASE WHEN (`m`.`score` >= 90) THEN 'top' WHEN (`m`.`score` >= 50) THEN 'mid' ELSE 'low' END, `m`.`score` FROM `test`.`employees` AS `e` JOIN `test`.`employees` AS `m` ON (`e`.`manager_id` = `m`.`id`) WHERE `e`.`id` NOT IN (5, 6) ORDER BY (`m`.`score` IS NULL) ASC, `m`.`score` DESC, `e`.`name` ASC;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
