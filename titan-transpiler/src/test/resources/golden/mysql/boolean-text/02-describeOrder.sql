DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`describe_order`$$
CREATE FUNCTION `test`.`describe_order`(p_left INT, p_right INT)
RETURNS TEXT
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_return_value TEXT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    SET __titan_return_value = CONCAT(COALESCE(CAST('ascending=' AS CHAR), 'null'), COALESCE((CASE WHEN (p_left <= p_right) IS NULL THEN NULL WHEN (p_left <= p_right) THEN 'true' ELSE 'false' END), 'null'));
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
