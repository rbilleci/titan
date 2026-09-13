DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`reject_negative`$$
CREATE FUNCTION `test`.`reject_negative`(p_value INT)
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
    IF COALESCE((p_value < 0), FALSE) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TITAN_GATE_NEGATIVE';
    END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:30
    IF p_value IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/golden-fixtures/emitter-deployability.java:30'; END IF;
    SET __titan_return_value = (p_value * 2);
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
