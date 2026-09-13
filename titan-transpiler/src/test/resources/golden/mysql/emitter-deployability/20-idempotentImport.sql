DELIMITER $$
DROP PROCEDURE IF EXISTS `test`.`idempotent_import`$$
CREATE PROCEDURE `test`.`idempotent_import`(IN p_id INT, IN p_name TEXT, IN p_key TEXT)
SQL SECURITY INVOKER
BEGIN
    DECLARE v_already_applied BOOLEAN;
    DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET time_zone = __titan_saved_time_zone;
        RESIGNAL;
    END;
    SET time_zone = '+00:00';
    proc_body: BEGIN
        SELECT EXISTS (SELECT `gate_idempotency`.`idempotency_key` FROM `test`.`gate_idempotency` WHERE COALESCE((`gate_idempotency`.`idempotency_key` = p_key), FALSE)) INTO v_already_applied;
        IF COALESCE(v_already_applied, FALSE) THEN
            LEAVE proc_body;
        END IF;
        INSERT INTO `test`.`gate_events` (`id`, `name`) VALUES (p_id, p_name) ON DUPLICATE KEY UPDATE `name` = p_name;
        INSERT INTO `test`.`gate_audit` (`idempotency_key`, `name`) VALUES (p_key, p_name);
        INSERT INTO `test`.`gate_idempotency` (`idempotency_key`) VALUES (p_key);
    END;
    SET time_zone = __titan_saved_time_zone;
END$$
DELIMITER ;
