DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`has_more`$$
CREATE FUNCTION `test`.`has_more`(p_count INT, p_limit INT)
RETURNS BOOLEAN
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_return_value BOOLEAN;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    SET __titan_return_value = (p_count > p_limit);
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
