DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`exception_dispatch`$$
CREATE FUNCTION `test`.`exception_dispatch`(p_code INT)
RETURNS TEXT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_result TEXT;
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_return_value TEXT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    SET v_result = 'ran';
    BEGIN
        DECLARE __titan_saved_exception BOOLEAN DEFAULT FALSE;
        DECLARE __titan_saved_state CHAR(5) DEFAULT NULL;
        DECLARE __titan_saved_message TEXT DEFAULT NULL;
        DECLARE v_stale TEXT DEFAULT NULL;
        BEGIN
        DECLARE EXIT HANDLER FOR SQLSTATE '45003'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '45003';
            SET v_stale = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLEXCEPTION
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_state = RETURNED_SQLSTATE, __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET v_stale = __titan_saved_message;
        END;
        IF COALESCE((p_code = 1), FALSE) THEN
            SET @__titan_raise_message = COALESCE(CONCAT(COALESCE(CAST('TITAN_QUOTA:' AS CHAR), 'null'), COALESCE(CAST(p_code AS CHAR), 'null')), 'Java throw');
            SIGNAL SQLSTATE '45002' SET MESSAGE_TEXT = @__titan_raise_message;
        END IF;
        IF COALESCE((p_code = 2), FALSE) THEN
            SIGNAL SQLSTATE '45003' SET MESSAGE_TEXT = 'TITAN_STALE';
        END IF;
        END;
        IF __titan_saved_exception THEN
            IF __titan_saved_state = '45003' THEN
                SET v_result = 'stale-caught';
                SET __titan_saved_exception = FALSE;
                SET __titan_saved_state = NULL;
            END IF;
        END IF;
        IF __titan_saved_exception THEN
            SET __titan_saved_message = IFNULL(__titan_saved_message, 'Unhandled exception in try block');
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = __titan_saved_message;
        END IF;
    END;
    SET __titan_return_value = v_result;
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
