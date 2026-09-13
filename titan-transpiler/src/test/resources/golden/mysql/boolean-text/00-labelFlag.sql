DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`label_flag`$$
CREATE FUNCTION `test`.`label_flag`(p_name TEXT, p_flag BOOLEAN)
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
    SET __titan_return_value = CONCAT(COALESCE(CAST(CONCAT(COALESCE(CAST(p_name AS CHAR), 'null'), COALESCE(CAST('=' AS CHAR), 'null')) AS CHAR), 'null'), COALESCE((CASE WHEN p_flag IS NULL THEN NULL WHEN p_flag THEN 'true' ELSE 'false' END), 'null'));
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
