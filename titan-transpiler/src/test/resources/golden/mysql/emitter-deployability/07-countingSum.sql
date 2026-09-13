DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`counting_sum`$$
CREATE FUNCTION `test`.`counting_sum`(p_from INT, p_up_to INT)
RETURNS INT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_sum INT;
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_return_value INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    SET v_sum = 0;
    BEGIN
    DECLARE v_i INT;
    SET v_i = p_from - 1;
    titan_loop_1: LOOP
        SET v_i = v_i + 1;
        IF v_i > p_up_to THEN LEAVE titan_loop_1; END IF;
        IF COALESCE((v_i = (p_from + 2)), FALSE) THEN
            ITERATE titan_loop_1;
        END IF;
        SET v_sum = (v_sum + v_i);
    END LOOP titan_loop_1;
    END;
    BEGIN
    DECLARE v_j INT;
    SET v_j = 0 - 1;
    titan_loop_2: LOOP
        SET v_j = v_j + 1;
        IF v_j > 3 THEN LEAVE titan_loop_2; END IF;
        SET v_sum = (v_sum + 100);
    END LOOP titan_loop_2;
    END;
    SET __titan_return_value = v_sum;
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
