DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`render_boolean`$$
CREATE FUNCTION `test`.`render_boolean`(p_flag BOOLEAN)
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
    SET __titan_return_value = (CASE WHEN p_flag IS NULL THEN NULL WHEN p_flag THEN 'true' ELSE 'false' END);
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
