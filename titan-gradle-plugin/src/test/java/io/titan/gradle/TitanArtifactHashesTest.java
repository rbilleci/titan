package io.titan.gradle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TitanArtifactHashesTest {

    @Test
    void objectSqlHashNormalizesLineEndingsAndOuterWhitespace() {
        String sql = """
                CREATE OR REPLACE FUNCTION public.demo()
                RETURNS integer
                LANGUAGE SQL
                AS $$
                  SELECT 1
                $$;
                """;
        String withCrlfAndOuterWhitespace = "\r\n  " + sql.replace("\n", "\r\n") + "\r\n";

        assertEquals(
                TitanArtifactHashes.objectSqlSha256(sql),
                TitanArtifactHashes.objectSqlSha256(withCrlfAndOuterWhitespace));
    }

    @Test
    void objectSqlHashPreservesMeaningfulInteriorSqlChanges() {
        String original = "CREATE FUNCTION demo() RETURNS integer RETURN 1";
        String changed = "CREATE FUNCTION demo() RETURNS integer RETURN 2";

        assertNotEquals(
                TitanArtifactHashes.objectSqlSha256(original),
                TitanArtifactHashes.objectSqlSha256(changed));
    }

    @Test
    void sourceInputHashUsesExactSourceBytes() {
        assertNotEquals(
                TitanArtifactHashes.sourceInputSha256("SELECT 1\n"),
                TitanArtifactHashes.sourceInputSha256(" SELECT 1\n"));
    }

    @Test
    void sourceInputsHashIsStableAcrossInputOrder() {
        TitanArtifactManifest.SourceInput postgresql = new TitanArtifactManifest.SourceInput(
                "postgresql",
                "postgresql/demo.sql",
                TitanArtifactHashes.sourceInputSha256("SELECT 1\n"));
        TitanArtifactManifest.SourceInput mysql = new TitanArtifactManifest.SourceInput(
                "mysql",
                "mysql/demo.sql",
                TitanArtifactHashes.sourceInputSha256("SELECT 1\n"));

        assertEquals(
                TitanArtifactHashes.sourceInputsSha256(List.of(postgresql, mysql)),
                TitanArtifactHashes.sourceInputsSha256(List.of(mysql, postgresql)));
    }
}
