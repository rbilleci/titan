package io.titan.transpiler.tir.generative.shared;

import io.titan.transpiler.tir.LoweredSelectSqlEmitter;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MySQL twin of {@link JoinPostgresSqlHarness} (plan 5.3): same generated join case, same Java
 * reference evaluation, transpiled for MySQL and executed on the singleton MySQL container.
 * Capability-rejected cases (TITAN-E001) are recorded as skips — see
 * {@link MySqlDifferentialSupport}.
 */
public final class JoinMySqlSqlHarness {

    public record JoinRun(
            String generatedJavaSource,
            String sql,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            String capabilitySkipReason,
            Path artifactDir
    ) {
        public boolean capabilitySkipped() {
            return capabilitySkipReason != null;
        }
    }

    public JoinRun runJoinCase(
            Connection connection,
            JoinCaseModel.JoinCase joinCase,
            Path artifactRoot
    ) throws Exception {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(joinCase, "joinCase");
        Objects.requireNonNull(artifactRoot, "artifactRoot");

        var accounts = FixtureCatalog.accountsFixture();
        var plans = switch (joinCase.family()) {
            case INNER_JOIN_DUPLICATE_PLAN_MATCHES,
                 LEFT_JOIN_DUPLICATE_NULL_EXTENSION -> FixtureCatalog.plansFixtureWithDuplicateFree();
            case INNER_JOIN_ACTIVE_ACCOUNTS,
                 INNER_JOIN_PAID_PLANS,
                 LEFT_JOIN_ACTIVE_NULL_EXTENSION,
                 LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS,
                 LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION,
                 TWO_JOIN_ACTIVE_PLAN_FAMILY,
                 TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION,
                 TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_TWO_JOIN_NULL_EXTENSION,
                 LEFT_TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION -> FixtureCatalog.plansFixture();
        };
        var planFamilies = switch (joinCase.family()) {
            case TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION -> FixtureCatalog.planFamiliesFixtureWithDuplicateGrowth();
            case INNER_JOIN_ACTIVE_ACCOUNTS,
                 INNER_JOIN_PAID_PLANS,
                 INNER_JOIN_DUPLICATE_PLAN_MATCHES,
                 LEFT_JOIN_ACTIVE_NULL_EXTENSION,
                 LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS,
                 LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION,
                 TWO_JOIN_ACTIVE_PLAN_FAMILY,
                 TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_JOIN_DUPLICATE_NULL_EXTENSION,
                 LEFT_TWO_JOIN_NULL_EXTENSION,
                 LEFT_TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION -> FixtureCatalog.planFamiliesFixture();
        };
        Path artifactDir = Files.createDirectories(artifactRoot.resolve(joinCase.family().id() + "-mysql-artifacts"));
        String source = JoinPostgresSqlHarness.renderJavaSource(joinCase);
        Files.writeString(artifactDir.resolve("case.json"), joinCase.toStableJson());
        Files.writeString(artifactDir.resolve("generated-case.java"), source);
        Files.writeString(artifactDir.resolve("accounts-fixture.json"), accounts.toStableJson());
        Files.writeString(artifactDir.resolve("plans-fixture.json"), plans.toStableJson());
        Files.writeString(artifactDir.resolve("plan-families-fixture.json"), planFamilies.toStableJson());

        String sql;
        try {
            sql = new LoweredSelectSqlEmitter().emitMySqlSelectSql(source, artifactDir);
        } catch (RuntimeException transpileFailure) {
            String skipReason = MySqlDifferentialSupport.capabilitySkipReason(transpileFailure);
            if (skipReason == null) {
                throw transpileFailure;
            }
            Files.writeString(artifactDir.resolve("capability-skip.txt"), skipReason + System.lineSeparator());
            return new JoinRun(source, null, null, null, null, skipReason, artifactDir);
        }
        MySqlDifferentialSupport.installJoinFixtures(connection, accounts, plans, planFamilies);
        var expected = new JoinReferenceEvaluator().evaluate(joinCase, accounts, plans, planFamilies);
        var actual = execute(connection, sql);
        String mismatch = new ReferenceResultComparison().summarizeMismatch(expected, actual);

        Files.writeString(artifactDir.resolve("mysql-select.sql"), sql + System.lineSeparator());
        Files.writeString(artifactDir.resolve("reference-result.json"), expected.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("sql-result.json"), actual.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("repro.txt"), """
                Replay this bounded join case on the MySQL leg with:

                source ~/.sdkman/bin/sdkman-init.sh && ./gradlew :titan-transpiler:integrationTest \
                  --tests io.titan.transpiler.tir.generative.differential.JoinDifferentialMySqlReplayTest \
                  -Dtitan.phaseb.profile=%s \
                  -Dtitan.phaseb.seed=<seed> \
                  --no-daemon

                Profile: %s
                Family: %s
                """.formatted(joinCase.profileId(), joinCase.profileId(), joinCase.family().id()));
        if (mismatch != null) {
            Files.writeString(artifactDir.resolve("mismatch.txt"), mismatch + System.lineSeparator());
        }
        FailureTriageSupport.Triage triage = FailureTriageSupport.forMismatch(mismatch);
        ArtifactSummaryWriter.write(
                artifactDir,
                "Join MySQL SQL artifact summary",
                mismatch == null ? "green" : "mismatch",
                Map.of(
                        "Phase", "B",
                        "Profile", joinCase.profileId(),
                        "Dialect", "mysql",
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Family", joinCase.family().id()),
                mismatch == null
                        ? List.of("generated-case.java", "mysql-select.sql", "reference-result.json", "sql-result.json", "repro.txt")
                        : List.of("generated-case.java", "mysql-select.sql", "reference-result.json", "sql-result.json", "mismatch.txt", "repro.txt"));
        return new JoinRun(source, sql, expected, actual, mismatch, null, artifactDir);
    }

    private static ReferenceResult execute(Connection connection, String sql) throws Exception {
        List<ResultRow> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("account_id", rs.getObject(1));
                values.put("plan_name", rs.getObject(2));
                rows.add(new ResultRow(values));
            }
        }
        rows.sort(Comparator.comparing(ResultRow::toStableJson));
        return new ReferenceResult(List.copyOf(rows));
    }
}
