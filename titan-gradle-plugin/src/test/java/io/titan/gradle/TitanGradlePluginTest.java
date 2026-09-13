package io.titan.gradle;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.work.DisableCachingByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TitanGradlePluginTest {

    @TempDir
    Path tempDir;

    @Test
    void statefulAndDiagnosticTasksDeclareTheirNonCacheablePolicy() {
        for (Class<?> taskType : List.of(TitanPackageTask.class, TitanVerifyInstallTask.class, TitanTask.class)) {
            DisableCachingByDefault policy = taskType.getAnnotation(DisableCachingByDefault.class);
            assertNotNull(policy, taskType.getSimpleName());
            assertFalse(policy.because().isBlank());
            assertNull(taskType.getAnnotation(CacheableTask.class));
        }
    }

    @Test
    void registersExtensionAndTitanTasks() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply(JavaPlugin.class);
        project.getPlugins().apply(TitanGradlePlugin.class);

        assertNotNull(project.getExtensions().findByName(TitanGradlePlugin.EXTENSION_NAME));
        TitanExtension extension = (TitanExtension) project.getExtensions().getByName(TitanGradlePlugin.EXTENSION_NAME);
        assertFalse(extension.getTranspiler().getDebugMode().get());

        // GAP G-9: tasks are registered lazily; names are visible without realizing the tasks.
        assertTrue(project.getTasks().getNames().containsAll(
                List.of("titanIntrospect", "titanGenerate", "titanTranspile", "titanPackage", "titanVerifyInstall")));

        assertInstanceOf(TitanTranspileTask.class, project.getTasks().getByName("titanTranspile"));
        assertInstanceOf(TitanPackageTask.class, project.getTasks().getByName("titanPackage"));
        assertInstanceOf(TitanVerifyInstallTask.class, project.getTasks().getByName("titanVerifyInstall"));

        assertTrue(dependencyTaskNames(project, "titanGenerate").contains("titanIntrospect"));
        assertTrue(dependencyTaskNames(project, "titanTranspile").contains("titanGenerate"));
        assertTrue(dependencyTaskNames(project, "titanTranspile").contains("compileJava"));
        assertTrue(dependencyTaskNames(project, "titanPackage").contains("titanTranspile"));
        // Plan 4.4: titanVerifyInstall verifies the packaged artifacts, so it depends on
        // titanPackage and is never UP-TO-DATE (it runs against a live database).
        assertTrue(dependencyTaskNames(project, "titanVerifyInstall").contains("titanPackage"));
        assertFalse(outputsCanBeUpToDate(project, "titanVerifyInstall"));
        assertFalse(outputsAreCacheable(project, "titanVerifyInstall"));
    }

    @Test
    void jdbcBackedIntrospectAndGenerateTasksAreNeverUpToDateOrCached() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPlugins().apply(JavaPlugin.class);
        project.getPlugins().apply(TitanGradlePlugin.class);
        TitanExtension extension = (TitanExtension) project.getExtensions().getByName(TitanGradlePlugin.EXTENSION_NAME);
        extension.getDatabase().getJdbcUrl().set("jdbc:postgresql://db.example.com:5432/app");

        assertFalse(outputsCanBeUpToDate(project, "titanIntrospect"),
                "live database schema is not a tracked input; JDBC mode must never be UP-TO-DATE");
        assertFalse(outputsCanBeUpToDate(project, "titanGenerate"),
                "live database schema is not a tracked input; JDBC mode must never be UP-TO-DATE");
        assertFalse(outputsAreCacheable(project, "titanIntrospect"),
                "live database schema is not a tracked input; JDBC mode must never be restored from cache");
        assertFalse(outputsAreCacheable(project, "titanGenerate"),
                "live database schema is not a tracked input; JDBC mode must never be restored from cache");
    }

    @Test
    void ddlModeKeepsFileBasedUpToDatenessAndCacheability() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPlugins().apply(JavaPlugin.class);
        project.getPlugins().apply(TitanGradlePlugin.class);
        TitanExtension extension = (TitanExtension) project.getExtensions().getByName(TitanGradlePlugin.EXTENSION_NAME);
        extension.getDatabase().getJdbcUrl().set("jdbc:postgresql://db.example.com:5432/app");
        Path ddlDir = Files.createDirectories(tempDir.resolve("ddl"));
        Files.writeString(ddlDir.resolve("schema.sql"), "CREATE TABLE t (id integer);\n", StandardCharsets.UTF_8);
        extension.getDatabase().getDdlDir().set(ddlDir.toString());

        assertTrue(outputsCanBeUpToDate(project, "titanIntrospect"));
        assertTrue(outputsCanBeUpToDate(project, "titanGenerate"));
        assertTrue(outputsAreCacheable(project, "titanIntrospect"));
        assertTrue(outputsAreCacheable(project, "titanGenerate"));
    }

    @Test
    void deterministicTasksAreCacheable() {
        for (Class<?> taskType : List.of(TitanIntrospectTask.class, TitanGenerateTask.class, TitanTranspileTask.class)) {
            assertNotNull(taskType.getAnnotation(CacheableTask.class),
                    taskType.getSimpleName() + " must be @CacheableTask (GAP S8)");
        }
    }

    @Test
    void credentialPropertiesAreInternalAndNeverTaskInputs() throws Exception {
        for (Class<?> taskType : List.of(TitanIntrospectTask.class, TitanGenerateTask.class)) {
            for (String getter : List.of("getUsername", "getPassword")) {
                Method method = taskType.getMethod(getter);
                assertNotNull(method.getAnnotation(Internal.class),
                        taskType.getSimpleName() + "." + getter + " must be @Internal");
                assertNull(method.getAnnotation(Input.class),
                        taskType.getSimpleName() + "." + getter + " must not enter Gradle input fingerprints");
            }
        }
    }

    @Test
    void introspectionLogsRedactedJdbcUrl() {
        assertEquals("jdbc:postgresql://db.example.com:5432/app",
                TitanIntrospectTask.redactedJdbcUrl("jdbc:postgresql://db.example.com:5432/app?user=titan&password=secret"));
        assertEquals("jdbc:mysql://db.example.com:3306/app",
                TitanIntrospectTask.redactedJdbcUrl("jdbc:mysql://titan:secret@db.example.com:3306/app"));
    }

    @Test
    void migrationsDefaultUnderBuildDirectoryNotSourceTree() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPlugins().apply(TitanGradlePlugin.class);
        TitanExtension extension = (TitanExtension) project.getExtensions().getByName(TitanGradlePlugin.EXTENSION_NAME);

        String migrationsDir = extension.getDeployment().getMigrationsDir().get();
        String expected = project.getLayout().getBuildDirectory().dir("titan/migrations").get().getAsFile().getAbsolutePath();
        assertEquals(expected, migrationsDir,
                "GAP G-5: the build must not write migrations into the source tree by default");
    }

    @Test
    void registersResolvableTitanJdbcConfiguration() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply(TitanGradlePlugin.class);

        Configuration titanJdbc = project.getConfigurations().getByName(TitanGradlePlugin.JDBC_CONFIGURATION_NAME);
        assertTrue(titanJdbc.isCanBeResolved());
        assertFalse(titanJdbc.isCanBeConsumed());
    }

    @Test
    void wiresGeneratedCatalogSourcesIntoMainSourceSet() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPlugins().apply(JavaPlugin.class);
        project.getPlugins().apply(TitanGradlePlugin.class);

        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        Set<File> srcDirs = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getJava().getSrcDirs();
        File catalogOutputDir = project.getLayout().getBuildDirectory()
                .dir("generated/sources/titan").get().getAsFile();
        assertTrue(srcDirs.contains(catalogOutputDir),
                "GAP G-5: generated catalog sources must compile via sourceSets.main.java, found " + srcDirs);

        assertTrue(dependencyTaskNames(project, "compileJava").contains("titanGenerate"),
                "srcDir(taskProvider) must carry the implicit titanGenerate dependency for compileJava");
    }

    // WS-C Phase 3 Rung 3 audit fix (boundary): the titanPermissiveScopesReport ratchet must scan EXACTLY
    // the source set the transpiler lowers/emits (design D2/§6: the report counts every IDENTIFIER/
    // RAW_FRAGMENT splice that survives). A permissive splice in generated (titanGenerate-output) code was
    // transpiled yet invisible to the report because the report's source FileTree omitted the generate
    // output dir. Lock that the report task's resolved source files are a SUPERSET of the transpile task's
    // (report ⊇ transpile) — including the generated sources — so a splice can never be emitted unaudited.
    @Test
    void permissiveScopesReportScansTheSameSourceSetAsTranspile() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPlugins().apply(JavaPlugin.class);
        project.getPlugins().apply(TitanGradlePlugin.class);

        TitanTranspileTask transpile = (TitanTranspileTask) project.getTasks().getByName("titanTranspile");
        TitanPermissiveScopesReportTask report =
                (TitanPermissiveScopesReportTask) project.getTasks().getByName("titanPermissiveScopesReport");

        // The report's source FileTree must depend on titanGenerate (its output dir is part of the source
        // set), exactly like the transpile task — the wiring that makes the report scan the SAME sources the
        // transpiler emits. (Resolving getFiles() eagerly is unsupported: the generate-output provider can
        // only be read after titanGenerate runs; the build-dependency is the structural proof of the wiring.)
        Set<String> transpileSourceDeps = buildDependencyTaskNames(transpile.getSourceFiles());
        Set<String> reportSourceDeps = buildDependencyTaskNames(report.getSourceFiles());
        assertTrue(transpileSourceDeps.contains("titanGenerate"),
                "sanity: transpile sources include the titanGenerate output; deps=" + transpileSourceDeps);
        assertTrue(reportSourceDeps.contains("titanGenerate"),
                "the permissive-scopes report must scan the titanGenerate output dir like transpile (ratchet ⊇ "
                        + "transpile); deps=" + reportSourceDeps);
        assertTrue(reportSourceDeps.containsAll(transpileSourceDeps),
                "report source build-deps must cover transpile's (report scans a superset of transpile sources); "
                        + "report=" + reportSourceDeps + " transpile=" + transpileSourceDeps);
        // And the task itself depends on titanGenerate so the generated sources exist before it scans them.
        assertTrue(dependencyTaskNames(project, "titanPermissiveScopesReport").contains("titanGenerate"),
                "titanPermissiveScopesReport must depend on titanGenerate");
    }

    /** The names of tasks that producing {@code collection}'s contents depends on (its build-dependencies). */
    private static Set<String> buildDependencyTaskNames(org.gradle.api.file.FileCollection collection) {
        return collection.getBuildDependencies().getDependencies(null).stream()
                .map(Task::getName).collect(Collectors.toSet());
    }

    // B-6 (TG-BLK-002): transpiler.strictMode is a no-op (transpile-time validation is
    // unconditional since Phase 0.8; the pipeline never reads the flag). It is retained only so
    // existing build scripts compile, and is marked @Deprecated(forRemoval=true) on both the
    // extension property and the mirrored task input so consumers are told to drop it.
    @Test
    void strictModeGetterIsDeprecatedForRemovalOnExtensionAndTask() throws Exception {
        Method extensionGetter = TitanExtension.Transpiler.class.getMethod("getStrictMode");
        Deprecated extensionDeprecated = extensionGetter.getAnnotation(Deprecated.class);
        assertNotNull(extensionDeprecated, "TitanExtension.Transpiler.getStrictMode() must be @Deprecated (B-6)");
        assertTrue(extensionDeprecated.forRemoval(),
                "the no-op strictMode extension property must be marked forRemoval (B-6)");

        Method taskGetter = TitanTranspileTask.class.getMethod("getStrictMode");
        Deprecated taskDeprecated = taskGetter.getAnnotation(Deprecated.class);
        assertNotNull(taskDeprecated, "TitanTranspileTask.getStrictMode() must be @Deprecated (B-6)");
        assertTrue(taskDeprecated.forRemoval(),
                "the no-op strictMode task input must be marked forRemoval (B-6)");
    }

    // B-7 (TG-BLK-001): transpiler.strictWraparound is exposed and wired through to the
    // titanTranspile task input. It defaults to false (matching the pipeline/lowerer default) and
    // the extension value flows to the task.
    @Test
    void strictWraparoundDefaultsToFalseAndIsWiredToTranspileTask() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPlugins().apply(JavaPlugin.class);
        project.getPlugins().apply(TitanGradlePlugin.class);
        TitanExtension extension = (TitanExtension) project.getExtensions().getByName(TitanGradlePlugin.EXTENSION_NAME);

        assertFalse(extension.getTranspiler().getStrictWraparound().get(),
                "B-7: strictWraparound must default to false (fail-loud parity), matching the pipeline default");

        extension.getTranspiler().getStrictWraparound().set(true);
        TitanTranspileTask transpile = (TitanTranspileTask) project.getTasks().getByName("titanTranspile");
        assertTrue(transpile.getStrictWraparound().get(),
                "B-7: the extension's strictWraparound value must flow to the titanTranspile task input");
    }

    // B-7: strictWraparound is a real, declared Gradle @Input on the task — it participates in the
    // up-to-date check, so changing it forces a retranspile.
    @Test
    void strictWraparoundIsADeclaredTaskInput() throws Exception {
        Method getter = TitanTranspileTask.class.getMethod("getStrictWraparound");
        assertNotNull(getter.getAnnotation(Input.class),
                "B-7: TitanTranspileTask.getStrictWraparound() must be a declared @Input");
    }

    private static Set<String> dependencyTaskNames(Project project, String taskName) {
        Task task = project.getTasks().getByName(taskName);
        return task.getTaskDependencies().getDependencies(task).stream()
                .map(Task::getName)
                .collect(Collectors.toSet());
    }

    private static boolean outputsCanBeUpToDate(Project project, String taskName) {
        TaskInternal task = (TaskInternal) project.getTasks().getByName(taskName);
        return task.getOutputs().getUpToDateSpec().isSatisfiedBy(task);
    }

    private static boolean outputsAreCacheable(Project project, String taskName) {
        TaskInternal task = (TaskInternal) project.getTasks().getByName(taskName);
        return task.getOutputs().getCacheIfSpecs().stream().allMatch(spec -> spec.isSatisfiedBy(task))
                && task.getOutputs().getDoNotCacheIfSpecs().stream().noneMatch(spec -> spec.isSatisfiedBy(task));
    }
}
