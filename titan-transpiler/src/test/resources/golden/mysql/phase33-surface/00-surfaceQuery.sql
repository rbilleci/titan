DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`surface_query`$$
CREATE PROCEDURE `test`.`surface_query`()
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    SELECT DISTINCT `e`.`name`, CASE WHEN (`m`.`score` >= 90) THEN 'top' WHEN (`m`.`score` >= 50) THEN 'mid' ELSE 'low' END, `m`.`score` FROM `test`.`employees` AS `e` JOIN `test`.`employees` AS `m` ON (`e`.`manager_id` = `m`.`id`) WHERE `e`.`id` NOT IN (5, 6) ORDER BY (`m`.`score` IS NULL) ASC, `m`.`score` DESC, `e`.`name` ASC;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
