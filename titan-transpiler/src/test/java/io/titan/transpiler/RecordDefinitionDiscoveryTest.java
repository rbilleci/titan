package io.titan.transpiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordDefinitionDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversRecordComponents() throws Exception {
        Path source = tempDir.resolve("RecordHolder.java");
        Files.writeString(
                source,
                """
                package sample;

                public final class RecordHolder {
                    record CustomerSnapshot(long id, String email, int score) {}
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(source), List.of(), "21", false);

        RecordDefinitionDiscovery discovery = new RecordDefinitionDiscovery();
        List<DiscoveredRecordDefinition> records = discovery.discover(parsed);

        assertEquals(1, records.size());
        DiscoveredRecordDefinition recordDef = records.getFirst();
        assertEquals("CustomerSnapshot", recordDef.recordName());
        assertEquals(List.of("id", "email", "score"),
                recordDef.components().stream().map(DiscoveredRecordDefinition.RecordComponentDef::name).toList());
        assertEquals(List.of("long", "String", "int"),
                recordDef.components().stream().map(DiscoveredRecordDefinition.RecordComponentDef::typeName).toList());
    }
}
