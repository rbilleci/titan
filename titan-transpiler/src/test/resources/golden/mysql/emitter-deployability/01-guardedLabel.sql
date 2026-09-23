DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`guarded_label`$$
CREATE FUNCTION `test`.`guarded_label`(p_input TEXT, p_fail_fast BOOLEAN)
RETURNS TEXT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_label TEXT;
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_time_zone_pinned BOOLEAN DEFAULT FALSE;
    DECLARE __titan_return_value TEXT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
        RESIGNAL;
    END;
    IF @@session.time_zone <> '+00:00' THEN
        SET time_zone = '+00:00';
        SET __titan_time_zone_pinned = TRUE;
    END IF;
    SET v_label = 'start';
    BEGIN
        DECLARE __titan_saved_exception BOOLEAN DEFAULT FALSE;
        DECLARE __titan_saved_state CHAR(5) DEFAULT NULL;
        DECLARE __titan_saved_message TEXT DEFAULT NULL;
        DECLARE v_error TEXT DEFAULT NULL;
        BEGIN
        DECLARE EXIT HANDLER FOR SQLSTATE '55000'
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET __titan_saved_state = '55000';
            SET v_error = __titan_saved_message;
        END;
        DECLARE EXIT HANDLER FOR SQLEXCEPTION
        BEGIN
            GET DIAGNOSTICS CONDITION 1 __titan_saved_state = RETURNED_SQLSTATE, __titan_saved_message = MESSAGE_TEXT;
            SET __titan_saved_exception = TRUE;
            SET v_error = __titan_saved_message;
        END;
        IF COALESCE(p_fail_fast, FALSE) THEN
            SIGNAL SQLSTATE '55000' SET MESSAGE_TEXT = 'TITAN_GATE_FAIL_FAST';
        END IF;
        SET v_label = CONCAT(COALESCE(CAST(v_label AS CHAR), 'null'), COALESCE(CAST(CONCAT(COALESCE(CAST(':' AS CHAR), 'null'), COALESCE(CAST(p_input AS CHAR), 'null')) AS CHAR), 'null'));
        END;
        IF __titan_saved_exception THEN
            IF __titan_saved_state = '55000' THEN
                SET v_label = 'caught';
                SET __titan_saved_exception = FALSE;
                SET __titan_saved_state = NULL;
            END IF;
        END IF;
        SET v_label = CONCAT(COALESCE(CAST(v_label AS CHAR), 'null'), COALESCE(CAST(':done' AS CHAR), 'null'));
        IF __titan_saved_exception THEN
            SET __titan_saved_message = IFNULL(__titan_saved_message, 'Unhandled exception in try block');
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = __titan_saved_message;
        END IF;
    END;
    SET __titan_return_value = v_label;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
    RETURN __titan_return_value;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
