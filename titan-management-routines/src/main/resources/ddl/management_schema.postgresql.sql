-- Management store schema (B-8 / GAP-006, Phase B1) — PostgreSQL.
--
-- THE BOOTSTRAP SEAM. Titan transpiles the management MUTATION ROUTINES, not these base tables:
-- the store that records deployments cannot deploy itself before it exists, so the schema is a
-- hand-authored first-class artifact applied BEFORE the routines (the routines reference these
-- tables, and ON CONFLICT target-inference needs the unique/primary keys to already exist).
--
-- Columns are derived from titan-management's ManagementRecords (Draft, ArtifactRef, Deployment),
-- ManagementAudit.AuditRecord and ManagementIdempotency.IdempotencyRecord, plus the spike DDL.
--
-- AUDIT TABLE NAME RECONCILIATION: the spike used `management_audit`; ManagementTransactions'
-- TransactionContract (the authoritative durable contract titan-management ships —
-- IMPORT_MODEL_DOCUMENT_ATOMIC_WRITES) names it `management_audit_outcomes`. We adopt the CONTRACT
-- name `management_audit_outcomes` so the dogfooded store's atomic-write set matches the contract
-- exactly. (The B2 routines and the deployability probe use this name.)
--
-- enum-valued columns (status / verification_status / outcome_status) are stored as TEXT holding
-- the enum `id()` string (e.g. 'imported', 'pending', 'active', 'success') — the same stable id
-- the records serialize. Instants are stored as TEXT ISO-8601 (the records round-trip
-- Instant.toString()); this matches the spike and keeps the routines free of timestamp casts.
-- The draft document blob is native JSONB (G4 now drives the ::jsonb cast).

CREATE TABLE management_drafts (
    id              TEXT    NOT NULL PRIMARY KEY,
    workspace_id    TEXT    NOT NULL,
    model_id        TEXT    NOT NULL,
    version         INTEGER NOT NULL,
    status          TEXT    NOT NULL,
    document        JSONB   NOT NULL,
    document_hash   TEXT    NOT NULL,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL,
    metadata        JSONB   NOT NULL
);

CREATE TABLE management_artifact_refs (
    id                          TEXT NOT NULL PRIMARY KEY,
    artifact_id                 TEXT NOT NULL,
    artifact_hash               TEXT NOT NULL,
    manifest_path               TEXT NOT NULL,
    object_inventory_path       TEXT NOT NULL,
    install_plan_path           TEXT NOT NULL,
    install_verification_path   TEXT NOT NULL,
    verification_status         TEXT NOT NULL,
    package_mode                TEXT,
    dialect                     TEXT,
    manifest_content_hash       TEXT,
    object_inventory_hash       TEXT,
    install_plan_hash           TEXT,
    install_verification_hash   TEXT,
    source_inputs_hash          TEXT
);

CREATE TABLE management_deployments (
    id                  TEXT NOT NULL PRIMARY KEY,
    workspace_id        TEXT NOT NULL,
    artifact_ref_id     TEXT NOT NULL,
    environment         TEXT NOT NULL,
    status              TEXT NOT NULL,
    requested_at        TEXT NOT NULL,
    activated_at        TEXT
);

-- Supports the activateDeployment supersede query: find/flip sibling ACTIVE deployments in the
-- same workspace + environment.
CREATE INDEX management_deployments_supersede_idx
    ON management_deployments (workspace_id, environment, status);

-- COMPOSITE PRIMARY KEY (command_name, scope, idempotency_key): required so the routines'
-- INSERT ... ON CONFLICT (command_name, scope, idempotency_key) can infer this exact target.
CREATE TABLE management_idempotency (
    command_name    TEXT NOT NULL,
    scope           TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    input_hash      TEXT NOT NULL,
    outcome_status  TEXT NOT NULL,
    outcome_hash    TEXT NOT NULL,
    result_ref      TEXT,
    error_code      TEXT,
    error_message   TEXT,
    created_at      TEXT NOT NULL,
    PRIMARY KEY (command_name, scope, idempotency_key)
);

CREATE TABLE management_audit_outcomes (
    id              TEXT    NOT NULL PRIMARY KEY,
    sequence        INTEGER NOT NULL,
    command_name    TEXT    NOT NULL,
    status          TEXT    NOT NULL,
    actor_id        TEXT,
    actor_role      TEXT,
    actor_scope     TEXT,
    request_id      TEXT,
    idempotency_key TEXT,
    input_hash      TEXT    NOT NULL,
    output_hash     TEXT,
    error_code      TEXT,
    error_message   TEXT,
    occurred_at     TEXT    NOT NULL
);
