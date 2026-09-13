package io.titan.gradle;

import io.titan.transpiler.tir.DialectId;
import io.titan.transpiler.tir.DialectProviders;
import io.titan.transpiler.tir.SqlObject;
import io.titan.transpiler.tir.SqlObjectRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the artifact manifest, object inventory and install plan from the transpiler's
 * structured metadata (plan 4.4, audit G-10/S9).
 *
 * <p>Object names, kinds, typed signatures and dependency edges come from
 * {@code .titan-artifacts.tsv} (written by {@code titanTranspile} straight from the pipeline)
 * and from {@code RuntimeStrategy.runtimeObjects()} for the runtime bundle. The emitted SQL is
 * hashed, never parsed: the CREATE-regex object discovery and the name-substring dependency
 * inference are gone.</p>
 *
 * <p>SQL files without a metadata row (hand-authored SQL dropped into the input directory) are
 * inventoried as one opaque {@code script} object — they are not Titan-generated, so Titan does
 * not guess their contents.</p>
 */
final class TitanPackagedArtifacts {

    static final String DEFAULT_SCHEMA = "public";

    /** One dialect's SQL input files (the transpile output directory layout). */
    record DialectInput(String dialect, List<Path> sqlFiles) {
    }

    /** Discovers the dialect directories and their SQL files under the transpile output root. */
    static List<DialectInput> collectDialectInputs(Path inputRoot) throws IOException {
        List<Path> dialectDirs;
        try (java.util.stream.Stream<Path> stream = Files.list(inputRoot)) {
            dialectDirs = stream
                    .filter(Files::isDirectory)
                    .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        List<DialectInput> dialectInputs = new ArrayList<>();
        for (Path dialectDir : dialectDirs) {
            String dialect = dialectDir.getFileName().toString().toLowerCase(Locale.ROOT);
            List<Path> sqlFiles;
            try (java.util.stream.Stream<Path> files = Files.list(dialectDir)) {
                sqlFiles = files
                        .filter(path -> path.getFileName().toString().endsWith(".sql"))
                        .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            if (sqlFiles.isEmpty()) {
                continue;
            }
            dialectInputs.add(new DialectInput(dialect, sqlFiles));
        }
        return List.copyOf(dialectInputs);
    }

    record Result(
            TitanArtifactManifest manifest,
            TitanObjectInventory inventory,
            TitanInstallPlan installPlan,
            Map<String, SqlObject> sqlObjectsByInventoryId,
            List<TitanRollbackScript.RenderedRollbackScript> rollbackScripts
    ) {
    }

    private TitanPackagedArtifacts() {
    }

    static String runtimeMigrationSqlForDialect(String dialect) {
        // Plan 3.4: target aliases resolve through the DialectId registry, so a dialect added
        // to the transpiler is picked up here without touching this class.
        return DialectId.parse(dialect)
                .map(dialectId -> new DialectProviders().require(dialectId).runtimeStrategy().runtimeMigrationSql())
                .orElse("");
    }

    static List<SqlObject> runtimeObjectsForDialect(String dialect) {
        return DialectId.parse(dialect)
                .map(dialectId -> new DialectProviders().require(dialectId).runtimeStrategy().runtimeObjects())
                .orElse(List.of());
    }

    static String runtimeNamespaceForDialect(String dialect) {
        return DialectId.parse(dialect)
                .map(dialectId -> new DialectProviders().require(dialectId).runtimeStrategy().runtimeNamespace())
                .orElse("");
    }

    static Result build(
            List<DialectInput> dialectInputs,
            Path inputRoot,
            String mode,
            String titanVersion,
            Map<String, TitanArtifactMetadataFile.ArtifactRow> metadataRows
    ) throws IOException {
        List<TitanArtifactManifest.SourceInput> sourceInputs = new ArrayList<>();
        List<PendingObject> pendingObjects = new ArrayList<>();
        Map<String, EntryPointBuilder> entryPointBuilders = new LinkedHashMap<>();
        Map<String, SqlObject> sqlObjectsById = new LinkedHashMap<>();
        List<String> dialects = new ArrayList<>();
        int createOrder = 0;

        for (DialectInput input : dialectInputs) {
            String dialect = input.dialect();
            dialects.add(dialect);

            if (mode.equals("migration")) {
                String runtimeSql = runtimeMigrationSqlForDialect(dialect);
                if (!runtimeSql.isBlank()) {
                    String runtimeHash = TitanArtifactHashes.objectSqlSha256(runtimeSql);
                    String runtimePath = dialect + "/" + TitanPackageTask.RUNTIME_MIGRATION_FILE_NAME;
                    List<SqlObject> runtimeObjects = runtimeObjectsForDialect(dialect);
                    for (int index = 0; index < runtimeObjects.size(); index++) {
                        pendingObjects.add(PendingObject.forSqlObject(
                                dialect,
                                runtimeObjects.get(index),
                                runtimePath,
                                "titan_runtime",
                                "invoker",
                                createOrder + index,
                                List.of(),
                                null,
                                runtimeHash));
                    }
                    createOrder += 100;
                }
            }

            for (Path sqlFile : input.sqlFiles()) {
                String relativePath = inputRoot.relativize(sqlFile).toString().replace('\\', '/');
                String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);
                sourceInputs.add(new TitanArtifactManifest.SourceInput(
                        dialect, relativePath, TitanArtifactHashes.sourceInputSha256(sql)));

                String sqlHash = TitanArtifactHashes.objectSqlSha256(sql);
                String baseName = sqlFile.getFileName().toString().replaceFirst("\\.sql$", "");
                TitanArtifactMetadataFile.ArtifactRow row = metadataRows.get(relativePath);
                if (row == null || row.sqlObjects().isEmpty()) {
                    pendingObjects.add(PendingObject.forScript(
                            dialect, baseName, relativePath, createOrder, sqlHash));
                    createOrder += 100;
                    continue;
                }

                String sourceEntryPoint = sourceEntryPointFor(row, baseName);
                String securityMode = row.securityMode().isBlank() ? "invoker" : row.securityMode();
                String primaryId = null;
                for (int index = 0; index < row.sqlObjects().size(); index++) {
                    SqlObject object = row.sqlObjects().get(index);
                    PendingObject pending = PendingObject.forSqlObject(
                            dialect,
                            object,
                            relativePath,
                            sourceEntryPoint,
                            securityMode,
                            createOrder + index,
                            row.dependsOn(),
                            primaryId,
                            sqlHash);
                    pendingObjects.add(pending);
                    if (index == 0) {
                        primaryId = pending.id();
                    }
                }
                createOrder += 100;

                if (!row.annotationKind().isBlank()) {
                    SqlObject primary = row.sqlObjects().get(0);
                    EntryPointBuilder builder = entryPointBuilders.computeIfAbsent(
                            row.entryPointId(),
                            ignored -> new EntryPointBuilder(row));
                    builder.addSql(new TitanArtifactManifest.SqlEntryPoint(
                            dialect,
                            objectId(dialect, primary),
                            primary.name(),
                            primary.kind().label(),
                            manifestParameters(primary),
                            manifestReturnType(primary),
                            relativePath));
                }
            }
        }

        List<TitanObjectInventory.GeneratedObject> inventoryObjects =
                resolveDependencies(pendingObjects, sqlObjectsById);

        String sourceInputsHash = TitanArtifactHashes.sourceInputsSha256(sourceInputs);
        String identityHash = TitanArtifactManifest.sha256Hex((mode + "\n"
                + titanVersion + "\n"
                + sourceInputsHash).getBytes(StandardCharsets.UTF_8));
        String artifactId = "titan.generated-sql." + identityHash.substring(0, 16);
        TitanObjectInventory inventory = new TitanObjectInventory(
                TitanObjectInventory.CURRENT_SCHEMA_VERSION,
                artifactId,
                inventoryObjects,
                new TitanObjectInventory.InventoryHashes(
                        TitanObjectInventory.INVENTORY_CONTENT_HASH_PLACEHOLDER));
        inventory = inventory.withInventoryContentSha256(inventory.canonicalContentHash());

        List<TitanArtifactManifest.GeneratedObjectReference> generatedObjects = inventory.objects().stream()
                .map(object -> new TitanArtifactManifest.GeneratedObjectReference(object.id(), "inventoried"))
                .toList();

        // B-5 (TG-BLK-008): render the executable rollback scripts here, before the manifest,
        // so the manifest can integrity-link each one by path + statementCount + sha256. The
        // TitanPackageTask writes these exact bytes to disk, so the manifest's sha256 matches the
        // file the consumer reads.
        List<TitanRollbackScript.RenderedRollbackScript> rollbackScripts = TitanRollbackScript.renderAll(
                dialects, artifactId, inventory.objects(), sqlObjectsById, mode);

        TitanArtifactManifest manifest = new TitanArtifactManifest(
                TitanArtifactManifest.CURRENT_SCHEMA_VERSION,
                artifactId,
                titanVersion,
                mode,
                dialects,
                sourceInputs,
                entryPointBuilders.values().stream()
                        .map(EntryPointBuilder::toEntryPoint)
                        .toList(),
                generatedObjects,
                rollbackScripts.stream()
                        .map(TitanRollbackScript.RenderedRollbackScript::toManifestEntry)
                        .toList(),
                new TitanArtifactManifest.ManifestHashes(
                        sourceInputsHash,
                        TitanArtifactManifest.MANIFEST_CONTENT_HASH_PLACEHOLDER),
                new TitanArtifactManifest.Validation("generated", List.of(
                        "install verification pending: run titanVerifyInstall")));
        manifest = manifest.withManifestContentSha256(manifest.canonicalContentHash());
        TitanInstallPlan installPlan = TitanInstallPlan.from(manifest, inventory);
        return new Result(manifest, inventory, installPlan, Map.copyOf(sqlObjectsById), rollbackScripts);
    }

    /**
     * Dependency edges are resolved object-id to object-id after every object of the package is
     * known: artifact-level edges (call graph, record/enum/view usage) attach to each object of
     * the artifact, and every non-primary object depends on its artifact's primary object (the
     * file creates them in that order). A dangling edge is a packaging bug and fails loudly.
     */
    private static List<TitanObjectInventory.GeneratedObject> resolveDependencies(
            List<PendingObject> pendingObjects,
            Map<String, SqlObject> sqlObjectsById
    ) {
        Map<String, String> idByRefKey = new LinkedHashMap<>();
        for (PendingObject pending : pendingObjects) {
            idByRefKey.put(refKey(pending.dialect(), pending.schema(), pending.name(), pending.kind()), pending.id());
        }

        List<TitanObjectInventory.GeneratedObject> objects = new ArrayList<>(pendingObjects.size());
        for (PendingObject pending : pendingObjects) {
            List<String> dependsOn = new ArrayList<>();
            for (SqlObjectRef ref : pending.refs()) {
                String refId = idByRefKey.get(refKey(
                        pending.dialect(),
                        schemaOrDefault(ref.schema()),
                        ref.name(),
                        ref.kind().label()));
                if (refId == null) {
                    throw new IllegalStateException("Titan package dependency edge points to an object "
                            + "that is not part of this package: " + pending.id() + " -> "
                            + ref.kind().label() + " " + ref.schema() + "." + ref.name());
                }
                if (!refId.equals(pending.id())) {
                    dependsOn.add(refId);
                }
            }
            if (pending.primaryId() != null) {
                dependsOn.add(pending.primaryId());
            }
            objects.add(new TitanObjectInventory.GeneratedObject(
                    pending.id(),
                    pending.dialect(),
                    pending.kind(),
                    pending.schema(),
                    pending.name(),
                    pending.signature(),
                    pending.sourceInputPath(),
                    pending.sourceEntryPoint(),
                    pending.securityMode(),
                    pending.createOrder(),
                    dependsOn,
                    pending.sqlHash()));
            if (pending.sqlObject() != null) {
                sqlObjectsById.put(pending.id(), pending.sqlObject());
            }
        }
        return List.copyOf(objects);
    }

    private static String refKey(String dialect, String schema, String name, String kind) {
        return dialect + " " + schema + " " + name + " " + kind;
    }

    static String schemaOrDefault(String schema) {
        return (schema == null || schema.isBlank()) ? DEFAULT_SCHEMA : schema;
    }

    static String objectId(String dialect, SqlObject object) {
        return dialect + "." + schemaOrDefault(object.schema()) + "." + object.name() + "." + object.kind().label();
    }

    private static List<TitanArtifactManifest.SqlParameter> manifestParameters(SqlObject object) {
        List<TitanArtifactManifest.SqlParameter> parameters = new ArrayList<>();
        for (int ordinal = 0; ordinal < object.parameters().size(); ordinal++) {
            SqlObject.Parameter parameter = object.parameters().get(ordinal);
            parameters.add(new TitanArtifactManifest.SqlParameter(ordinal, parameter.name(), parameter.type()));
        }
        return List.copyOf(parameters);
    }

    private static String manifestReturnType(SqlObject object) {
        if (!object.returnType().isBlank()) {
            return object.returnType();
        }
        return switch (object.kind()) {
            case PROCEDURE, EVENT -> "void";
            case TRIGGER -> "trigger";
            default -> "unknown";
        };
    }

    private static String sourceEntryPointFor(TitanArtifactMetadataFile.ArtifactRow row, String baseName) {
        // Routine-shaped artifacts (entry points and helpers) name their Java origin; data
        // artifacts (enum lookups, record models) keep the file base name — both match the
        // strings the previous packager derived from the file name.
        if (row.className().equals(row.artifactName())) {
            return baseName;
        }
        return row.className() + "." + row.artifactName() + "()";
    }

    private record PendingObject(
            String id,
            String dialect,
            String kind,
            String schema,
            String name,
            String signature,
            String sourceInputPath,
            String sourceEntryPoint,
            String securityMode,
            int createOrder,
            List<SqlObjectRef> refs,
            String primaryId,
            String sqlHash,
            SqlObject sqlObject
    ) {
        static PendingObject forSqlObject(
                String dialect,
                SqlObject object,
                String sourceInputPath,
                String sourceEntryPoint,
                String securityMode,
                int createOrder,
                List<SqlObjectRef> refs,
                String primaryId,
                String sqlHash
        ) {
            String schema = schemaOrDefault(object.schema());
            return new PendingObject(
                    objectId(dialect, object),
                    dialect,
                    object.kind().label(),
                    schema,
                    object.name(),
                    object.signature(),
                    sourceInputPath,
                    sourceEntryPoint,
                    securityMode,
                    createOrder,
                    refs,
                    primaryId,
                    sqlHash,
                    object);
        }

        static PendingObject forScript(
                String dialect,
                String baseName,
                String sourceInputPath,
                int createOrder,
                String sqlHash
        ) {
            return new PendingObject(
                    dialect + "." + DEFAULT_SCHEMA + "." + baseName + ".script",
                    dialect,
                    "script",
                    DEFAULT_SCHEMA,
                    baseName,
                    "()",
                    sourceInputPath,
                    baseName,
                    "invoker",
                    createOrder,
                    List.of(),
                    null,
                    sqlHash,
                    null);
        }
    }

    private static final class EntryPointBuilder {
        private final TitanArtifactMetadataFile.ArtifactRow row;
        private final List<TitanArtifactManifest.SqlEntryPoint> sqlEntries = new ArrayList<>();

        private EntryPointBuilder(TitanArtifactMetadataFile.ArtifactRow row) {
            this.row = row;
        }

        private void addSql(TitanArtifactManifest.SqlEntryPoint sqlEntry) {
            sqlEntries.add(sqlEntry);
        }

        private TitanArtifactManifest.EntryPoint toEntryPoint() {
            return new TitanArtifactManifest.EntryPoint(
                    row.entryPointId(),
                    new TitanArtifactManifest.JavaEntryPoint(
                            row.className(),
                            row.methodName(),
                            row.parameterTypes(),
                            row.annotationKind(),
                            new TitanArtifactManifest.SourceLocation(
                                    row.sourcePath().isBlank() ? "unknown" : row.sourcePath(),
                                    row.sourceLine())),
                    sqlEntries,
                    row.securityMode().isBlank() ? "invoker" : row.securityMode().toLowerCase(Locale.ROOT));
        }
    }
}
