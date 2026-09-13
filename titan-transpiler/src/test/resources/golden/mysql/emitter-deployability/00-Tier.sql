CREATE TABLE IF NOT EXISTS `test`.`__enum_emitter_deployability_fixture_tier` (
    `ordinal` INT PRIMARY KEY,
    `name` VARCHAR(191) NOT NULL,
    UNIQUE KEY `uq_enum_name` (`name`)
);

INSERT INTO `test`.`__enum_emitter_deployability_fixture_tier` (`ordinal`, `name`) VALUES
    (0, 'BASIC'),
    (1, 'PRO')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);