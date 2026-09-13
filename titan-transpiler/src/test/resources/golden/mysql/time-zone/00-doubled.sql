DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`doubled`$$
CREATE FUNCTION `test`.`doubled`(p_value INT)
RETURNS INT
SQL SECURITY INVOKER
BEGIN
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_return_value INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    -- titan:source:build/golden-fixtures/time-zone.java:8
    IF p_value IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/time-zone.java:8'; END IF;
    SET __titan_return_value = (p_value * 2);
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
