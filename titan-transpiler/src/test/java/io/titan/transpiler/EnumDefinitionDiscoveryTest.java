package io.titan.transpiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnumDefinitionDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversEnumConstantsAndInstanceFields() throws Exception {
        Path source = tempDir.resolve("EnumHolder.java");
        Files.writeString(
                source,
                """
                package sample;

                public final class EnumHolder {
                    enum UserStatus {
                        ACTIVE(1, \"active\"),
                        SUSPENDED(2, \"suspended\");

                        private final int code;
                        private final String label;

                        UserStatus(int code, String label) {
                            this.code = code;
                            this.label = label;
                        }
                    }
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(source), List.of(), "21", false);

        EnumDefinitionDiscovery discovery = new EnumDefinitionDiscovery();
        List<DiscoveredEnumDefinition> enums = discovery.discover(parsed);

        assertEquals(1, enums.size());
        DiscoveredEnumDefinition enumDef = enums.getFirst();
        assertEquals("UserStatus", enumDef.enumName());
        assertEquals(List.of("code", "label"), enumDef.fields().stream().map(DiscoveredEnumDefinition.EnumField::name).toList());
        assertEquals(List.of("int", "String"), enumDef.fields().stream().map(DiscoveredEnumDefinition.EnumField::typeName).toList());
        assertEquals(List.of("ACTIVE", "SUSPENDED"), enumDef.constants().stream().map(DiscoveredEnumDefinition.EnumConstant::name).toList());
        assertEquals(List.of("1", "active"), enumDef.constants().getFirst().constructorArguments());
    }
}
