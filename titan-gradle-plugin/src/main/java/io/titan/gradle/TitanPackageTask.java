package io.titan.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Plan 4.4 (audit G-10/S9): packaging is driven by the transpiler's structured metadata
// (.titan-artifacts.tsv + RuntimeStrategy.runtimeObjects()), not by regex over the emitted SQL.
// The CREATE-object regex (which missed triggers and events and matched inside string literals),
// the name-substring dependency inference (which invented edges like
// get_user_orders -> get_user) and the bare-comma signature splitting (which broke
// numeric(10,2)) are deleted.
@DisableCachingByDefault(because = "Packaging preserves user migrations and shares verification metadata with install verification")
public abstract class TitanPackageTask extends DefaultTask {

    // Titan-owned migrations are Flyway REPEATABLE migrations (R__ prefix): the emitted SQL is
    // CREATE-OR-REPLACE-shaped, so re-applying when the checksum changes is safe and no
    // previously-applied versioned migration is ever rewritten. Flyway runs repeatables in
    // description order, so the numeric infix guarantees the runtime bundle
    // ("titan 010 runtime") applies before the routine bundle ("titan 020 routines").
    static final String RUNTIME_MIGRATION_FILE_NAME = "R__titan_010_runtime.sql";
    static final String ROUTINES_MIGRATION_FILE_NAME = "R__titan_020_routines.sql";
    // Stale titan-owned bundles emitted by the legacy versioned scheme. Matched exactly so
    // user-authored migrations are never deleted.
    private static final Pattern LEGACY_TITAN_MIGRATION_PATTERN = Pattern.compile(
            "V0000000000000000__titan_runtime\\.sql|V[0-9a-f]{16}__titan_routines\\.sql");

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSqlInputDir();

    @Input
    public abstract Property<String> getMode();

    @Input
    public abstract Property<String> getTitanVersion();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void run() throws IOException {
        Path inputRoot = getSqlInputDir().get().getAsFile().toPath();
        if (!Files.exists(inputRoot)) {
            getLogger().lifecycle("Titan package skipped: SQL input directory does not exist: {}", inputRoot);
            return;
        }

        String mode = getMode().getOrElse("migration").toLowerCase(Locale.ROOT);
        if (!mode.equals("migration") && !mode.equals("direct")) {
            throw new IllegalArgumentException("Unsupported titan.deployment.mode: " + mode + " (expected 'migration' or 'direct')");
        }

        Path outputRoot = getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(outputRoot);
        String titanVersion = getTitanVersion().getOrElse("unspecified").trim();

        List<TitanPackagedArtifacts.DialectInput> dialectInputs =
                TitanPackagedArtifacts.collectDialectInputs(inputRoot);

        int packaged = 0;
        for (TitanPackagedArtifacts.DialectInput input : dialectInputs) {
            String dialect = input.dialect();
            List<Path> sqlFiles = input.sqlFiles();
            Path dialectOutputDir = outputRoot.resolve(dialect);
            Files.createDirectories(dialectOutputDir);

            if (mode.equals("direct")) {
                for (Path sqlFile : sqlFiles) {
                    Files.copy(sqlFile, dialectOutputDir.resolve(sqlFile.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    packaged++;
                }
                continue;
            }

            deleteLegacyTitanMigrations(dialectOutputDir);

            String runtimeSql = TitanPackagedArtifacts.runtimeMigrationSqlForDialect(dialect);
            if (!runtimeSql.isBlank()) {
                Path runtimeMigrationPath = dialectOutputDir.resolve(RUNTIME_MIGRATION_FILE_NAME);
                String runtimeMigrationWithVersion = withRuntimeVersion(runtimeSql, titanVersion);
                String existingVersion = readRuntimeVersion(runtimeMigrationPath);
                String existingRuntimeMigration = Files.exists(runtimeMigrationPath)
                        ? Files.readString(runtimeMigrationPath, StandardCharsets.UTF_8)
                        : null;
                if (!Files.exists(runtimeMigrationPath)
                        || !titanVersion.equals(existingVersion)
                        || !runtimeMigrationWithVersion.equals(existingRuntimeMigration)) {
                    Files.writeString(runtimeMigrationPath, runtimeMigrationWithVersion, StandardCharsets.UTF_8);
                    packaged++;
                }
            }

            String bundledSql = bundleSql(sqlFiles);
            if (bundledSql.isBlank()) {
                continue;
            }

            Path migrationPath = dialectOutputDir.resolve(ROUTINES_MIGRATION_FILE_NAME);
            String existingBundle = Files.exists(migrationPath)
                    ? Files.readString(migrationPath, StandardCharsets.UTF_8)
                    : null;
            if (!bundledSql.equals(existingBundle)) {
                Files.writeString(migrationPath, bundledSql, StandardCharsets.UTF_8);
                packaged++;
            }
        }

        if (!dialectInputs.isEmpty()) {
            Map<String, TitanArtifactMetadataFile.ArtifactRow> metadataRows =
                    TitanArtifactMetadataFile.read(inputRoot.resolve(TitanArtifactMetadataFile.FILE_NAME));
            TitanPackagedArtifacts.Result artifacts = TitanPackagedArtifacts.build(
                    dialectInputs, inputRoot, mode, titanVersion, metadataRows);
            TitanArtifactManifest manifest = artifacts.manifest();
            TitanObjectInventory objectInventory = artifacts.inventory();
            TitanInstallPlan installPlan = artifacts.installPlan();
            TitanInstallVerification installVerification = TitanInstallVerification.planned(manifest, installPlan);
            Files.writeString(outputRoot.resolve("titan-artifact.json"), manifest.toJson(), StandardCharsets.UTF_8);
            Files.writeString(outputRoot.resolve("titan-object-inventory.json"), objectInventory.toJson(), StandardCharsets.UTF_8);
            Files.writeString(outputRoot.resolve("titan-install-plan.json"), installPlan.toJson(), StandardCharsets.UTF_8);
            Files.writeString(outputRoot.resolve("titan-install-verification.json"), installVerification.toJson(), StandardCharsets.UTF_8);

            // Plan 4.4 / production gate G4: the executable rollback artifact, one per dialect,
            // at the package root next to the manifest — never inside the dialect migration
            // directories, so migration runners cannot mistake it for a migration. B-5: these are
            // the exact bytes the manifest integrity-links (path + statementCount + sha256), so
            // the on-disk script and the manifest entry can never diverge.
            for (TitanRollbackScript.RenderedRollbackScript rollbackScript : artifacts.rollbackScripts()) {
                Files.writeString(
                        outputRoot.resolve(rollbackScript.path()),
                        rollbackScript.sql(),
                        StandardCharsets.UTF_8);
            }
            packaged++;
        }

        getLogger().lifecycle("Titan package completed in '{}' mode. Wrote {} artifact(s) to {}", mode, packaged, outputRoot);
    }

    private static void deleteLegacyTitanMigrations(Path dialectOutputDir) throws IOException {
        List<Path> legacyFiles;
        try (Stream<Path> stream = Files.list(dialectOutputDir)) {
            legacyFiles = stream
                    .filter(path -> LEGACY_TITAN_MIGRATION_PATTERN.matcher(path.getFileName().toString()).matches())
                    .toList();
        }
        for (Path legacyFile : legacyFiles) {
            Files.delete(legacyFile);
        }
    }

    // The bundle embeds each generated file behind a titan:source-file marker; the verifier
    // re-derives per-file segments from these self-owned markers (never from SQL syntax).
    static final String BUNDLE_SOURCE_FILE_MARKER = "-- titan:source-file:";

    private static String bundleSql(List<Path> sqlFiles) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (Path sqlFile : sqlFiles) {
            String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);
            if (sql.isBlank()) {
                continue;
            }

            builder.append(BUNDLE_SOURCE_FILE_MARKER)
                    .append(sqlFile.getFileName())
                    .append("\n");
            builder.append(sql);
            if (!sql.endsWith("\n")) {
                builder.append("\n");
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    static final String RUNTIME_VERSION_MARKER = "-- titan-runtime-version:";

    private static String withRuntimeVersion(String runtimeSql, String titanVersion) {
        String normalized = runtimeSql.endsWith("\n") ? runtimeSql : runtimeSql + "\n";
        return RUNTIME_VERSION_MARKER + titanVersion + "\n\n" + normalized;
    }

    private static String readRuntimeVersion(Path runtimeMigrationPath) throws IOException {
        if (!Files.exists(runtimeMigrationPath)) {
            return null;
        }
        String[] lines = Files.readString(runtimeMigrationPath, StandardCharsets.UTF_8).split("\\R", 2);
        if (lines.length == 0) {
            return null;
        }
        if (!lines[0].startsWith(RUNTIME_VERSION_MARKER)) {
            return null;
        }
        return lines[0].substring(RUNTIME_VERSION_MARKER.length()).trim();
    }
}
