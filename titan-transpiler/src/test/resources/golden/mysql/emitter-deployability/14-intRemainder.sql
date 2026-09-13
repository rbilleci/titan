DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`int_remainder`$$
CREATE FUNCTION `test`.`int_remainder`(p_dividend INT, p_divisor INT)
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
    -- titan:source:build/golden-fixtures/emitter-deployability.java:242
    IF p_dividend IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:242'; END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:242
    IF p_divisor IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:242'; END IF;
    SET __titan_return_value = titan_rt_java_mod(p_dividend, p_divisor);
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
