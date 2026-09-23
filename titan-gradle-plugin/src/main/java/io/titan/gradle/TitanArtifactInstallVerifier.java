package io.titan.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Installs a packaged Titan artifact into a scratch/target database and verifies it
 * (plan 4.4, audit G-10).
 *
 * <p>Packaged SQL is located through the package's own structure (direct files, or the
 * {@code -- titan:source-file:} markers inside migration bundles) — never by regex over SQL
 * syntax. Installed-definition drift is detected canonical-to-canonical: the definition that
 * existed before the install is compared with the definition the database reports after the
 * packaged SQL is applied (both rendered by the same server via {@code pg_get_functiondef} /
 * {@code SHOW CREATE}), so formatting differences between packaged text and catalog text can
 * never produce false drift.</p>
 */
public final class TitanArtifactInstallVerifier {

    // JDBC sends a complete SQL statement in one MySQL protocol packet. Leave a small fixed
    // reserve for packet framing so a statement that is merely one byte below the server setting
    // cannot still fail during installation. This is deliberately package-derived, not a global
    // 16 MiB policy: a small Titan package should remain installable on a safely configured small
    // server, while a larger generated routine fails before any package SQL is attempted.
    private static final long MYSQL_PACKET_PROTOCOL_RESERVE_BYTES = 64L * 1024L;

    private TitanArtifactInstallVerifier() {
    }

    public static TitanInstallVerification verify(
            TitanArtifactManifest manifest,
            TitanObjectInventory inventory,
            TitanInstallPlan installPlan,
            Path artifactRoot,
            ScratchDatabase database
    ) {
        return verify(manifest, inventory, installPlan, artifactRoot, List.of(database));
    }

    /**
     * Verifies against one database per dialect ({@code titanVerifyInstall} provisions a scratch
     * container per packaged dialect); dialects without a database are reported as skipped.
     */
    public static TitanInstallVerification verify(
            TitanArtifactManifest manifest,
            TitanObjectInventory inventory,
            TitanInstallPlan installPlan,
            Path artifactRoot,
            List<ScratchDatabase> databases
    ) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(installPlan, "installPlan");
        Objects.requireNonNull(artifactRoot, "artifactRoot");
        Objects.requireNonNull(databases, "databases");
        if (databases.isEmpty()) {
            throw new IllegalArgumentException("install verification requires at least one database");
        }
        Map<String, ScratchDatabase> databasesByDialect = new LinkedHashMap<>();
        for (ScratchDatabase database : databases) {
            databasesByDialect.put(database.dialect(), database);
        }

        List<TitanInstallVerification.Diagnostic> diagnostics = new ArrayList<>();
        List<TitanInstallVerification.Drift> drift = new ArrayList<>();
        Map<String, PackagedSql> packagedSqlByObjectId = packagedSqlByObjectId(inventory, artifactRoot, diagnostics);
        verifyArtifactHashes(manifest, inventory, installPlan, artifactRoot, packagedSqlByObjectId, diagnostics, drift);
        List<TitanInstallVerification.DialectReport> dialectReports = new ArrayList<>();
        for (String dialect : manifest.dialects()) {
            ScratchDatabase database = databasesByDialect.get(dialect);
            if (database == null) {
                dialectReports.add(new TitanInstallVerification.DialectReport(dialect, "skipped", List.of()));
                continue;
            }
            dialectReports.add(verifyDialect(
                    dialect,
                    inventory,
                    installPlan,
                    packagedSqlByObjectId,
                    database,
                    diagnostics,
                    drift));
        }

        String status = diagnostics.isEmpty() && drift.isEmpty() ? "passed" : "failed";
        return new TitanInstallVerification(
                TitanInstallVerification.CURRENT_SCHEMA_VERSION,
                manifest.artifactId(),
                manifest.hashes().manifestContentSha256(),
                installPlan.hashes().planContentSha256(),
                status,
                reportDatabase(databases),
                dialectReports,
                drift,
                diagnostics);
    }

    private static TitanInstallVerification.Database reportDatabase(List<ScratchDatabase> databases) {
        String kind = databases.stream()
                .map(ScratchDatabase::kind)
                .distinct()
                .reduce((left, right) -> left + "+" + right)
                .orElse("scratch");
        String version = databases.size() == 1
                ? databases.get(0).version()
                : databases.stream()
                        .map(database -> database.dialect() + "=" + database.version())
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("not-run");
        return new TitanInstallVerification.Database(kind, version);
    }

    public static TitanInstallVerification verifyAndWrite(
            TitanArtifactManifest manifest,
            TitanObjectInventory inventory,
            TitanInstallPlan installPlan,
            Path artifactRoot,
            ScratchDatabase database
    ) throws IOException {
        return verifyAndWrite(manifest, inventory, installPlan, artifactRoot, List.of(database));
    }

    public static TitanInstallVerification verifyAndWrite(
            TitanArtifactManifest manifest,
            TitanObjectInventory inventory,
            TitanInstallPlan installPlan,
            Path artifactRoot,
            List<ScratchDatabase> databases
    ) throws IOException {
        TitanInstallVerification report = verify(manifest, inventory, installPlan, artifactRoot, databases);
        Files.writeString(
                artifactRoot.resolve("titan-install-verification.json"),
                report.toJson(),
                StandardCharsets.UTF_8);
        return report;
    }

    private static TitanInstallVerification.DialectReport verifyDialect(
            String dialect,
            TitanObjectInventory inventory,
            TitanInstallPlan installPlan,
            Map<String, PackagedSql> packagedSqlByObjectId,
            ScratchDatabase database,
            List<TitanInstallVerification.Diagnostic> diagnostics,
            List<TitanInstallVerification.Drift> drift
    ) {
        List<Path> sqlFiles = sqlFilesOrderedByPlan(dialect, inventory, installPlan, packagedSqlByObjectId);
        if (dialect.equals("mysql") && !preflightMysqlPacketLimit(
                database.connection(), dialect, sqlFiles, diagnostics)) {
            // A package install must be atomic from the verifier's perspective. Do not begin
            // executing migrations after establishing that a later routine cannot traverse the
            // target's JDBC/MySQL packet boundary.
            return new TitanInstallVerification.DialectReport(dialect, "failed", List.of());
        }

        // Canonical-to-canonical drift: capture what the database had BEFORE the install...
        Map<String, String> preInstallDefinitions = new LinkedHashMap<>();
        for (TitanObjectInventory.GeneratedObject object : routineObjects(inventory, dialect)) {
            String definition = installedDefinition(database.connection(), dialect, object);
            if (definition != null) {
                preInstallDefinitions.put(object.id(), definition);
            }
        }

        for (Path sqlFile : sqlFiles) {
            executeScript(database.connection(), dialect, sqlFile, diagnostics);
        }

        // ...and compare it with what the database reports AFTER the packaged definition was
        // applied. Both sides are the server's canonical rendering, so packaged-text formatting
        // (whitespace, qualified names, body normalization) can never produce false drift.
        for (TitanObjectInventory.GeneratedObject object : routineObjects(inventory, dialect)) {
            String preInstall = preInstallDefinitions.get(object.id());
            if (preInstall == null) {
                continue;
            }
            String postInstall = installedDefinition(database.connection(), dialect, object);
            if (postInstall == null || postInstall.equals(preInstall)) {
                continue;
            }
            drift.add(new TitanInstallVerification.Drift(
                    dialect,
                    object.id(),
                    "installedDefinitionCanonicalSha256",
                    TitanArtifactHashes.objectSqlSha256(postInstall),
                    TitanArtifactHashes.objectSqlSha256(preInstall)));
            diagnostics.add(new TitanInstallVerification.Diagnostic(
                    dialect,
                    "verify.installed-definition." + object.id(),
                    object.id(),
                    "TITAN-GAP005-INSTALLED-DEFINITION-DRIFT",
                    "Previously installed routine definition does not match this package's definition "
                            + "(canonical catalog forms compared)."));
        }

        List<TitanInstallVerification.VerifiedObject> verifiedObjects = new ArrayList<>();
        for (TitanObjectInventory.GeneratedObject object : routineObjects(inventory, dialect)) {
            List<String> checks = new ArrayList<>();
            if (routineExists(database.connection(), dialect, object)) {
                checks.add("exists");
            } else {
                diagnostics.add(new TitanInstallVerification.Diagnostic(
                        dialect,
                        "verify.exists." + object.id(),
                        object.id(),
                        "TITAN-GAP005-OBJECT-MISSING",
                        "Expected generated routine was not found in scratch database."));
            }
            if (signatureMatches(database.connection(), dialect, object)) {
                checks.add("signatureMatches");
            } else {
                diagnostics.add(new TitanInstallVerification.Diagnostic(
                        dialect,
                        "verify.signature." + object.id(),
                        object.id(),
                        "TITAN-GAP005-SIGNATURE-MISMATCH",
                        "Installed routine signature does not match the inventory signature."));
            }
            if (installedDefinition(database.connection(), dialect, object) != null) {
                checks.add("installedDefinitionObserved");
            }
            verifiedObjects.add(new TitanInstallVerification.VerifiedObject(object.id(), checks));
        }

        String status = diagnostics.stream().anyMatch(diagnostic -> diagnostic.dialect().equals(dialect))
                || drift.stream().anyMatch(item -> item.dialect().equals(dialect))
                ? "failed"
                : "passed";
        return new TitanInstallVerification.DialectReport(dialect, status, verifiedObjects);
    }

    /**
     * Checks a target MySQL server before any package statement is sent. MySQL reports the limit
     * in bytes, while package migrations can contain a single generated CREATE PROCEDURE that is
     * much larger than the historical 1 MiB default. Letting Connector/J discover that midway
     * through an install produces a transport exception and potentially a partially applied
     * package; this check reports the exact safe minimum first.
     */
    private static boolean preflightMysqlPacketLimit(
            Connection connection,
            String dialect,
            List<Path> sqlFiles,
            List<TitanInstallVerification.Diagnostic> diagnostics
    ) {
        List<String> statements = new ArrayList<>();
        for (Path sqlFile : sqlFiles) {
            try {
                String script = Files.readString(sqlFile, StandardCharsets.UTF_8);
                if (!script.isBlank()) {
                    statements.addAll(TitanSqlScripts.split(script));
                }
            } catch (IOException exception) {
                diagnostics.add(new TitanInstallVerification.Diagnostic(
                        dialect,
                        "preflight.mysql.max-allowed-packet." + sqlFile.getFileName(),
                        "",
                        "TITAN-GAP005-PACKAGE-READ",
                        stableMessage(exception)));
                return false;
            }
        }
        MysqlPacketRequirement requirement = mysqlPacketRequirement(statements);
        if (requirement.requiredBytes() == 0L) {
            return true;
        }
        long configuredBytes;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT @@max_allowed_packet")) {
            if (!resultSet.next()) {
                throw new SQLException("MySQL did not return @@max_allowed_packet");
            }
            configuredBytes = resultSet.getLong(1);
            if (resultSet.wasNull() || configuredBytes <= 0L) {
                throw new SQLException("MySQL returned an invalid @@max_allowed_packet");
            }
        } catch (SQLException exception) {
            diagnostics.add(new TitanInstallVerification.Diagnostic(
                    dialect,
                    "preflight.mysql.max-allowed-packet",
                    "",
                    "TITAN-GAP005-MYSQL-PACKET-PREFLIGHT",
                    "Unable to read MySQL @@max_allowed_packet before package installation: "
                            + stableMessage(exception)));
            return false;
        }
        String error = mysqlPacketPreflightError(configuredBytes, requirement);
        if (error.length() == 0) {
            return true;
        }
        diagnostics.add(new TitanInstallVerification.Diagnostic(
                dialect,
                "preflight.mysql.max-allowed-packet",
                "",
                "TITAN-GAP005-MYSQL-PACKET-PREFLIGHT",
                error));
        return false;
    }

    /** Package-visible pure calculation so the packet boundary has non-Docker regression coverage. */
    static MysqlPacketRequirement mysqlPacketRequirement(List<String> statements) {
        long largestStatementBytes = 0L;
        if (statements != null) {
            for (String statement : statements) {
                if (statement != null) {
                    largestStatementBytes = Math.max(largestStatementBytes,
                            statement.getBytes(StandardCharsets.UTF_8).length);
                }
            }
        }
        long requiredBytes = largestStatementBytes == 0L ? 0L
                : Math.addExact(largestStatementBytes, MYSQL_PACKET_PROTOCOL_RESERVE_BYTES);
        return new MysqlPacketRequirement(largestStatementBytes, requiredBytes);
    }

    /** Empty means a target setting can safely transmit every packaged statement. */
    static String mysqlPacketPreflightError(long configuredBytes, MysqlPacketRequirement requirement) {
        if (requirement == null || requirement.requiredBytes() == 0L
                || configuredBytes >= requirement.requiredBytes()) {
            return "";
        }
        return "MySQL @@max_allowed_packet is " + configuredBytes + " bytes, but this package contains a "
                + requirement.largestStatementBytes() + " byte SQL statement and requires at least "
                + requirement.requiredBytes() + " bytes including protocol reserve. Increase max_allowed_packet "
                + "before installation; no package SQL was executed.";
    }

    record MysqlPacketRequirement(long largestStatementBytes, long requiredBytes) {
        MysqlPacketRequirement {
            if (largestStatementBytes < 0L || requiredBytes < largestStatementBytes) {
                throw new IllegalArgumentException("MySQL packet requirement must be non-negative and monotonic");
            }
        }
    }

    private static List<TitanObjectInventory.GeneratedObject> routineObjects(
            TitanObjectInventory inventory,
            String dialect
    ) {
        return inventory.objects().stream()
                .filter(object -> object.dialect().equals(dialect))
                .filter(object -> object.kind().equals("function") || object.kind().equals("procedure"))
                .toList();
    }

    private static void verifyArtifactHashes(
            TitanArtifactManifest manifest,
            TitanObjectInventory inventory,
            TitanInstallPlan installPlan,
            Path artifactRoot,
            Map<String, PackagedSql> packagedSqlByObjectId,
            List<TitanInstallVerification.Diagnostic> diagnostics,
            List<TitanInstallVerification.Drift> drift
    ) {
        compareHash(
                "all",
                "",
                "sourceInputsSha256",
                manifest.hashes().sourceInputsSha256(),
                TitanArtifactHashes.sourceInputsSha256(manifest.sourceInputs()),
                "hash.source-inputs",
                "TITAN-GAP005-SOURCE-INPUTS-DRIFT",
                "Manifest source input aggregate hash does not match source input entries.",
                diagnostics,
                drift);
        compareHash(
                "all",
                "",
                "manifestContentSha256",
                manifest.hashes().manifestContentSha256(),
                manifest.canonicalContentHash(),
                "hash.manifest",
                "TITAN-GAP005-MANIFEST-DRIFT",
                "Artifact manifest content hash does not match canonical manifest content.",
                diagnostics,
                drift);
        compareHash(
                "all",
                "",
                "inventoryContentSha256",
                inventory.hashes().inventoryContentSha256(),
                inventory.canonicalContentHash(),
                "hash.inventory",
                "TITAN-GAP005-INVENTORY-DRIFT",
                "Object inventory content hash does not match canonical inventory content.",
                diagnostics,
                drift);
        compareHash(
                "all",
                "",
                "installPlanContentSha256",
                installPlan.hashes().planContentSha256(),
                installPlan.canonicalContentHash(),
                "hash.install-plan",
                "TITAN-GAP005-INSTALL-PLAN-DRIFT",
                "Install plan content hash does not match canonical install plan content.",
                diagnostics,
                drift);

        if (manifest.packageMode().equals("direct")) {
            for (TitanArtifactManifest.SourceInput input : manifest.sourceInputs()) {
                Path path = artifactRoot.resolve(input.path());
                try {
                    String sql = Files.readString(path, StandardCharsets.UTF_8);
                    compareHash(
                            input.dialect(),
                            "",
                            "sourceInputSha256",
                            input.sha256(),
                            TitanArtifactHashes.sourceInputSha256(sql),
                            "hash.source." + input.path(),
                            "TITAN-GAP005-SOURCE-DRIFT",
                            "Source SQL input hash does not match packaged SQL.",
                            diagnostics,
                            drift);
                } catch (IOException exception) {
                    diagnostics.add(new TitanInstallVerification.Diagnostic(
                            input.dialect(),
                            "hash.source." + input.path(),
                            "",
                            "TITAN-GAP005-PACKAGE-READ",
                            stableMessage(exception)));
                }
            }
        }

        for (TitanObjectInventory.GeneratedObject object : inventory.objects()) {
            PackagedSql packagedSql = packagedSqlByObjectId.get(object.id());
            if (packagedSql == null) {
                diagnostics.add(new TitanInstallVerification.Diagnostic(
                        object.dialect(),
                        "hash.object." + object.id(),
                        object.id(),
                        "TITAN-GAP005-PACKAGE-READ",
                        "No packaged SQL file contains generated object " + object.id() + "."));
                continue;
            }
            compareHash(
                    object.dialect(),
                    object.id(),
                    "objectSqlSha256",
                    object.sqlHash(),
                    TitanArtifactHashes.objectSqlSha256(packagedSql.sql()),
                    "hash.object." + object.id(),
                    "TITAN-GAP005-OBJECT-DRIFT",
                    "Generated object SQL hash does not match packaged SQL.",
                    diagnostics,
                    drift);
        }

        verifyRollbackScripts(manifest, artifactRoot, diagnostics, drift);
    }

    /**
     * B-5 (TG-BLK-008): each rollback script the manifest references must be present at its
     * package-root path and hash to the manifest's recorded {@code sha256} (over the raw script
     * bytes). A missing or tampered {@code titan-rollback.<dialect>.sql} fails verification with
     * a message naming the dialect and the rollback file — the integrity link the consumer's
     * GAP-005 adapter no longer has to recompute on trust.
     */
    private static void verifyRollbackScripts(
            TitanArtifactManifest manifest,
            Path artifactRoot,
            List<TitanInstallVerification.Diagnostic> diagnostics,
            List<TitanInstallVerification.Drift> drift
    ) {
        for (TitanArtifactManifest.RollbackScript rollback : manifest.rollbackScripts()) {
            Path script = artifactRoot.resolve(rollback.path());
            if (!Files.isRegularFile(script)) {
                diagnostics.add(new TitanInstallVerification.Diagnostic(
                        rollback.dialect(),
                        "hash.rollback." + rollback.path(),
                        "",
                        "TITAN-GAP005-ROLLBACK-MISSING",
                        "Rollback script " + rollback.path() + " referenced by the manifest is missing from the package."));
                continue;
            }
            String actualSha256;
            try {
                actualSha256 = TitanArtifactManifest.sha256Hex(Files.readAllBytes(script));
            } catch (IOException exception) {
                diagnostics.add(new TitanInstallVerification.Diagnostic(
                        rollback.dialect(),
                        "hash.rollback." + rollback.path(),
                        "",
                        "TITAN-GAP005-PACKAGE-READ",
                        stableMessage(exception)));
                continue;
            }
            compareHash(
                    rollback.dialect(),
                    "",
                    "rollbackScriptSha256",
                    rollback.sha256(),
                    actualSha256,
                    "hash.rollback." + rollback.path(),
                    "TITAN-GAP005-ROLLBACK-DRIFT",
                    "Rollback script " + rollback.path() + " hash does not match the manifest entry "
                            + "(the packaged script was modified after packaging).",
                    diagnostics,
                    drift);
        }
    }

    /** The packaged SQL text covering one inventoried object, plus the file it executes from. */
    private record PackagedSql(Path file, String sql) {
    }

    /**
     * Locates each object's packaged SQL through the package's own structure: the object's
     * source input path when it is packaged verbatim (direct mode, runtime migration), or the
     * {@code -- titan:source-file:} marker segment inside the migration bundle. Both are formats
     * this plugin writes itself — SQL syntax is never parsed here.
     */
    private static Map<String, PackagedSql> packagedSqlByObjectId(
            TitanObjectInventory inventory,
            Path artifactRoot,
            List<TitanInstallVerification.Diagnostic> diagnostics
    ) {
        Map<String, PackagedSql> packaged = new LinkedHashMap<>();
        for (TitanObjectInventory.GeneratedObject object : inventory.objects()) {
            try {
                PackagedSql resolved = resolvePackagedSql(artifactRoot, object);
                if (resolved != null) {
                    packaged.put(object.id(), resolved);
                }
            } catch (IOException exception) {
                diagnostics.add(new TitanInstallVerification.Diagnostic(
                        object.dialect(),
                        "hash.object." + object.id(),
                        object.id(),
                        "TITAN-GAP005-PACKAGE-READ",
                        stableMessage(exception)));
            }
        }
        return packaged;
    }

    private static PackagedSql resolvePackagedSql(
            Path artifactRoot,
            TitanObjectInventory.GeneratedObject object
    ) throws IOException {
        Path directPath = artifactRoot.resolve(object.sourceInputPath());
        String sourceFileName = directPath.getFileName().toString();
        if (Files.exists(directPath)) {
            String sql = Files.readString(directPath, StandardCharsets.UTF_8);
            if (sourceFileName.equals(TitanPackageTask.RUNTIME_MIGRATION_FILE_NAME)) {
                sql = stripRuntimeVersionHeader(sql);
            }
            return new PackagedSql(directPath, sql);
        }

        Path bundlePath = artifactRoot.resolve(object.dialect())
                .resolve(TitanPackageTask.ROUTINES_MIGRATION_FILE_NAME);
        if (!Files.exists(bundlePath)) {
            return null;
        }
        String segment = bundleSegment(Files.readString(bundlePath, StandardCharsets.UTF_8), sourceFileName);
        if (segment == null) {
            return null;
        }
        return new PackagedSql(bundlePath, segment);
    }

    /** Extracts one source file's SQL from a migration bundle via the self-owned markers. */
    static String bundleSegment(String bundleSql, String sourceFileName) {
        String marker = TitanPackageTask.BUNDLE_SOURCE_FILE_MARKER + sourceFileName + "\n";
        int start = bundleSql.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int contentStart = start + marker.length();
        int nextMarker = bundleSql.indexOf(TitanPackageTask.BUNDLE_SOURCE_FILE_MARKER, contentStart);
        return nextMarker < 0
                ? bundleSql.substring(contentStart)
                : bundleSql.substring(contentStart, nextMarker);
    }

    /** Drops the {@code -- titan-runtime-version:} header the packager prepends. */
    static String stripRuntimeVersionHeader(String runtimeMigrationSql) {
        if (!runtimeMigrationSql.startsWith(TitanPackageTask.RUNTIME_VERSION_MARKER)) {
            return runtimeMigrationSql;
        }
        String[] parts = runtimeMigrationSql.split("\\R", 2);
        return parts.length < 2 ? "" : parts[1].stripLeading();
    }

    private static void compareHash(
            String dialect,
            String objectId,
            String check,
            String expected,
            String actual,
            String stepId,
            String code,
            String message,
            List<TitanInstallVerification.Diagnostic> diagnostics,
            List<TitanInstallVerification.Drift> drift
    ) {
        if (expected.equals(actual)) {
            return;
        }
        drift.add(new TitanInstallVerification.Drift(dialect, objectId, check, expected, actual));
        diagnostics.add(new TitanInstallVerification.Diagnostic(dialect, stepId, objectId, code, message));
    }

    private static List<Path> sqlFilesOrderedByPlan(
            String dialect,
            TitanObjectInventory inventory,
            TitanInstallPlan installPlan,
            Map<String, PackagedSql> packagedSqlByObjectId
    ) {
        List<TitanObjectInventory.GeneratedObject> objects = inventory.objects().stream()
                .filter(object -> object.dialect().equals(dialect))
                .toList();
        List<Path> paths = new ArrayList<>();
        for (TitanInstallPlan.DialectPlan dialectPlan : installPlan.dialectPlans()) {
            if (!dialectPlan.dialect().equals(dialect)) {
                continue;
            }
            for (TitanInstallPlan.InstallStep step : dialectPlan.steps()) {
                if (!step.kind().equals("runtimeBootstrap") && !step.kind().equals("createOrReplace")) {
                    continue;
                }
                for (TitanObjectInventory.GeneratedObject object : objects) {
                    if (!object.id().equals(step.objectId())) {
                        continue;
                    }
                    PackagedSql packagedSql = packagedSqlByObjectId.get(object.id());
                    if (packagedSql != null && !paths.contains(packagedSql.file())) {
                        paths.add(packagedSql.file());
                    }
                }
            }
        }
        return List.copyOf(paths);
    }

    private static void executeScript(
            Connection connection,
            String dialect,
            Path sqlFile,
            List<TitanInstallVerification.Diagnostic> diagnostics
    ) {
        String script;
        try {
            script = Files.readString(sqlFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            diagnostics.add(new TitanInstallVerification.Diagnostic(
                    dialect,
                    "install.sql." + sqlFile.getFileName(),
                    "",
                    "TITAN-GAP005-PACKAGE-READ",
                    stableMessage(exception)));
            return;
        }
        if (script.isBlank()) {
            return;
        }
        for (String sql : TitanSqlScripts.split(script)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException exception) {
                diagnostics.add(new TitanInstallVerification.Diagnostic(
                        dialect,
                        "install.sql." + sqlFile.getFileName(),
                        "",
                        "TITAN-GAP005-INSTALL-SQL",
                        stableMessage(exception)));
            }
        }
    }

    private static boolean routineExists(
            Connection connection,
            String dialect,
            TitanObjectInventory.GeneratedObject object
    ) {
        String query = switch (dialect) {
            case "postgresql", "postgres", "pg" ->
                    "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = '"
                            + sqlLiteral(object.schema())
                            + "' AND routine_name = '"
                            + sqlLiteral(object.name()) + "'";
            case "mysql" ->
                    "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE() AND routine_name = '"
                            + sqlLiteral(object.name()) + "'";
            default -> "";
        };
        if (query.isBlank()) {
            return metadataRoutineExists(connection, object);
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            if (resultSet.next() && resultSet.getInt(1) > 0) {
                return true;
            }
        } catch (SQLException ignored) {
            return metadataRoutineExists(connection, object);
        }
        if (dialect.equals("mysql")) {
            // MySQL routines may live in a schema other than the connection's default database
            // (the transpiler qualifies routine names with the configured schema).
            String schemaQuery = "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = '"
                    + sqlLiteral(object.schema())
                    + "' AND routine_name = '"
                    + sqlLiteral(object.name()) + "'";
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(schemaQuery)) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            } catch (SQLException ignored) {
                return metadataRoutineExists(connection, object);
            }
        }
        return false;
    }

    private static boolean signatureMatches(
            Connection connection,
            String dialect,
            TitanObjectInventory.GeneratedObject object
    ) {
        int expected = parameterCount(object.signature());
        String query = switch (dialect) {
            case "postgresql", "postgres", "pg" ->
                    "SELECT COUNT(*) FROM information_schema.parameters WHERE specific_schema = '"
                            + sqlLiteral(object.schema())
                            + "' AND specific_name IN (SELECT specific_name FROM information_schema.routines"
                            + " WHERE routine_schema = '"
                            + sqlLiteral(object.schema())
                            + "' AND routine_name = '"
                            + sqlLiteral(object.name())
                            + "') AND parameter_mode IN ('IN', 'INOUT')";
            case "mysql" ->
                    "SELECT COUNT(*) FROM information_schema.parameters WHERE specific_schema IN (DATABASE(), '"
                            + sqlLiteral(object.schema())
                            + "') AND specific_name IN (SELECT specific_name FROM information_schema.routines"
                            + " WHERE routine_schema IN (DATABASE(), '"
                            + sqlLiteral(object.schema())
                            + "') AND routine_name = '"
                            + sqlLiteral(object.name())
                            + "') AND parameter_mode IN ('IN', 'INOUT')";
            default -> "";
        };
        if (query.isBlank()) {
            return true;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            return resultSet.next() && resultSet.getInt(1) == expected;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static String installedDefinition(
            Connection connection,
            String dialect,
            TitanObjectInventory.GeneratedObject object
    ) {
        String query = switch (dialect) {
            case "postgresql", "postgres", "pg" ->
                    "SELECT pg_get_functiondef(p.oid) FROM pg_proc p"
                            + " JOIN pg_namespace n ON n.oid = p.pronamespace"
                            + " WHERE n.nspname = '"
                            + sqlLiteral(object.schema())
                            + "' AND p.proname = '"
                            + sqlLiteral(object.name()) + "' LIMIT 1";
            case "mysql" -> "SHOW CREATE " + object.kind().toUpperCase(Locale.ROOT) + " `" + object.name() + "`";
            default -> "";
        };
        if (query.isBlank()) {
            return null;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            if (!resultSet.next()) {
                return null;
            }
            if (dialect.equals("mysql")) {
                return mysqlCreateStatement(resultSet, object);
            }
            return resultSet.getString(1);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static String mysqlCreateStatement(ResultSet resultSet, TitanObjectInventory.GeneratedObject object)
            throws SQLException {
        String column = object.kind().equals("procedure") ? "Create Procedure" : "Create Function";
        try {
            return resultSet.getString(column);
        } catch (SQLException ignored) {
            return resultSet.getString(3);
        }
    }

    private static boolean metadataRoutineExists(Connection connection, TitanObjectInventory.GeneratedObject object) {
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getFunctions(null, object.schema(), object.name())) {
                if (resultSet.next()) {
                    return true;
                }
            }
            try (ResultSet resultSet = metadata.getProcedures(null, object.schema(), object.name())) {
                return resultSet.next();
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static int parameterCount(String signature) {
        String trimmed = signature == null ? "" : signature.trim();
        if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
            return 0;
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isBlank()) {
            return 0;
        }
        int count = 1;
        int depth = 0;
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    private static String sqlLiteral(String value) {
        return value.replace("'", "''").toLowerCase(Locale.ROOT);
    }

    private static String stableMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    public record ScratchDatabase(String dialect, String version, Connection connection, String kind) {
        public ScratchDatabase {
            requireText(dialect, "dialect");
            requireText(version, "version");
            Objects.requireNonNull(connection, "connection");
            requireText(kind, "kind");
        }

        public ScratchDatabase(String dialect, String version, Connection connection) {
            this(dialect, version, connection, "scratch");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("scratch database requires " + fieldName);
        }
    }
}
