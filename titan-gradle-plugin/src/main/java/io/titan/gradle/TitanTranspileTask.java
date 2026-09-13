package io.titan.gradle;

import io.titan.introspect.SchemaJsonReader;
import io.titan.introspect.SchemaModel;
import io.titan.transpiler.tir.TranspilationPipeline;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// GAP S8: this task previously fingerprinted its own inputs (including hand-rolled classpath and
// implementation hashes) into ".titan-transpile.fingerprint" and early-returned inside the action.
// That duplicated — and fought — Gradle's incremental build: a deleted output stayed missing while
// the fingerprint matched, and the task could never be relocatably cached. Incrementality is now
// exclusively Gradle's responsibility via the declared inputs/outputs below; the action always
// emits deterministically when it runs.
@CacheableTask
public abstract class TitanTranspileTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Classpath
    public abstract ConfigurableFileCollection getClasspathFiles();

    @Input
    public abstract ListProperty<String> getTargets();

    @Input
    public abstract ListProperty<String> getSchemas();

    /**
     * @deprecated No-op since Phase 0.8 (audit B-6 / TG-BLK-002): transpile-time validation is
     *     unconditional and {@link TranspilationPipeline#transpile} never reads its {@code strictMode}
     *     parameter. Mirrors {@link TitanExtension.Transpiler#getStrictMode()}; retained only so
     *     existing wiring compiles. Scheduled for removal alongside the pipeline-side parameter
     *     (core item B-6).
     */
    @Input
    @Optional
    @Deprecated(forRemoval = true)
    public abstract Property<Boolean> getStrictMode();

    /** Audit B-7 (TG-BLK-001): see {@link TitanExtension.Transpiler#getStrictWraparound()}. */
    @Input
    public abstract Property<Boolean> getStrictWraparound();

    @Input
    public abstract Property<Boolean> getObservability();

    @Input
    public abstract Property<Boolean> getDebugMode();

    @Input
    public abstract ListProperty<String> getSensitiveColumns();

    /**
     * Build-level raw-SQL safety mode ({@code "strict"} default | {@code "permissive"}), threaded to
     * the transpile pipeline so the JDBC lowerer's build-level resolver default honors it (a method or
     * class {@code @SqlSafety} still overrides it — narrowest-scope-wins). WS-C Phase 3 D6: previously
     * the pipeline hardcoded STRICT, so this build flag changed only the reports, never codegen.
     */
    @Input
    @Optional
    public abstract Property<String> getSqlSafety();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSchemaJsonFile();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void run() throws Exception {
        List<Path> sourcePaths = getSourceFiles().getFiles().stream()
                .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                .map(file -> file.toPath().toAbsolutePath())
                .sorted(Comparator.naturalOrder())
                .toList();

        Path outputRoot = getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(outputRoot);
        Path outputsManifestFile = outputRoot.resolve(".titan-transpile.outputs");
        Path artifactMetadataFile = outputRoot.resolve(TitanArtifactMetadataFile.FILE_NAME);

        if (sourcePaths.isEmpty()) {
            int removedStale = cleanupAllSqlArtifacts(outputRoot);
            Files.writeString(outputsManifestFile, "", StandardCharsets.UTF_8);
            Files.writeString(artifactMetadataFile, "", StandardCharsets.UTF_8);
            getLogger().lifecycle("Titan transpile skipped: no Java source files found ({} stale removed).", removedStale);
            return;
        }

        List<Path> classpath = getClasspathFiles().getFiles().stream()
                .map(file -> file.toPath().toAbsolutePath())
                .toList();

        List<String> targets = getTargets().getOrElse(List.of("postgresql", "mysql"));
        List<String> schemas = getSchemas().getOrElse(List.of("public"));

        // The full introspected catalog (or null when no schema.json is configured). Its view names feed
        // the schema-defined-views argument, and the whole model is threaded to the pipeline so the JDBC
        // path can resolve I-7 generated-key columns from the Catalog (§6.3).
        SchemaModel schemaModel = readSchemaModel();
        List<String> schemaDefinedViews = schemaDefinedViewNames(schemaModel);

        TranspilationPipeline pipeline = new TranspilationPipeline();
        // Audit B-6/B-7: the fullest overload is the only one that accepts strictWraparound, and it
        // still carries the dead strictMode parameter — so exposing strictWraparound (B-7) and
        // retiring strictMode (B-6) resolve together. The strictMode argument below is inert: the
        // pipeline never reads it (validation has been unconditional since Phase 0.8). A fixed value
        // is passed; removing the parameter from TranspilationPipeline.transpile is core item B-6,
        // out of scope for this plugin pass (it ripples into the transpiler module and other
        // callers). TODO(B-6): drop the inert strictMode argument once the pipeline signature loses it.
        boolean inertStrictMode = false;
        List<TranspilationPipeline.GeneratedSql> generated = pipeline.transpile(
                sourcePaths,
                classpath,
                targets,
                schemas,
                inertStrictMode,
                schemaDefinedViews,
                getObservability().getOrElse(false),
                getSensitiveColumns().getOrElse(List.of()),
                getDebugMode().getOrElse(false),
                getStrictWraparound().getOrElse(false),
                schemaModel,
                getSqlSafety().getOrElse("strict")
        );
        pipeline.warnings().forEach(warning -> getLogger().warn(warning.render()));

        if (generated.isEmpty()) {
            int removedStale = cleanupAllSqlArtifacts(outputRoot);
            Files.writeString(outputsManifestFile, "", StandardCharsets.UTF_8);
            Files.writeString(artifactMetadataFile, "", StandardCharsets.UTF_8);
            getLogger().lifecycle("Titan transpile completed: no @StoredProcedure/@StoredFunction entry points discovered ({} stale removed).", removedStale);
            return;
        }

        Map<Path, String> expectedOutputs = new HashMap<>();
        Set<Path> touchedDialectDirs = new HashSet<>();

        for (TranspilationPipeline.GeneratedSql generatedSql : generated) {
            Path dialectDir = outputRoot.resolve(generatedSql.target().toLowerCase());
            Files.createDirectories(dialectDir);
            touchedDialectDirs.add(dialectDir);

            Path file = dialectDir.resolve(artifactFileName(generatedSql));
            expectedOutputs.put(file, generatedSql.sql());
        }

        int emittedFiles = 0;
        int skippedUnchangedFiles = 0;
        for (Map.Entry<Path, String> entry : expectedOutputs.entrySet()) {
            Path file = entry.getKey();
            String sql = entry.getValue();
            if (Files.exists(file)) {
                String current = Files.readString(file, StandardCharsets.UTF_8);
                if (current.equals(sql)) {
                    skippedUnchangedFiles++;
                    continue;
                }
            }
            Files.writeString(file, sql, StandardCharsets.UTF_8);
            emittedFiles++;
        }

        int removedStaleFiles = 0;
        Set<Path> cleanupDirs = new HashSet<>(touchedDialectDirs);
        cleanupDirs.addAll(listDialectDirectories(outputRoot));
        for (Path dialectDir : cleanupDirs) {
            if (!Files.exists(dialectDir)) {
                continue;
            }
            try (var stream = Files.list(dialectDir)) {
                List<Path> staleFiles = stream
                        .filter(path -> path.getFileName().toString().endsWith(".sql"))
                        .filter(path -> !expectedOutputs.containsKey(path))
                        .toList();
                for (Path stale : staleFiles) {
                    Files.deleteIfExists(stale);
                    removedStaleFiles++;
                }
            }
        }

        List<String> manifestEntries = expectedOutputs.keySet().stream()
                .map(outputRoot::relativize)
                .map(Path::toString)
                .sorted()
                .toList();
        Files.writeString(outputsManifestFile, String.join("\n", manifestEntries), StandardCharsets.UTF_8);
        Files.writeString(
                artifactMetadataFile,
                artifactMetadata(generated, sourcePaths),
                StandardCharsets.UTF_8);

        getLogger().lifecycle(
                "Titan transpile updated {} SQL files ({} unchanged, {} stale removed) for {} entry points into {} (observability={}).",
                emittedFiles,
                skippedUnchangedFiles,
                removedStaleFiles,
                generated.stream().map(g -> g.className() + "#" + g.methodName()).distinct().count(),
                outputRoot,
                getObservability().getOrElse(false)
        );
    }

    // Plan 4.4 (audit G-10/S9): every generated artifact — entry points, helpers, enum lookups,
    // record models, views — is written with its structured object metadata (kind, typed
    // signature, dependency edges) so the packager never re-parses the emitted SQL.
    private static String artifactMetadata(
            List<TranspilationPipeline.GeneratedSql> generated,
            List<Path> sourcePaths
    ) {
        Path sourceRoot = commonParent(sourcePaths);
        return TitanArtifactMetadataFile.render(generated.stream()
                .map(item -> new TitanArtifactMetadataFile.RenderedArtifact(
                        item.target().toLowerCase(Locale.ROOT) + "/" + artifactFileName(item),
                        relativeSourcePath(item.sourceFile(), sourceRoot),
                        item))
                .toList());
    }

    /**
     * Single authority for the on-disk artifact file name (the metadata rows reference the same
     * name). Enum-lookup and record-type artifacts carry a kind token: their class and artifact
     * names are both the declaring type, so {@code enum Operation} and {@code record Operation}
     * used to write the same {@code Operation__Operation.sql} — one kind's DDL silently vanished
     * from the package (B-2 / TG-BLK-005 shape 3). The className is the declaration's
     * source-local qualified name, so same-simple-name declarations in different enclosing
     * types get distinct files too.
     */
    private static String artifactFileName(TranspilationPipeline.GeneratedSql generatedSql) {
        String kindToken = switch (generatedSql.artifactKind()) {
            case "enum-lookup" -> ".enum";
            case "record-type" -> ".record";
            default -> "";
        };
        return sanitizeClassName(generatedSql.className()) + "__" + generatedSql.artifactName() + kindToken + ".sql";
    }

    private static String relativeSourcePath(String sourceFile, Path sourceRoot) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return "unknown";
        }
        Path sourcePath = Path.of(sourceFile).toAbsolutePath().normalize();
        if (sourceRoot != null && sourcePath.startsWith(sourceRoot)) {
            return sourceRoot.relativize(sourcePath).toString().replace('\\', '/');
        }
        return sourcePath.getFileName().toString();
    }

    private static Path commonParent(List<Path> sourcePaths) {
        Path common = null;
        for (Path sourcePath : sourcePaths) {
            Path parent = sourcePath.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                continue;
            }
            if (common == null) {
                common = parent;
                continue;
            }
            while (common != null && !parent.startsWith(common)) {
                common = common.getParent();
            }
        }
        return common;
    }

    private int cleanupAllSqlArtifacts(Path outputRoot) throws Exception {
        if (!Files.exists(outputRoot)) {
            return 0;
        }
        try (var stream = Files.walk(outputRoot)) {
            List<Path> staleFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList();
            for (Path stale : staleFiles) {
                Files.deleteIfExists(stale);
            }
            return staleFiles.size();
        }
    }

    private List<Path> listDialectDirectories(Path outputRoot) throws Exception {
        if (!Files.exists(outputRoot)) {
            return List.of();
        }
        try (var stream = Files.list(outputRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        }
    }

    // GAP S9: schema.json is consumed through the shared structured reader that mirrors
    // SchemaJsonWriter, never by line- or regex-parsing the document. Returns the full introspected
    // catalog, or null when no schema.json is configured (or the file is absent).
    private SchemaModel readSchemaModel() {
        if (!getSchemaJsonFile().isPresent()) {
            return null;
        }
        Path schemaJsonPath = getSchemaJsonFile().get().getAsFile().toPath();
        if (!Files.exists(schemaJsonPath)) {
            return null;
        }
        try {
            return new SchemaJsonReader().read(schemaJsonPath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read schema model from " + schemaJsonPath, e);
        }
    }

    /** The schema-qualified, lower-cased names of the catalog's views (empty when no catalog). */
    private static List<String> schemaDefinedViewNames(SchemaModel schemaModel) {
        if (schemaModel == null) {
            return List.of();
        }
        return schemaModel.views().stream()
                .map(view -> (view.schema() + "." + view.name()).toLowerCase(Locale.ROOT))
                .toList();
    }

    private static String sanitizeClassName(String className) {
        return className.replace('.', '_');
    }
}
