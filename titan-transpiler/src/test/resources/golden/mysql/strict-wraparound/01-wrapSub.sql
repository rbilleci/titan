DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`wrap_sub`$$
CREATE FUNCTION `test`.`wrap_sub`(p_a INT, p_b INT)
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
    -- titan:source:build/golden-fixtures/strict-wraparound.java:9
    IF p_a IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/strict-wraparound.java:9'; END IF;
    -- titan:source:build/golden-fixtures/strict-wraparound.java:9
    IF p_b IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/strict-wraparound.java:9'; END IF;
    SET __titan_return_value = titan_rt_java_int_sub(p_a, p_b);
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
