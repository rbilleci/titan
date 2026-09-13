package io.titan.gradle;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

final class TitanArtifactHashes {
    private TitanArtifactHashes() {
    }

    static String sourceInputSha256(String source) {
        return TitanArtifactManifest.sha256Hex(source.getBytes(StandardCharsets.UTF_8));
    }

    static String sourceInputsSha256(List<TitanArtifactManifest.SourceInput> sourceInputs) {
        StringBuilder material = new StringBuilder();
        sourceInputs.stream()
                .sorted(Comparator.comparing(TitanArtifactManifest.SourceInput::dialect)
                        .thenComparing(TitanArtifactManifest.SourceInput::path))
                .forEach(input -> material.append(input.dialect())
                        .append('\0')
                        .append(input.path())
                        .append('\0')
                        .append(input.sha256())
                        .append('\n'));
        return TitanArtifactManifest.sha256Hex(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String objectSqlSha256(String objectSql) {
        return TitanArtifactManifest.sha256Hex(normalizeSql(objectSql).getBytes(StandardCharsets.UTF_8));
    }

    static String normalizeSql(String sql) {
        return sql.strip().replace("\r\n", "\n").replace('\r', '\n') + "\n";
    }
}
