package io.titan.gradle;

import io.titan.transpiler.tir.SqlObject;
import io.titan.transpiler.tir.SqlObjectRef;
import io.titan.transpiler.tir.TranspilationPipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Transport for the transpiler's structured artifact metadata between {@code titanTranspile}
 * and {@code titanPackage} (plan 4.4, audit G-10/S9).
 *
 * <p>One row per generated SQL artifact, carrying the object kind, schema-qualified name, typed
 * parameter signature and outgoing dependency edges straight from
 * {@link TranspilationPipeline.GeneratedSql} — the packager builds the manifest, inventory and
 * install plan from these rows and never regex-parses the emitted SQL.</p>
 */
final class TitanArtifactMetadataFile {

    static final String FILE_NAME = ".titan-artifacts.tsv";

    private static final char FIELD_SEPARATOR = '\t';
    private static final String LIST_SEPARATOR = "\u001f";
    private static final String OBJECT_SEPARATOR = "\u001e";
    private static final String PARAMETER_SEPARATOR = "\u001d";
    private static final String PAIR_SEPARATOR = "\u001c";

    /** One generated artifact's metadata, keyed in the file by its dialect-relative SQL path. */
    record ArtifactRow(
            String sourceInputPath,
            String className,
            String methodName,
            String artifactName,
            String annotationKind,
            List<String> parameterNames,
            List<String> parameterTypes,
            String returnType,
            String sourcePath,
            long sourceLine,
            String securityMode,
            String artifactKind,
            String schemaName,
            String sqlName,
            List<SqlObject> sqlObjects,
            List<SqlObjectRef> dependsOn
    ) {
        String entryPointId() {
            return className + "." + methodName + "(" + String.join(",", parameterTypes) + ")";
        }
    }

    private TitanArtifactMetadataFile() {
    }

    static String render(List<RenderedArtifact> artifacts) {
        List<String> lines = artifacts.stream()
                .map(TitanArtifactMetadataFile::renderRow)
                .sorted()
                .toList();
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    /** A generated artifact plus the dialect-relative path its SQL was written to. */
    record RenderedArtifact(String sourceInputPath, String sourcePath, TranspilationPipeline.GeneratedSql generated) {
    }

    private static String renderRow(RenderedArtifact artifact) {
        TranspilationPipeline.GeneratedSql generated = artifact.generated();
        return String.join(String.valueOf(FIELD_SEPARATOR),
                encode(artifact.sourceInputPath()),
                encode(generated.className()),
                encode(generated.methodName()),
                encode(generated.artifactName()),
                encode(generated.annotationKind()),
                encode(String.join(LIST_SEPARATOR, generated.parameterNames())),
                encode(String.join(LIST_SEPARATOR, generated.parameterTypes())),
                encode(generated.returnType()),
                encode(artifact.sourcePath()),
                encode(Long.toString(generated.sourceLine())),
                encode(generated.securityMode()),
                encode(generated.artifactKind()),
                encode(generated.schemaName()),
                encode(generated.sqlName()),
                encode(renderObjects(generated.sqlObjects())),
                encode(renderRefs(generated.dependsOn())));
    }

    private static String renderObjects(List<SqlObject> objects) {
        return objects.stream()
                .map(object -> String.join(LIST_SEPARATOR,
                        object.kind().label(),
                        object.schema(),
                        object.name(),
                        object.returnType(),
                        object.onTable(),
                        renderParameters(object.parameters())))
                .reduce((left, right) -> left + OBJECT_SEPARATOR + right)
                .orElse("");
    }

    private static String renderParameters(List<SqlObject.Parameter> parameters) {
        return parameters.stream()
                .map(parameter -> parameter.name() + PAIR_SEPARATOR + parameter.type())
                .reduce((left, right) -> left + PARAMETER_SEPARATOR + right)
                .orElse("");
    }

    private static String renderRefs(List<SqlObjectRef> refs) {
        return refs.stream()
                .map(ref -> String.join(LIST_SEPARATOR, ref.kind().label(), ref.schema(), ref.name()))
                .reduce((left, right) -> left + OBJECT_SEPARATOR + right)
                .orElse("");
    }

    static Map<String, ArtifactRow> read(Path metadataFile) throws IOException {
        if (!Files.exists(metadataFile)) {
            return Map.of();
        }
        Map<String, ArtifactRow> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(metadataFile, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] fields = line.split(String.valueOf(FIELD_SEPARATOR), -1);
            if (fields.length != 16) {
                throw new IllegalArgumentException(
                        "Invalid Titan artifact metadata line (" + fields.length + " fields) in " + metadataFile);
            }
            ArtifactRow row = new ArtifactRow(
                    decode(fields[0]),
                    decode(fields[1]),
                    decode(fields[2]),
                    decode(fields[3]),
                    decode(fields[4]),
                    splitList(decode(fields[5])),
                    splitList(decode(fields[6])),
                    decode(fields[7]),
                    decode(fields[8]),
                    Long.parseLong(decode(fields[9])),
                    decode(fields[10]),
                    decode(fields[11]),
                    decode(fields[12]),
                    decode(fields[13]),
                    parseObjects(decode(fields[14]), metadataFile),
                    parseRefs(decode(fields[15]), metadataFile));
            rows.put(row.sourceInputPath(), row);
        }
        return Map.copyOf(rows);
    }

    private static List<SqlObject> parseObjects(String rendered, Path metadataFile) {
        if (rendered.isEmpty()) {
            return List.of();
        }
        List<SqlObject> objects = new ArrayList<>();
        for (String objectText : rendered.split(OBJECT_SEPARATOR, -1)) {
            String[] fields = objectText.split(LIST_SEPARATOR, -1);
            if (fields.length != 6) {
                throw new IllegalArgumentException("Invalid Titan artifact object metadata in " + metadataFile);
            }
            objects.add(new SqlObject(
                    requireKind(fields[0], metadataFile),
                    fields[1],
                    fields[2],
                    parseParameters(fields[5]),
                    fields[3],
                    fields[4]));
        }
        return List.copyOf(objects);
    }

    private static List<SqlObject.Parameter> parseParameters(String rendered) {
        if (rendered.isEmpty()) {
            return List.of();
        }
        List<SqlObject.Parameter> parameters = new ArrayList<>();
        for (String pair : rendered.split(PARAMETER_SEPARATOR, -1)) {
            int separator = pair.indexOf(PAIR_SEPARATOR);
            parameters.add(new SqlObject.Parameter(pair.substring(0, separator), pair.substring(separator + 1)));
        }
        return List.copyOf(parameters);
    }

    private static List<SqlObjectRef> parseRefs(String rendered, Path metadataFile) {
        if (rendered.isEmpty()) {
            return List.of();
        }
        List<SqlObjectRef> refs = new ArrayList<>();
        for (String refText : rendered.split(OBJECT_SEPARATOR, -1)) {
            String[] fields = refText.split(LIST_SEPARATOR, -1);
            if (fields.length != 3) {
                throw new IllegalArgumentException("Invalid Titan artifact dependency metadata in " + metadataFile);
            }
            refs.add(new SqlObjectRef(requireKind(fields[0], metadataFile), fields[1], fields[2]));
        }
        return List.copyOf(refs);
    }

    private static SqlObject.Kind requireKind(String label, Path metadataFile) {
        return SqlObject.Kind.fromLabel(label.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Titan artifact object kind '" + label + "' in " + metadataFile));
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(LIST_SEPARATOR, -1));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
