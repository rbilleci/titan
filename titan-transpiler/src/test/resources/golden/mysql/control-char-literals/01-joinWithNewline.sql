DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`join_with_newline`$$
CREATE FUNCTION `test`.`join_with_newline`(p_left TEXT, p_right TEXT)
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
    SET __titan_return_value = CONCAT(COALESCE(CAST(CONCAT(COALESCE(CAST(p_left AS CHAR), 'null'), COALESCE(CAST('\n' AS CHAR), 'null')) AS CHAR), 'null'), COALESCE(CAST(p_right AS CHAR), 'null'));
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
