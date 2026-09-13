package io.titan.transpiler.tir;

import io.titan.introspect.SchemaModel;
import java.util.List;

/**
 * The curated JDBC-input fixture corpus shared by {@link JdbcGoldenTest} (which pins the exact
 * emitted bytes against checked-in golden {@code .sql} files) and {@link JdbcEmitterDeployabilityIT}
 * (which proves each artifact deploys and executes on its live source-dialect database). These are
 * the worked examples of {@code docs/transpilable-jdbc-subset.md} §6.1 and §6.2 — ordinary
 * {@code java.sql} JDBC methods transpiled, per the §1.1a single-dialect-native reframe, to a stored
 * routine on their <em>one</em> configured source dialect.
 *
 * <p><b>Why a dedicated JDBC corpus (not {@link GoldenSqlCorpus}).</b> The DSL corpus transpiles
 * every fixture to <em>both</em> PostgreSQL and MySQL, because the DSL is dual-dialect. JDBC-input is
 * <em>single-dialect-native</em>: a JDBC engineer targets one database and their embedded SQL is
 * already valid for it by construction, so each fixture here carries a {@link #sourceDialect()} and is
 * transpiled to <em>only</em> that dialect. {@link JdbcGoldenTest} byte-compares against
 * {@code src/test/resources/golden/jdbc/<id>/<artifact>.sql} (regen with
 * {@code -Dtitan.jdbcgolden.update=true}). The emitted form is the dynamic-{@code EXECUTE} read/cursor
 * shape (design §2c decision 3), not the spec's static sketch.</p>
 *
 * <p><b>Fixture-source vs spec-sketch deviations</b> (the sketches are "illustrative of shape" — the
 * exact identifiers/casing follow the emitter):
 * <ul>
 *   <li>the §6.1 fee rates are {@code BigDecimal} parameters rather than {@code new BigDecimal("0.001")}
 *       literals, because object construction in a transpiled body is outside the Titan P0 subset
 *       ({@code TITAN-E001}, design-document §12.1); the multiply <em>shape</em> ({@code v_balance *
 *       p_gold_rate}) is identical to the sketch's {@code v_balance * 0.001};</li>
 *   <li>{@code @StoredProcedure}/{@code @StoredFunction} carry no {@code schema} element (the
 *       annotations are bare markers); the schema ({@code billing}) is supplied to the pipeline, as in
 *       {@link JdbcEndToEndTest}.</li>
 * </ul>
 * Everything load-bearing is verbatim from the spec: the {@code if (!rs.next()) throw} single-row read
 * guard (I-4), the {@code executeUpdate} (I-6), and the {@code while (rs.next())} cursor accumulate
 * (I-5).</p>
 */
public final class JdbcGoldenCorpus {

    /**
     * One single-dialect JDBC fixture: id (golden directory name), the standard-JDBC Java source, the
     * one configured source dialect it transpiles to, and an optional introspected {@link SchemaModel}
     * catalog. The catalog is supplied only when the fixture needs it (§6.3 I-7 generated-key recovery
     * resolves the {@code RETURNING} key column from it); {@code null} for fixtures that do not.
     */
    public record Fixture(String id, String source, DialectId sourceDialect, SchemaModel schemaModel) {
        public Fixture(String id, String source, DialectId sourceDialect) {
            this(id, source, sourceDialect, null);
        }
    }

    private JdbcGoldenCorpus() {
    }

    /**
     * The catalog for the §6.3 {@code create-invoice} fixture: an {@code invoices(id auto-increment,
     * customer_id, amount)} table in the corpus {@code billing} schema, so the I-7 lowerer resolves the
     * {@code RETURNING id} / {@code LAST_INSERT_ID()} key column from it.
     */
    public static SchemaModel invoicesModel() {
        SchemaModel.ColumnMeta id = new SchemaModel.ColumnMeta(
                "id", "BIGINT", false, null, null, null, null, null, List.of(), true);
        SchemaModel.ColumnMeta customerId = new SchemaModel.ColumnMeta(
                "customer_id", "BIGINT", false, null, null, null);
        SchemaModel.ColumnMeta amount = new SchemaModel.ColumnMeta(
                "amount", "NUMERIC", false, 38, 10, null);
        SchemaModel.TableMeta invoices = new SchemaModel.TableMeta(
                SCHEMA, "invoices", List.of(id, customerId, amount));
        return new SchemaModel(List.of(invoices));
    }

    /** The schema the JDBC corpus transpiles into — same as the deployability IT deploys into. */
    public static final String SCHEMA = "billing";

    /**
     * Every JDBC fixture with a golden, in stable order; golden directories are keyed by
     * {@link Fixture#id()}. {@link #CREATE_INVOICE} (§6.3, I-7 generated keys) transpiles now that the
     * JDBC path carries a Catalog: it lowers to the trigger-immune {@code INSERT … RETURNING <col> INTO}
     * (PostgreSQL) form, with the {@code RETURNING id} column resolved from {@link #invoicesModel()}.
     */
    public static List<Fixture> fixtures() {
        return List.of(COMPUTE_ACCOUNT_FEE, TOTAL_OVERDUE, CREATE_INVOICE);
    }

    /**
     * §6.1 — single-row read + compute + update ({@code computeAccountFee}-style), PostgreSQL source.
     * The {@code if (!rs.next()) throw} guard then two {@code rs.getX} reads lower to one multi-column
     * native {@code EXECUTE … INTO} (I-4) with an {@code IF NOT FOUND} guard; the ternary fee compute
     * lowers to a {@code CASE}; the second prepared statement lowers to a dynamic {@code EXECUTE} for the
     * UPDATE (I-6). Connection elided from the signature (§2.2).
     */
    public static final Fixture COMPUTE_ACCOUNT_FEE = new Fixture("compute-account-fee", """
            import titan.dsl.StoredProcedure;
            import java.sql.*;
            import java.math.BigDecimal;

            class ComputeAccountFee {
                @StoredProcedure
                public static void computeAccountFee(Connection c, long accountId, BigDecimal goldRate, BigDecimal stdRate)
                        throws SQLException {
                    BigDecimal balance;
                    String tier;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT balance, tier FROM accounts WHERE id = ?")) {
                        ps.setLong(1, accountId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalStateException("account not found");
                            }
                            balance = rs.getBigDecimal("balance");
                            tier = rs.getString("tier");
                        }
                    }
                    BigDecimal fee = "GOLD".equals(tier)
                            ? balance.multiply(goldRate)
                            : balance.multiply(stdRate);
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE accounts SET fee = ? WHERE id = ?")) {
                        up.setBigDecimal(1, fee);
                        up.setLong(2, accountId);
                        up.executeUpdate();
                    }
                }
            }
            """, DialectId.POSTGRESQL);

    /**
     * §6.2 — multi-row aggregation ({@code while (rs.next())} accumulate), MySQL source. The
     * {@code while (rs.next())} lowers to a {@code RawCursorStatement} (I-5) whose body — the
     * {@code total = total.add(rs.getBigDecimal("amount"))} accumulator — lowers through the existing
     * control-flow lowering, the constant SQL inlined into a static MySQL cursor; {@code return total}
     * is a function {@code RETURN}. Connection elided from the signature (§2.2).
     */
    public static final Fixture TOTAL_OVERDUE = new Fixture("total-overdue", """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.math.BigDecimal;

            class TotalOverdue {
                @StoredFunction
                public static BigDecimal totalOverdue(Connection c, long customerId) throws SQLException {
                    BigDecimal total = BigDecimal.ZERO;
                    PreparedStatement ps = c.prepareStatement(
                        "SELECT amount FROM invoices WHERE customer_id = ? AND status = 'OVERDUE'");
                    ps.setLong(1, customerId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        total = total.add(rs.getBigDecimal("amount"));
                    }
                    return total;
                }
            }
            """, DialectId.MYSQL);

    /**
     * §6.3 — insert with generated keys (I-7), PostgreSQL source. Now that the JDBC path carries a
     * Catalog ({@link #invoicesModel()}), this transpiles to the spec's trigger-immune, driver-faithful
     * {@code INSERT … RETURNING <key-col> INTO <local>} form with the {@code RETURNING id} column
     * <b>resolved from the Catalog</b> — the JDBC ordinal {@code 1} in {@code getLong(1)} does not name
     * the key column. Unlike a session {@code lastval()}/{@code LAST_INSERT_ID()} read (which returns
     * the last <i>sequence</i> value — a different row's id under an {@code AFTER INSERT} trigger),
     * {@code RETURNING} reads the actual inserted row's key, so the recovered value is correct even under
     * a trigger that advances another sequence. The trigger-immunity is proven live by
     * {@code JdbcEmitterDeployabilityIT.createInvoiceGeneratedKeyDeploysAndReturnsActualPkOnPostgres}.
     */
    public static final Fixture CREATE_INVOICE = new Fixture("create-invoice", """
            import titan.dsl.StoredFunction;
            import java.sql.*;
            import java.math.BigDecimal;

            class CreateInvoice {
                @StoredFunction
                public static long createInvoice(Connection c, long customerId, BigDecimal amount)
                        throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO invoices (customer_id, amount) VALUES (?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, customerId);
                    ps.setBigDecimal(2, amount);
                    ps.executeUpdate();
                    ResultSet keys = ps.getGeneratedKeys();
                    keys.next();
                    return keys.getLong(1);
                }
            }
            """, DialectId.POSTGRESQL, invoicesModel());
}
