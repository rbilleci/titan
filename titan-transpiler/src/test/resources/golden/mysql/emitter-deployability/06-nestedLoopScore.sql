DELIMITER $$
DROP FUNCTION IF EXISTS `test`.`nested_loop_score`$$
CREATE FUNCTION `test`.`nested_loop_score`(p_rows INT, p_cols INT)
RETURNS INT
SQL SECURITY INVOKER
BEGIN
    DECLARE v_score INT;
    DECLARE v_r INT;
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_time_zone_pinned BOOLEAN DEFAULT FALSE;
    DECLARE __titan_return_value INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
        RESIGNAL;
    END;
    IF @@session.time_zone <> '+00:00' THEN
        SET time_zone = '+00:00';
        SET __titan_time_zone_pinned = TRUE;
    END IF;
    SET v_score = 0;
    SET v_r = 0;
    titan_loop_1: WHILE COALESCE((v_r < p_rows), FALSE) DO
        BEGIN
        DECLARE v_c INT;
        SET v_c = 0;
        titan_loop_2: WHILE COALESCE((v_c < p_cols), FALSE) DO
            SET v_c = (v_c + 1);
            IF (v_c = 3) THEN
                ITERATE titan_loop_2;
            END IF;
            IF (v_c = 5) THEN
                LEAVE titan_loop_2;
            END IF;
            SET v_score = (v_score + v_c);
        END WHILE titan_loop_2;
        SET v_r = (v_r + 1);
        IF (v_score > 100) THEN
            LEAVE titan_loop_1;
        END IF;
        END;
    END WHILE titan_loop_1;
    SET __titan_return_value = v_score;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
    RETURN __titan_return_value;
    IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;
END$$
DELIMITER ;
