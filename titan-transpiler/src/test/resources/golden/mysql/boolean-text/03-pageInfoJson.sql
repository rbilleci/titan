DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`page_info_json`$$
CREATE FUNCTION `test`.`page_info_json`(p_has_next_page BOOLEAN, p_has_previous_page BOOLEAN)
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
    SET __titan_return_value = CONCAT(COALESCE(CAST(CONCAT(COALESCE(CAST(CONCAT(COALESCE(CAST(CONCAT(COALESCE(CAST('{"hasNextPage":' AS CHAR), 'null'), COALESCE((CASE WHEN p_has_next_page IS NULL THEN NULL WHEN p_has_next_page THEN 'true' ELSE 'false' END), 'null')) AS CHAR), 'null'), COALESCE(CAST(',"hasPreviousPage":' AS CHAR), 'null')) AS CHAR), 'null'), COALESCE((CASE WHEN p_has_previous_page IS NULL THEN NULL WHEN p_has_previous_page THEN 'true' ELSE 'false' END), 'null')) AS CHAR), 'null'), COALESCE(CAST('}' AS CHAR), 'null'));
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
