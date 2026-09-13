DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`classify_control_char`$$
CREATE FUNCTION `test`.`classify_control_char`(p_text TEXT)
RETURNS INT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_first TEXT;
    DECLARE v_kind INT;
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_return_value INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    SET v_first = SUBSTRING(p_text, 1, 1);
    SET v_kind = 0;
    IF COALESCE((v_first = CHAR(10 USING utf8mb4)), FALSE) THEN
        SET v_kind = 1;
    END IF;
    IF COALESCE((v_first = CHAR(9 USING utf8mb4)), FALSE) THEN
        SET v_kind = 2;
    END IF;
    IF COALESCE((v_first = CHAR(13 USING utf8mb4)), FALSE) THEN
        SET v_kind = 3;
    END IF;
    SET __titan_return_value = v_kind;
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
