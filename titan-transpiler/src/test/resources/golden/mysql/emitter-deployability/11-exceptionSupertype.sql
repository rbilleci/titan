DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`exception_supertype`$$
CREATE FUNCTION `test`.`exception_supertype`(p_code INT)
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
        DECLARE v_any TEXT DEFAULT NULL;
        BEGIN
        DECLARE EXIT HANDLER FOR SQLSTATE '22023'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '22023';
            SET v_any = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLSTATE '55000'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '55000';
            SET v_any = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLSTATE '22012'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '22012';
            SET v_any = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLSTATE '45001'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '45001';
            SET v_any = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLSTATE '2202E'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '2202E';
            SET v_any = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLSTATE '0A000'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '0A000';
            SET v_any = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLSTATE '45000'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '45000';
            SET v_any = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLSTATE '45002'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '45002';
            SET v_any = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLSTATE '45003'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '45003';
            SET v_any = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLEXCEPTION
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_state = RETURNED_SQLSTATE, __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET v_any = __titan_saved_message;
        END;
        IF COALESCE((p_code = 1), FALSE) THEN
            SIGNAL SQLSTATE '45002' SET MESSAGE_TEXT = 'TITAN_QUOTA_SUPER';
        END IF;
        IF COALESCE((p_code = 2), FALSE) THEN
            SIGNAL SQLSTATE '45003' SET MESSAGE_TEXT = 'TITAN_STALE_SUPER';
        END IF;
        END;
        IF __titan_saved_exception THEN
            IF __titan_saved_state = '22023' OR __titan_saved_state = '55000' OR __titan_saved_state = '22012' OR __titan_saved_state = '45001' OR __titan_saved_state = '2202E' OR __titan_saved_state = '0A000' OR __titan_saved_state = '45000' OR __titan_saved_state = '45002' OR __titan_saved_state = '45003' THEN
                SET v_result = 'caught';
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
