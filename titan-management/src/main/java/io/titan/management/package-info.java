/**
 * EXPERIMENTAL — Titan management/control-plane layer (GAP-006; audit G-11/S10).
 *
 * <p><strong>Status: experimental, no production callers.</strong> This package defines the
 * Titan-owned management records (drafts, validation reports, artifact refs, deployments,
 * registry entries, usage reports), the command/audit/idempotency contracts, and the
 * transactional mutation boundary for the management surface. It was extracted from
 * {@code titan-transpiler} into the standalone {@code titan-management} module because it has
 * zero compile coupling to the transpiler.
 *
 * <p><strong>Storage reality check:</strong> a JDBC-backed store now exists and is
 * <em>dogfooded</em> on Titan-transpiled SQL. {@link io.titan.management.JdbcTransactionalMutationStore}
 * (with {@link io.titan.management.JdbcIdempotencyStore} / {@link io.titan.management.JdbcAuditStore})
 * implements the same interfaces as the file-backed stores over a {@link javax.sql.DataSource},
 * owning the transaction envelope and {@code CALL}ing the mutation routines transpiled from the
 * {@code titan-management-routines} module (shipped here as a packaged SQL resource and installed via
 * {@link io.titan.management.ManagementSchemaInstaller}). It is proven durable and concurrent on live
 * PostgreSQL 16 + MySQL 8.4 by {@code JdbcManagementStoreDogfoodIT}. Read the SQL-backed storage
 * contract described by
 * {@link io.titan.management.ManagementTransactions#importModelDocumentTransactionContract()}
 * (PostgreSQL/MySQL transaction notes, {@code management_*} tables) through that store — it is no
 * longer specification-only. Two routine-design gaps remain (future refinements, not blockers):
 * {@code activateDeployment}'s typed {@code TITAN-MGMT-E03x} preconditions are enforced adapter-side
 * because a void routine cannot return a typed failure, and the {@code import_model_document} routine
 * collapses input/output/document hash into one value (so {@code outcome_hash == input_hash}). See the
 * Status Amendment 2 in {@code GAP-006.md} and {@code docs/archived/management-store-dogfood-spike.md}.
 *
 * <p>The file-backed stores ({@code FileAuditStore}, {@code FileIdempotencyStore},
 * {@code FileTransactionalMutationStore}) remain for non-DB / single-process use. Production bootstrap
 * of the schema + routines is out-of-band (the store cannot self-deploy). The API surface, log formats
 * and diagnostics codes may still change without notice until a real host (CLI/HTTP adapter) lands.
 */
package io.titan.management;
