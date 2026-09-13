-- Management store schema (B-8 / GAP-006, Phase B1) — MySQL 8.4.
--
-- THE BOOTSTRAP SEAM (see the PostgreSQL sibling for the full rationale). Titan transpiles the
-- management MUTATION ROUTINES, not these base tables; the schema is hand-authored and applied
-- BEFORE the routines so ON CONFLICT (-> ON DUPLICATE KEY) target inference and FK/PK references
-- resolve.
--
-- AUDIT TABLE NAME: `management_audit_outcomes` (reconciled to ManagementTransactions'
-- TransactionContract, not the spike's `management_audit`).
--
-- MySQL specifics vs the PostgreSQL sibling:
--   * PRIMARY KEY / composite-key / indexed columns are VARCHAR(n) (MySQL cannot key an
--     unbounded TEXT column without a prefix length); 191 keeps utf8mb4 keys inside the legacy
--     767-byte index limit. Non-key string columns are TEXT.
--   * The draft document blob is native JSON (G4 drives CAST(... AS JSON)). MySQL JSON columns
--     cannot carry an inline DEFAULT and cannot be part of an index, so document/metadata stay
--     plain JSON NOT NULL.
--   * enum-valued columns are VARCHAR holding the enum id() string; instants are VARCHAR ISO-8601.

CREATE TABLE management_drafts (
    id              VARCHAR(191) NOT NULL PRIMARY KEY,
    workspace_id    VARCHAR(191) NOT NULL,
    model_id        VARCHAR(191) NOT NULL,
    version         INT          NOT NULL,
    status          VARCHAR(64)  NOT NULL,
    document        JSON         NOT NULL,
    document_hash   VARCHAR(191) NOT NULL,
    created_at      VARCHAR(64)  NOT NULL,
    updated_at      VARCHAR(64)  NOT NULL,
    metadata        JSON         NOT NULL
);

CREATE TABLE management_artifact_refs (
    id                          VARCHAR(191) NOT NULL PRIMARY KEY,
    artifact_id                 VARCHAR(191) NOT NULL,
    artifact_hash               VARCHAR(191) NOT NULL,
    manifest_path               TEXT         NOT NULL,
    object_inventory_path       TEXT         NOT NULL,
    install_plan_path           TEXT         NOT NULL,
    install_verification_path   TEXT         NOT NULL,
    verification_status         VARCHAR(64)  NOT NULL,
    package_mode                VARCHAR(64),
    dialect                     VARCHAR(64),
    manifest_content_hash       VARCHAR(191),
    object_inventory_hash       VARCHAR(191),
    install_plan_hash           VARCHAR(191),
    install_verification_hash   VARCHAR(191),
    source_inputs_hash          VARCHAR(191)
);

CREATE TABLE management_deployments (
    id                  VARCHAR(191) NOT NULL PRIMARY KEY,
    workspace_id        VARCHAR(191) NOT NULL,
    artifact_ref_id     VARCHAR(191) NOT NULL,
    environment         VARCHAR(191) NOT NULL,
    status              VARCHAR(64)  NOT NULL,
    requested_at        VARCHAR(64)  NOT NULL,
    activated_at        VARCHAR(64)
);

CREATE INDEX management_deployments_supersede_idx
    ON management_deployments (workspace_id, environment, status);

-- COMPOSITE PRIMARY KEY (command_name, scope, idempotency_key) — required for ON DUPLICATE KEY
-- inference parity with the PostgreSQL ON CONFLICT target.
CREATE TABLE management_idempotency (
    command_name    VARCHAR(120) NOT NULL,
    scope           VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    input_hash      VARCHAR(191) NOT NULL,
    outcome_status  VARCHAR(64)  NOT NULL,
    outcome_hash    VARCHAR(191) NOT NULL,
    result_ref      VARCHAR(191),
    error_code      VARCHAR(120),
    error_message   TEXT,
    created_at      VARCHAR(64)  NOT NULL,
    PRIMARY KEY (command_name, scope, idempotency_key)
);

CREATE TABLE management_audit_outcomes (
    id              VARCHAR(191) NOT NULL PRIMARY KEY,
    sequence        INT          NOT NULL,
    command_name    VARCHAR(120) NOT NULL,
    status          VARCHAR(64)  NOT NULL,
    actor_id        VARCHAR(191),
    actor_role      VARCHAR(191),
    actor_scope     VARCHAR(191),
    request_id      VARCHAR(191),
    idempotency_key VARCHAR(120),
    input_hash      VARCHAR(191) NOT NULL,
    output_hash     VARCHAR(191),
    error_code      VARCHAR(120),
    error_message   TEXT,
    occurred_at     VARCHAR(64)  NOT NULL
);
