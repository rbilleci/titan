package io.titan.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Installs the packaged Titan artifacts into a verification database and verifies them
 * (plan 4.4, audit G-10): the {@code titan-install-verification.json} written by
 * {@code titanPackage} as {@code pending} is replaced with a real
 * {@code passed}/{@code failed} report from {@link TitanArtifactInstallVerifier}.
 *
 * <p>Two verification targets: a configured JDBC database
 * ({@code titan.verification.jdbcUrl}, drivers from the {@code titanJdbc} configuration), or —
 * the default — one scratch Testcontainers database per packaged dialect.</p>
 *
 * <p>Verification runs against a live database, so the task is never UP-TO-DATE and never
 * cached; every property is {@link Internal}.</p>
 */
@DisableCachingByDefault(because = "Installs into and verifies a live database; results must never be replayed from cache")
public abstract class TitanVerifyInstallTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getSqlInputDir();

    @Internal
    public abstract DirectoryProperty getArtifactDir();

    @Internal
    public abstract Property<String> getMode();

    @Internal
    public abstract Property<String> getTitanVersion();

    @Internal
    public abstract Property<String> getJdbcUrl();

    @Internal
    public abstract Property<String> getUsername();

    @Internal
    public abstract Property<String> getPassword();

    @Internal
    public abstract Property<String> getDialect();

    @Internal
    public abstract Property<Boolean> getFailOnVerificationError();

    @Classpath
    public abstract ConfigurableFileCollection getJdbcDriverClasspath();

    @TaskAction
    public void run() throws Exception {
        Path inputRoot = getSqlInputDir().get().getAsFile().toPath();
        Path artifactRoot = getArtifactDir().get().getAsFile().toPath();
        if (!Files.exists(inputRoot) || !Files.exists(artifactRoot)) {
            getLogger().lifecycle("Titan install verification skipped: no packaged artifacts found under {}", artifactRoot);
            return;
        }

        String mode = getMode().getOrElse("migration").toLowerCase(Locale.ROOT);
        String titanVersion = getTitanVersion().getOrElse("unspecified").trim();
        List<TitanPackagedArtifacts.DialectInput> dialectInputs =
                TitanPackagedArtifacts.collectDialectInputs(inputRoot);
        if (dialectInputs.isEmpty()) {
            getLogger().lifecycle("Titan install verification skipped: no dialect SQL inputs under {}", inputRoot);
            return;
        }

        // Rebuilt from the same inputs titanPackage used (which this task depends on); the
        // verifier additionally compares the packaged files' hashes against this inventory, so
        // a stale or tampered package surfaces as drift.
        Map<String, TitanArtifactMetadataFile.ArtifactRow> metadataRows =
                TitanArtifactMetadataFile.read(inputRoot.resolve(TitanArtifactMetadataFile.FILE_NAME));
        TitanPackagedArtifacts.Result artifacts = TitanPackagedArtifacts.build(
                dialectInputs, inputRoot, mode, titanVersion, metadataRows);

        TitanInstallVerification report;
        String configuredJdbcUrl = getJdbcUrl().getOrNull();
        if (configuredJdbcUrl != null && !configuredJdbcUrl.isBlank()) {
            report = verifyAgainstConfiguredDatabase(artifacts, artifactRoot, configuredJdbcUrl);
        } else {
            report = verifyAgainstScratchContainers(artifacts, artifactRoot);
        }

        getLogger().lifecycle("Titan install verification {}: {}", report.status(),
                artifactRoot.resolve("titan-install-verification.json"));
        if (!"passed".equals(report.status()) && getFailOnVerificationError().getOrElse(true)) {
            throw new GradleException("Titan install verification failed for " + report.artifactId()
                    + ". See " + artifactRoot.resolve("titan-install-verification.json")
                    + " for per-object drift and diagnostics.");
        }
    }

    private TitanInstallVerification verifyAgainstConfiguredDatabase(
            TitanPackagedArtifacts.Result artifacts,
            Path artifactRoot,
            String jdbcUrl
    ) throws Exception {
        String dialect = getDialect().getOrElse("postgresql").toLowerCase(Locale.ROOT);
        getLogger().lifecycle("Titan install verification against configured database {} ({})",
                TitanJdbcConnections.redactedJdbcUrl(jdbcUrl), dialect);
        try (TitanJdbcConnections.JdbcSession session = TitanJdbcConnections.open(
                getJdbcDriverClasspath().getFiles(),
                jdbcUrl,
                getUsername().getOrNull(),
                getPassword().getOrNull())) {
            return TitanArtifactInstallVerifier.verifyAndWrite(
                    artifacts.manifest(),
                    artifacts.inventory(),
                    artifacts.installPlan(),
                    artifactRoot,
                    List.of(new TitanArtifactInstallVerifier.ScratchDatabase(
                            dialect,
                            TitanJdbcConnections.redactedJdbcUrl(jdbcUrl),
                            session.connection(),
                            "jdbc")));
        }
    }

    private TitanInstallVerification verifyAgainstScratchContainers(
            TitanPackagedArtifacts.Result artifacts,
            Path artifactRoot
    ) throws Exception {
        if (!TitanScratchDatabases.dockerAvailable()) {
            throw new GradleException(TitanScratchDatabases.dockerUnavailableMessage());
        }
        List<TitanScratchDatabases.Scratch> scratches = new ArrayList<>();
        try {
            List<TitanArtifactInstallVerifier.ScratchDatabase> databases = new ArrayList<>();
            for (String dialect : artifacts.manifest().dialects()) {
                if (io.titan.transpiler.tir.DialectId.parse(dialect).isEmpty()) {
                    getLogger().lifecycle("Titan install verification: no scratch container image for dialect '{}'; reported as skipped", dialect);
                    continue;
                }
                getLogger().lifecycle("Titan install verification: starting scratch {} container", dialect);
                TitanScratchDatabases.Scratch scratch =
                        TitanScratchDatabases.start(dialect, getJdbcDriverClasspath().getFiles());
                scratches.add(scratch);
                TitanScratchDatabases.prepareSchemas(
                        scratch.connection(),
                        dialect,
                        schemasFor(artifacts, dialect));
                databases.add(new TitanArtifactInstallVerifier.ScratchDatabase(
                        dialect, scratch.version(), scratch.connection()));
            }
            return TitanArtifactInstallVerifier.verifyAndWrite(
                    artifacts.manifest(),
                    artifacts.inventory(),
                    artifacts.installPlan(),
                    artifactRoot,
                    databases);
        } finally {
            for (TitanScratchDatabases.Scratch scratch : scratches) {
                scratch.close();
            }
        }
    }

    private static List<String> schemasFor(TitanPackagedArtifacts.Result artifacts, String dialect) {
        Set<String> schemas = new LinkedHashSet<>();
        artifacts.inventory().objects().stream()
                .filter(object -> object.dialect().equals(dialect))
                .forEach(object -> schemas.add(object.schema()));
        return List.copyOf(schemas);
    }
}
