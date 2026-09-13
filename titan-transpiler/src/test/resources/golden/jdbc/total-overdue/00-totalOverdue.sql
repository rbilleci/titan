DELIMITER $$
DROP FUNCTION IF EXISTS `billing`.`total_overdue`$$
CREATE FUNCTION `billing`.`total_overdue`(p_customer_id BIGINT)
RETURNS DECIMAL(38,10)
SQL SECURITY INVOKER
BEGIN
    DECLARE v_total DECIMAL(38,10);
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE __titan_return_value DECIMAL(38,10);
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    SET v_total = 0;
    BEGIN
        DECLARE done_titan_row_amount_0 BOOLEAN DEFAULT FALSE;
        DECLARE cursor_open_titan_row_amount_0 BOOLEAN DEFAULT FALSE;
        DECLARE __titan_row_amount_0 DECIMAL(38,10);
        DECLARE cur_titan_row_amount_0 CURSOR FOR SELECT amount FROM invoices WHERE customer_id = p_customer_id AND status = 'OVERDUE';
        DECLARE EXIT HANDLER FOR SQLEXCEPTION
        BEGIN
            IF cursor_open_titan_row_amount_0 THEN CLOSE cur_titan_row_amount_0; END IF;
            RESIGNAL;
        END;
        DECLARE CONTINUE HANDLER FOR NOT FOUND SET done_titan_row_amount_0 = TRUE;
        OPEN cur_titan_row_amount_0;
        SET cursor_open_titan_row_amount_0 = TRUE;
        read_titan_row_amount_0: LOOP
            FETCH cur_titan_row_amount_0 INTO __titan_row_amount_0;
            IF done_titan_row_amount_0 THEN LEAVE read_titan_row_amount_0; END IF;
        -- titan:source:build/jdbc-golden-fixtures/total-overdue.java:6
        IF v_total IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/jdbc-golden-fixtures/total-overdue.java:6'; END IF;
        -- titan:source:build/jdbc-golden-fixtures/total-overdue.java:6
        IF __titan_row_amount_0 IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at build/jdbc-golden-fixtures/total-overdue.java:6'; END IF;
        SET v_total = (v_total + __titan_row_amount_0);
        END LOOP read_titan_row_amount_0;
        CLOSE cur_titan_row_amount_0;
        SET cursor_open_titan_row_amount_0 = FALSE;
    END;
    SET __titan_return_value = v_total;
    SET time_zone = __titan_saved_time_zone;
    RETURN __titan_return_value;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
