DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`page_info_from_routine`$$
CREATE FUNCTION `test`.`page_info_from_routine`(p_count INT, p_limit INT)
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
    SET __titan_return_value = CONCAT(COALESCE(CAST(CONCAT(COALESCE(CAST('{"hasNextPage":' AS CHAR), 'null'), COALESCE((CASE WHEN has_more(p_count, p_limit) IS NULL THEN NULL WHEN has_more(p_count, p_limit) THEN 'true' ELSE 'false' END), 'null')) AS CHAR), 'null'), COALESCE(CAST('}' AS CHAR), 'null'));
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
