DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`do_while_countdown`$$
CREATE FUNCTION `test`.`do_while_countdown`(p_start INT)
RETURNS INT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_total INT;
    DECLARE v_n INT;
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_return_value INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    SET v_total = 0;
    SET v_n = p_start;
    BEGIN
    DECLARE __titan_dowhile_cond_4290 BOOLEAN;
    DECLARE __titan_dowhile_first_4290 BOOLEAN;
    SET __titan_dowhile_first_4290 = TRUE;
    titan_loop_1: LOOP
        IF __titan_dowhile_first_4290 THEN
            SET __titan_dowhile_first_4290 = FALSE;
        ELSE
            SET __titan_dowhile_cond_4290 = (v_n > 0);
            IF (__titan_dowhile_cond_4290 IS NULL OR (__titan_dowhile_cond_4290 = FALSE)) THEN
                LEAVE titan_loop_1;
            END IF;
        END IF;
        SET v_n = (v_n - 1);
        IF (v_n = 2) THEN
            ITERATE titan_loop_1;
        END IF;
        SET v_total = (v_total + v_n);
    END LOOP titan_loop_1;
    END;
    SET __titan_return_value = v_total;
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
