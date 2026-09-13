package io.titan.gradle;

import io.titan.transpiler.tir.DialectCapabilities;
import io.titan.transpiler.tir.DialectId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

public record TitanInstallPlan(
        String schemaVersion,
        String artifactId,
        String manifestContentSha256,
        String inventoryContentSha256,
        List<DialectPlan> dialectPlans,
        PlanHashes hashes
) {
    public static final String CURRENT_SCHEMA_VERSION = "titan.install-plan.v1";
    public static final String PLAN_CONTENT_HASH_PLACEHOLDER =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final Comparator<TitanObjectInventory.GeneratedObject> OBJECT_ORDER =
            Comparator.comparingInt(TitanObjectInventory.GeneratedObject::createOrder)
                    .thenComparing(TitanObjectInventory.GeneratedObject::id);

    public TitanInstallPlan {
        requireText(schemaVersion, "schema version");
        requireText(artifactId, "artifact id");
        requireHexSha256(manifestContentSha256, "manifest content sha256");
        requireHexSha256(inventoryContentSha256, "inventory content sha256");
        dialectPlans = sortedCopy(dialectPlans, Comparator.comparing(DialectPlan::dialect));
        if (dialectPlans.isEmpty()) {
            throw new IllegalArgumentException("install plan requires at least one dialect plan");
        }
        requireUniqueDialectPlans(dialectPlans);
        Objects.requireNonNull(hashes, "hashes");
    }

    public static TitanInstallPlan from(
            TitanArtifactManifest manifest,
            TitanObjectInventory inventory
    ) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(inventory, "inventory");
        List<DialectPlan> dialectPlans = new ArrayList<>();
        for (String dialect : manifest.dialects()) {
            dialectPlans.add(dialectPlanFor(dialect, inventory.objects()));
        }
        TitanInstallPlan plan = new TitanInstallPlan(
                CURRENT_SCHEMA_VERSION,
                manifest.artifactId(),
                manifest.hashes().manifestContentSha256(),
                inventory.hashes().inventoryContentSha256(),
                dialectPlans,
                new PlanHashes(PLAN_CONTENT_HASH_PLACEHOLDER));
        return plan.withPlanContentSha256(plan.canonicalContentHash());
    }

    public String toJson() {
        return toJson(hashes.planContentSha256());
    }

    public byte[] toJsonBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public String canonicalContentHash() {
        return TitanArtifactManifest.sha256Hex(
                toJson(PLAN_CONTENT_HASH_PLACEHOLDER).getBytes(StandardCharsets.UTF_8));
    }

    public TitanInstallPlan withPlanContentSha256(String planContentSha256) {
        return new TitanInstallPlan(
                schemaVersion,
                artifactId,
                manifestContentSha256,
                inventoryContentSha256,
                dialectPlans,
                new PlanHashes(planContentSha256));
    }

    private static DialectPlan dialectPlanFor(
            String dialect,
            List<TitanObjectInventory.GeneratedObject> objects
    ) {
        List<TitanObjectInventory.GeneratedObject> dialectObjects = objects.stream()
                .filter(object -> object.dialect().equals(dialect))
                .sorted(OBJECT_ORDER)
                .toList();
        requireNoGeneratedNameCollisions(dialect, dialectObjects);
        requireIdentifiersWithinDialectLimits(dialect, dialectObjects);
        dialectObjects = dependencyOrderedObjects(dialect, dialectObjects);

        List<InstallStep> steps = new ArrayList<>();
        List<String> schemas = dialectObjects.stream()
                .map(TitanObjectInventory.GeneratedObject::schema)
                .distinct()
                .sorted()
                .toList();
        for (String schema : schemas) {
            steps.add(new InstallStep(
                    "preflight.schema." + dialect + "." + schema,
                    "preflight",
                    "schemaExists",
                    schema,
                    "",
                    "fail",
                    "Ensure target schema exists or can be created before installing generated objects."));
        }
        for (TitanObjectInventory.GeneratedObject object : dialectObjects) {
            steps.add(new InstallStep(
                    "preflight.name-collision." + object.id(),
                    "preflight",
                    "generatedNameCollision",
                    object.schema(),
                    object.id(),
                    "fail",
                    "Fail if an unmanaged object already owns this generated object name."));
        }
        for (TitanObjectInventory.GeneratedObject object : dialectObjects) {
            steps.add(new InstallStep(
                    "create." + object.id(),
                    createStepKind(object),
                    "",
                    object.schema(),
                    object.id(),
                    "fail",
                    rollbackHintFor(object)));
        }
        for (TitanObjectInventory.GeneratedObject object : dialectObjects) {
            if (object.kind().equals("function") || object.kind().equals("procedure")) {
                steps.add(new InstallStep(
                        "verify.exists." + object.id(),
                        "verificationProbe",
                        "objectExists",
                        object.schema(),
                        object.id(),
                        "fail",
                        "Verify installed object identity and signature in GAP005-M5.1."));
            }
        }

        return new DialectPlan(
                dialect,
                transactionModeFor(dialect),
                transactionNoteFor(dialect),
                "SQL text remains in the package dialect directory; this plan references generated object ids.",
                steps);
    }

    private static String createStepKind(TitanObjectInventory.GeneratedObject object) {
        if (object.sourceInputPath().contains(TitanPackageTask.RUNTIME_MIGRATION_FILE_NAME)) {
            return "runtimeBootstrap";
        }
        return "createOrReplace";
    }

    private static String rollbackHintFor(TitanObjectInventory.GeneratedObject object) {
        if (object.kind().equals("script")) {
            return "Review script SQL manually before cleanup; generated script rollback is not inferred.";
        }
        return "Cleanup can drop generated object id " + object.id() + " when no unmanaged dependency exists.";
    }

    private static String transactionModeFor(String dialect) {
        return switch (dialect) {
            case "postgresql", "postgres", "pg" -> "singleTransaction";
            case "mysql" -> "statementBoundaryDdl";
            default -> "dialectDefault";
        };
    }

    private static String transactionNoteFor(String dialect) {
        return switch (dialect) {
            case "postgresql", "postgres", "pg" ->
                    "PostgreSQL install steps may be reviewed as a single transaction when the caller executes supported DDL that way.";
            case "mysql" ->
                    "MySQL routine DDL observes statement boundaries; callers must not assume PostgreSQL-style all-or-nothing routine installation.";
            default ->
                    "Transaction behavior is dialect-defined; callers must review generated SQL and target database DDL semantics.";
        };
    }

    private String toJson(String planContentSha256) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        field(builder, 1, "schemaVersion", schemaVersion, true);
        field(builder, 1, "artifactId", artifactId, true);
        field(builder, 1, "manifestContentSha256", manifestContentSha256, true);
        field(builder, 1, "inventoryContentSha256", inventoryContentSha256, true);
        dialectPlansField(builder, 1, true);
        indent(builder, 1).append("\"hashes\": {\n");
        field(builder, 2, "planContentSha256", planContentSha256, false);
        indent(builder, 1).append("}\n");
        builder.append("}\n");
        return builder.toString();
    }

    public record DialectPlan(
            String dialect,
            String transactionMode,
            String transactionNote,
            String sqlReviewNote,
            List<InstallStep> steps
    ) {
        public DialectPlan {
            requireText(dialect, "dialect");
            requireText(transactionMode, "transaction mode");
            requireText(transactionNote, "transaction note");
            requireText(sqlReviewNote, "SQL review note");
            Objects.requireNonNull(steps, "steps");
            steps = List.copyOf(steps);
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("install plan requires at least one step");
            }
            requireUniqueStepIds(steps);
        }
    }

    public record InstallStep(
            String id,
            String kind,
            String check,
            String schema,
            String objectId,
            String onFailure,
            String note
    ) {
        public InstallStep {
            requireText(id, "step id");
            requireText(kind, "step kind");
            check = check == null ? "" : check;
            requireText(schema, "step schema");
            objectId = objectId == null ? "" : objectId;
            requireText(onFailure, "step onFailure");
            requireText(note, "step note");
        }
    }

    public record PlanHashes(String planContentSha256) {
        public PlanHashes {
            requireHexSha256(planContentSha256, "plan content sha256");
        }
    }

    private void dialectPlansField(StringBuilder builder, int depth, boolean comma) {
        indent(builder, depth).append("\"dialectPlans\": [\n");
        for (int index = 0; index < dialectPlans.size(); index++) {
            DialectPlan plan = dialectPlans.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "dialect", plan.dialect(), true);
            field(builder, depth + 2, "transactionMode", plan.transactionMode(), true);
            field(builder, depth + 2, "transactionNote", plan.transactionNote(), true);
            field(builder, depth + 2, "sqlReviewNote", plan.sqlReviewNote(), true);
            stepsField(builder, depth + 2, plan.steps(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < dialectPlans.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private static void stepsField(
            StringBuilder builder,
            int depth,
            List<InstallStep> steps,
            boolean comma
    ) {
        indent(builder, depth).append("\"steps\": [\n");
        for (int index = 0; index < steps.size(); index++) {
            InstallStep step = steps.get(index);
            indent(builder, depth + 1).append("{\n");
            field(builder, depth + 2, "id", step.id(), true);
            field(builder, depth + 2, "kind", step.kind(), true);
            field(builder, depth + 2, "check", step.check(), true);
            field(builder, depth + 2, "schema", step.schema(), true);
            field(builder, depth + 2, "objectId", step.objectId(), true);
            field(builder, depth + 2, "onFailure", step.onFailure(), true);
            field(builder, depth + 2, "note", step.note(), false);
            indent(builder, depth + 1).append("}");
            commaAndNewline(builder, index < steps.size() - 1);
        }
        indent(builder, depth).append("]");
        commaAndNewline(builder, comma);
    }

    private static void requireNoGeneratedNameCollisions(
            String dialect,
            List<TitanObjectInventory.GeneratedObject> objects
    ) {
        Set<String> generatedNames = new HashSet<>();
        for (TitanObjectInventory.GeneratedObject object : objects) {
            String identity = object.dialect() + "." + object.schema() + "." + object.name();
            if (!generatedNames.add(identity)) {
                throw new IllegalArgumentException(
                        "install plan detected generated object name collision for " + dialect + ": " + identity);
            }
        }
    }

    /**
     * B-11 (TG-BLK-011 hardening): every generated object name must fit the dialect's identifier
     * ceiling ({@code NamingRules.identifierMaxLength} — PostgreSQL 63, MySQL 64), read from the
     * capabilities registry, never hardcoded. With the TG-BLK-011 fix in {@code SqlNames} this is
     * a never-firing invariant: the cheap, Docker-free, package-time guard that catches any
     * future name family that bypasses {@code SqlNames} before a green package deploys broken
     * (an over-limit identifier is silently truncated by the server, so the object the routine
     * body calls never exists). Dialects without a capability descriptor (alias-only strings the
     * registry does not resolve) are skipped — the descriptor is the only authority on the limit.
     */
    private static void requireIdentifiersWithinDialectLimits(
            String dialect,
            List<TitanObjectInventory.GeneratedObject> objects
    ) {
        DialectId dialectId = DialectId.parse(dialect).orElse(null);
        if (dialectId == null) {
            return;
        }
        int identifierMaxLength = DialectCapabilities.forDialect(dialectId).namingRules().identifierMaxLength();
        for (TitanObjectInventory.GeneratedObject object : objects) {
            if (object.name().length() > identifierMaxLength) {
                throw new IllegalArgumentException(
                        "install plan detected generated identifier exceeding the " + dialect
                                + " identifier limit of " + identifierMaxLength + " characters: object "
                                + object.id() + " name \"" + object.name() + "\" is "
                                + object.name().length() + " characters. The server would silently truncate it, "
                                + "so the generated object would not match the name its callers use.");
            }
        }
    }

    /**
     * Install order for one dialect's objects: dependency edges first, stable
     * {@code createOrder} tie-break. The rollback script reverses this order (plan 4.4).
     */
    static List<TitanObjectInventory.GeneratedObject> installOrder(
            String dialect,
            List<TitanObjectInventory.GeneratedObject> objects
    ) {
        return dependencyOrderedObjects(dialect, objects);
    }

    private static List<TitanObjectInventory.GeneratedObject> dependencyOrderedObjects(
            String dialect,
            List<TitanObjectInventory.GeneratedObject> objects
    ) {
        Map<String, TitanObjectInventory.GeneratedObject> objectsById = new HashMap<>();
        Map<String, List<TitanObjectInventory.GeneratedObject>> dependentsByDependency = new HashMap<>();
        Map<String, Integer> remainingDependencies = new HashMap<>();
        for (TitanObjectInventory.GeneratedObject object : objects) {
            if (objectsById.put(object.id(), object) != null) {
                throw new IllegalArgumentException(
                        "install plan requires unique generated object ids for " + dialect + ": " + object.id());
            }
            remainingDependencies.put(object.id(), 0);
        }
        for (TitanObjectInventory.GeneratedObject object : objects) {
            for (String dependencyId : object.dependsOn()) {
                TitanObjectInventory.GeneratedObject dependency = objectsById.get(dependencyId);
                if (dependency == null) {
                    throw new IllegalArgumentException(
                            "install plan dependency not found for " + dialect + ": "
                                    + object.id() + " depends on " + dependencyId);
                }
                dependentsByDependency.computeIfAbsent(dependency.id(), ignored -> new ArrayList<>()).add(object);
                remainingDependencies.merge(object.id(), 1, Integer::sum);
            }
        }

        PriorityQueue<TitanObjectInventory.GeneratedObject> ready = new PriorityQueue<>(OBJECT_ORDER);
        for (TitanObjectInventory.GeneratedObject object : objects) {
            if (remainingDependencies.get(object.id()) == 0) {
                ready.add(object);
            }
        }

        List<TitanObjectInventory.GeneratedObject> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            TitanObjectInventory.GeneratedObject object = ready.remove();
            ordered.add(object);
            for (TitanObjectInventory.GeneratedObject dependent
                    : dependentsByDependency.getOrDefault(object.id(), List.of())) {
                int remaining = remainingDependencies.merge(dependent.id(), -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (ordered.size() != objects.size()) {
            throw new IllegalArgumentException("install plan dependency cycle detected for " + dialect);
        }
        return List.copyOf(ordered);
    }

    private static void requireUniqueDialectPlans(List<DialectPlan> plans) {
        Set<String> dialects = new HashSet<>();
        for (DialectPlan plan : plans) {
            if (!dialects.add(plan.dialect())) {
                throw new IllegalArgumentException("install plan requires unique dialect plans: " + plan.dialect());
            }
        }
    }

    private static void requireUniqueStepIds(List<InstallStep> steps) {
        Set<String> stepIds = new HashSet<>();
        for (InstallStep step : steps) {
            if (!stepIds.add(step.id())) {
                throw new IllegalArgumentException("install plan requires unique step ids: " + step.id());
            }
        }
    }

    private static void field(StringBuilder builder, int depth, String name, String value, boolean comma) {
        indent(builder, depth)
                .append("\"")
                .append(escape(name))
                .append("\": \"")
                .append(escape(value))
                .append("\"");
        commaAndNewline(builder, comma);
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static StringBuilder indent(StringBuilder builder, int depth) {
        return builder.append("  ".repeat(depth));
    }

    private static void commaAndNewline(StringBuilder builder, boolean comma) {
        if (comma) {
            builder.append(",");
        }
        builder.append("\n");
    }

    private static <T> List<T> sortedCopy(List<T> values, Comparator<T> comparator) {
        Objects.requireNonNull(values, "values");
        return values.stream().sorted(comparator).toList();
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("install plan requires " + fieldName);
        }
    }

    private static void requireHexSha256(String value, String fieldName) {
        requireText(value, fieldName);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("install plan requires 64-character lowercase hex " + fieldName);
        }
    }
}
