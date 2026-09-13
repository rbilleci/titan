package io.titan.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import java.util.List;

// GAP G-9: tasks are registered lazily (tasks.register), all locations flow through
// ProjectLayout/Provider chains, and no Project instance is captured by anything that runs at
// execution time — the plugin is configuration-cache compatible (verified by a TestKit test that
// reuses the cache on the second build).
public class TitanGradlePlugin implements Plugin<Project> {
    public static final String EXTENSION_NAME = "titan";

    /**
     * Resolvable configuration the introspection tasks ({@code titanIntrospect},
     * {@code titanGenerate}) load JDBC drivers from in JDBC mode:
     * {@code dependencies { titanJdbc("org.postgresql:postgresql:42.7.13") }}.
     */
    public static final String JDBC_CONFIGURATION_NAME = "titanJdbc";

    @Override
    public void apply(Project project) {
        ProjectLayout layout = project.getLayout();
        ProviderFactory providers = project.getProviders();

        TitanExtension extension = project.getExtensions().create(EXTENSION_NAME, TitanExtension.class);
        project.getPluginManager().apply(TitanCodegenPlugin.class);
        extension.getTranspiler().getTargets().convention(List.of("postgresql", "mysql"));
        extension.getTranspiler().getOutputDir().convention(layout.getBuildDirectory().dir("generated/sql/titan").map(d -> d.getAsFile().getAbsolutePath()));
        extension.getTranspiler().getStrictMode().convention(true);
        // Audit B-7 (TG-BLK-001): default false matches the pipeline/lowerer default
        // ("fail-loud parity" — integer overflow raises rather than silently wrapping).
        extension.getTranspiler().getStrictWraparound().convention(false);
        extension.getTranspiler().getObservability().convention(false);
        extension.getTranspiler().getDebugMode().convention(false);
        extension.getTranspiler().getSensitiveColumns().convention(List.of());
        // WS-C: build-level raw-SQL safety; strict by default (injection-proof, secure by default).
        // The surgical @SqlSafety(PERMISSIVE) method/class annotation overrides it (narrowest scope wins).
        extension.getTranspiler().getSqlSafety().convention("strict");
        extension.getDeployment().getMode().convention("migration");
        extension.getVerification().getFailOnVerificationError().convention(true);
        // GAP G-5: generated migrations default to the build directory; writing into the source
        // tree (e.g. src/main/resources/db/migration) is an explicit opt-in via migrationsDir.
        extension.getDeployment().getMigrationsDir().convention(layout.getBuildDirectory().dir("titan/migrations").map(d -> d.getAsFile().getAbsolutePath()));

        Configuration titanJdbc = project.getConfigurations().getByName(JDBC_CONFIGURATION_NAME);
        Provider<RegularFile> schemaJson = layout.getBuildDirectory().file("titan/schema.json");
        Provider<Directory> sqlOutputDir = extension.getTranspiler().getOutputDir()
                .map(path -> layout.getProjectDirectory().dir(path));
        Provider<Directory> migrationsOutputDir = extension.getDeployment().getMigrationsDir()
                .map(path -> layout.getProjectDirectory().dir(path));
        TaskProvider<TitanGenerateTask> generate = project.getTasks().named("titanGenerate", TitanGenerateTask.class);

        TaskProvider<TitanTranspileTask> transpile = project.getTasks().register("titanTranspile", TitanTranspileTask.class, task -> {
            task.setGroup("titan");
            task.setDescription("Transpile annotated Java entry points to SQL routines.");
            task.dependsOn(generate);
            task.getTargets().set(extension.getTranspiler().getTargets());
            task.getSchemas().set(extension.getDatabase().getSchemas());
            task.getStrictMode().set(extension.getTranspiler().getStrictMode());
            task.getStrictWraparound().set(extension.getTranspiler().getStrictWraparound());
            task.getSqlSafety().set(extension.getTranspiler().getSqlSafety());
            task.getObservability().set(extension.getTranspiler().getObservability());
            task.getDebugMode().set(extension.getTranspiler().getDebugMode());
            task.getSensitiveColumns().set(extension.getTranspiler().getSensitiveColumns());
            task.getSchemaJsonFile().set(schemaJson);
            task.getOutputDir().set(sqlOutputDir);
            task.getSourceFiles().from(
                    layout.getProjectDirectory().dir("src/main/java").getAsFileTree().matching(spec -> spec.include("**/*.java")),
                    generate.flatMap(TitanGenerateTask::getOutputDir).map(dir -> dir.getAsFileTree().matching(spec -> spec.include("**/*.java")))
            );
        });

        // WS-C Phase 1: report-only JDBC compatibility linter. Standalone (does not gate the build):
        // it measures which annotated methods' java.sql usage is transpilable/passthrough/rejected.
        Provider<RegularFile> jdbcCompatReportFile =
                layout.getBuildDirectory().file("reports/titan/jdbc-compat-report.json");
        TaskProvider<TitanJdbcCompatReportTask> jdbcCompatReport = project.getTasks().register("titanJdbcCompatReport", TitanJdbcCompatReportTask.class, task -> {
            task.setGroup("titan");
            task.setDescription("Report standard java.sql JDBC usage compatibility (transpilable/passthrough/rejected) per method.");
            task.getSqlSafety().set(extension.getTranspiler().getSqlSafety());
            task.getFailOnRejected().convention(false);
            task.getReportFile().set(jdbcCompatReportFile);
            task.getSourceFiles().from(
                    layout.getProjectDirectory().dir("src/main/java").getAsFileTree().matching(spec -> spec.include("**/*.java")));
        });

        // NEW WORK (design-document.md §11.1): report-only "effective permissive scopes" audit.
        // Standalone (does not gate the build): it enumerates every entry-point method whose effective
        // sqlSafety resolves to permissive — including methods relaxed only by a class-level
        // @SqlSafety(PERMISSIVE) or the build-level sqlSafety flag (the non-greppable cases), so a
        // broad relaxation cannot quietly cover a forgotten method.
        Provider<RegularFile> permissiveScopesReportFile =
                layout.getBuildDirectory().file("reports/titan/permissive-scopes.json");
        TaskProvider<TitanPermissiveScopesReportTask> permissiveScopesReport = project.getTasks().register("titanPermissiveScopesReport", TitanPermissiveScopesReportTask.class, task -> {
            task.setGroup("titan");
            task.setDescription("Report every entry-point method whose effective sqlSafety is permissive (method/class/build-level source), including non-greppable class-/build-level relaxations.");
            task.getSqlSafety().set(extension.getTranspiler().getSqlSafety());
            task.getReportFile().set(permissiveScopesReportFile);
            // The ratchet must see EXACTLY the source set the transpiler lowers/emits (design D2/§6: the
            // report counts every IDENTIFIER/RAW_FRAGMENT splice that survives). So consume the SAME
            // providers the transpile task does — src/main/java AND the titanGenerate output dir — otherwise
            // a permissive splice method in generated (or other transpiled) sources would be emitted yet
            // invisible to the report (an under-count). Kept aligned with the transpile task above.
            task.dependsOn(generate);
            task.getSourceFiles().from(
                    layout.getProjectDirectory().dir("src/main/java").getAsFileTree().matching(spec -> spec.include("**/*.java")),
                    generate.flatMap(TitanGenerateTask::getOutputDir).map(dir -> dir.getAsFileTree().matching(spec -> spec.include("**/*.java")))
            );
        });

        TaskProvider<TitanPackageTask> titanPackage = project.getTasks().register("titanPackage", TitanPackageTask.class, task -> {
            task.setGroup("titan");
            task.setDescription("Package emitted SQL into deterministic migration artifacts.");
            task.dependsOn(transpile);
            task.getSqlInputDir().set(sqlOutputDir);
            task.getMode().set(extension.getDeployment().getMode());
            // Evaluated lazily so a version assigned after plugin application is honored; the
            // configuration cache fixes the value when it stores the task graph, so no Project
            // state is read at execution time.
            task.getTitanVersion().set(providers.provider(() -> String.valueOf(project.getVersion())));
            task.getOutputDir().set(migrationsOutputDir);
        });

        // Plan 4.4 (audit G-10): real install verification. titanPackage writes the verification
        // report as 'pending'; this task replaces it with a passed/failed report after deploying
        // the package to a configured JDBC target or to scratch containers.
        project.getTasks().register("titanVerifyInstall", TitanVerifyInstallTask.class, task -> {
            task.setGroup("titan");
            task.setDescription("Install packaged Titan SQL into a verification database (configured JDBC target or scratch containers) and verify objects, signatures and drift.");
            task.dependsOn(titanPackage);
            task.getSqlInputDir().set(sqlOutputDir);
            task.getArtifactDir().set(migrationsOutputDir);
            task.getMode().set(extension.getDeployment().getMode());
            task.getTitanVersion().set(providers.provider(() -> String.valueOf(project.getVersion())));
            task.getJdbcUrl().set(extension.getVerification().getJdbcUrl());
            task.getUsername().set(extension.getVerification().getUsername());
            task.getPassword().set(extension.getVerification().getPassword());
            task.getDialect().set(extension.getVerification().getDialect());
            task.getFailOnVerificationError().set(extension.getVerification().getFailOnVerificationError());
            task.getJdbcDriverClasspath().from(titanJdbc);
            // Verification runs against a live database: never UP-TO-DATE, never cached.
            task.getOutputs().upToDateWhen(t -> false);
            task.getOutputs().doNotCacheIf("install verification runs against a live database", t -> true);
        });

        project.getPlugins().withType(JavaPlugin.class, plugin -> {
            TaskProvider<JavaCompile> compileJava = project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class);
            transpile.configure(task -> {
                task.dependsOn(compileJava);
                task.getClasspathFiles().from(
                        compileJava.map(JavaCompile::getClasspath),
                        compileJava.flatMap(JavaCompile::getDestinationDirectory));
            });

            // WS-C: the JDBC linter reads the same compile classpath so java.sql / titan.dsl types
            // resolve (and depends on compileJava so the classpath is materialized).
            jdbcCompatReport.configure(task -> {
                task.dependsOn(compileJava);
                task.getClasspathFiles().from(
                        compileJava.map(JavaCompile::getClasspath),
                        compileJava.flatMap(JavaCompile::getDestinationDirectory));
            });

            // The permissive-scopes audit reads the same compile classpath so titan.dsl (@SqlSafety,
            // the entry-point annotations) resolves on the parse (and depends on compileJava so the
            // classpath is materialized), exactly like the JDBC compat report above.
            permissiveScopesReport.configure(task -> {
                task.dependsOn(compileJava);
                task.getClasspathFiles().from(
                        compileJava.map(JavaCompile::getClasspath),
                        compileJava.flatMap(JavaCompile::getDestinationDirectory));
            });

            project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure(task -> task.dependsOn(titanPackage));
        });
    }

}
