package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePathScanner;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaTypeToTirMapperTest {

    @TempDir
    Path tempDir;

    private ExecutableElement probeMethod() throws Exception {
        Path sourceFile = tempDir.resolve("MapperFixture.java");
        Files.writeString(sourceFile, """
                import java.math.BigDecimal;
                import java.math.BigInteger;
                import java.time.Duration;
                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.LocalDateTime;
                import java.time.LocalTime;
                import java.time.OffsetDateTime;
                import java.time.Period;
                import java.time.ZonedDateTime;
                import java.util.List;
                import java.util.Map;
                import java.util.UUID;

                record MapperFixtureRow(int id, String label) {
                }

                class MapperFixture {
                    enum Tier { BASIC, PRO }

                    static void probe(
                            boolean primitiveBoolean, byte primitiveByte, short primitiveShort, int primitiveInt,
                            long primitiveLong, char primitiveChar, float primitiveFloat, double primitiveDouble,
                            Boolean boxedBoolean, Byte boxedByte, Short boxedShort, Integer boxedInteger,
                            Long boxedLong, Character boxedCharacter, Float boxedFloat, Double boxedDouble,
                            BigDecimal bigDecimal, BigInteger bigInteger, String text,
                            LocalDate localDate, LocalTime localTime, LocalDateTime localDateTime,
                            Instant instant, ZonedDateTime zonedDateTime, OffsetDateTime offsetDateTime,
                            Duration duration, Period period, UUID uuid, List<UUID> uuidList,
                            byte[] bytePayload,
                            int[] intArray, List<String> textList, MapperFixtureRow row, Tier tier,
                            Map<String, Integer> unsupported) {
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        ExecutableElement[] probe = new ExecutableElement[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                if ("probe".contentEquals(node.getName())) {
                    Element element = parsed.trees().getElement(getCurrentPath());
                    if (element instanceof ExecutableElement method) {
                        probe[0] = method;
                    }
                }
                return super.visitMethod(node, unused);
            }
        }.scan(parsed.compilationUnits().getFirst(), null);
        return probe[0];
    }

    private static Map<String, TypeMirror> parameterTypes(ExecutableElement method) {
        Map<String, TypeMirror> types = new LinkedHashMap<>();
        for (VariableElement parameter : method.getParameters()) {
            types.put(parameter.getSimpleName().toString(), parameter.asType());
        }
        return types;
    }

    @Test
    void mapsEverySupportedTypeMirror() throws Exception {
        ExecutableElement probe = probeMethod();
        Map<String, TypeMirror> types = parameterTypes(probe);

        assertEquals(new TVoidType(), JavaTypeToTirMapper.map(probe.getReturnType(), "test"));
        assertEquals(new TBooleanType(), JavaTypeToTirMapper.map(types.get("primitiveBoolean"), "test"));
        assertEquals(new TIntType(), JavaTypeToTirMapper.map(types.get("primitiveByte"), "test"));
        assertEquals(new TIntType(), JavaTypeToTirMapper.map(types.get("primitiveShort"), "test"));
        assertEquals(new TIntType(), JavaTypeToTirMapper.map(types.get("primitiveInt"), "test"));
        assertEquals(new TBigintType(), JavaTypeToTirMapper.map(types.get("primitiveLong"), "test"));
        assertEquals(new TTextType(), JavaTypeToTirMapper.map(types.get("primitiveChar"), "test"));
        assertEquals(new TNumericType(38, 10), JavaTypeToTirMapper.map(types.get("primitiveFloat"), "test"));
        assertEquals(new TNumericType(38, 10), JavaTypeToTirMapper.map(types.get("primitiveDouble"), "test"));
        assertEquals(new TBooleanType(), JavaTypeToTirMapper.map(types.get("boxedBoolean"), "test"));
        assertEquals(new TIntType(), JavaTypeToTirMapper.map(types.get("boxedByte"), "test"));
        assertEquals(new TIntType(), JavaTypeToTirMapper.map(types.get("boxedShort"), "test"));
        assertEquals(new TIntType(), JavaTypeToTirMapper.map(types.get("boxedInteger"), "test"));
        assertEquals(new TBigintType(), JavaTypeToTirMapper.map(types.get("boxedLong"), "test"));
        assertEquals(new TTextType(), JavaTypeToTirMapper.map(types.get("boxedCharacter"), "test"));
        assertEquals(new TNumericType(38, 10), JavaTypeToTirMapper.map(types.get("boxedFloat"), "test"));
        assertEquals(new TNumericType(38, 10), JavaTypeToTirMapper.map(types.get("boxedDouble"), "test"));
        assertEquals(new TNumericType(38, 10), JavaTypeToTirMapper.map(types.get("bigDecimal"), "test"));
        assertEquals(new TNumericType(38, 10), JavaTypeToTirMapper.map(types.get("bigInteger"), "test"));
        assertEquals(new TTextType(), JavaTypeToTirMapper.map(types.get("text"), "test"));
        assertEquals(new TDateType(), JavaTypeToTirMapper.map(types.get("localDate"), "test"));
        assertEquals(new TTimeType(), JavaTypeToTirMapper.map(types.get("localTime"), "test"));
        assertEquals(new TTimestampType(), JavaTypeToTirMapper.map(types.get("localDateTime"), "test"));
        assertEquals(new TTimestampTzType(), JavaTypeToTirMapper.map(types.get("instant"), "test"));
        assertEquals(new TTimestampTzType(), JavaTypeToTirMapper.map(types.get("zonedDateTime"), "test"));
        assertEquals(new TTimestampTzType(), JavaTypeToTirMapper.map(types.get("offsetDateTime"), "test"));
        assertEquals(new TDurationType(), JavaTypeToTirMapper.map(types.get("duration"), "test"));
        assertEquals(new TPeriodType(), JavaTypeToTirMapper.map(types.get("period"), "test"));
        assertEquals(new TUuidType(), JavaTypeToTirMapper.map(types.get("uuid"), "test"));
        assertEquals(new TArrayType(new TUuidType()), JavaTypeToTirMapper.map(types.get("uuidList"), "test"));
        // byte[] is an opaque binary payload (BYTEA/LONGBLOB), NOT TArrayType(TIntType).
        assertEquals(new TBytesType(), JavaTypeToTirMapper.map(types.get("bytePayload"), "test"));
        assertEquals(new TArrayType(new TIntType()), JavaTypeToTirMapper.map(types.get("intArray"), "test"));
        assertEquals(new TArrayType(new TTextType()), JavaTypeToTirMapper.map(types.get("textList"), "test"));
        assertEquals(new TRecordType("MapperFixtureRow"), JavaTypeToTirMapper.map(types.get("row"), "test"));
        assertEquals(new TTextType(), JavaTypeToTirMapper.map(types.get("tier"), "test"));
    }

    @Test
    void unknownTypeMirrorThrowsDiagnosticNamingTypeAndContext() throws Exception {
        Map<String, TypeMirror> types = parameterTypes(probeMethod());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> JavaTypeToTirMapper.map(types.get("unsupported"), "variable 'lookup'"));

        assertTrue(exception.getMessage().contains("TITAN-E001"), exception.getMessage());
        assertTrue(exception.getMessage().contains("java.util.Map"), exception.getMessage());
        assertTrue(exception.getMessage().contains("variable 'lookup'"), exception.getMessage());
    }

    @Test
    void nullTypeMirrorThrowsDiagnosticWithContext() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> JavaTypeToTirMapper.map((TypeMirror) null, "variable 'ghost'"));

        assertTrue(exception.getMessage().contains("TITAN-E001"), exception.getMessage());
        assertTrue(exception.getMessage().contains("variable 'ghost'"), exception.getMessage());
    }

    @Test
    void mapsEverySupportedTypeName() {
        Set<String> records = Set.of("OrderRow");
        Set<String> enums = Set.of("Tier");

        assertEquals(new TBooleanType(), map("boolean", records, enums));
        assertEquals(new TBooleanType(), map("java.lang.Boolean", records, enums));
        assertEquals(new TIntType(), map("byte", records, enums));
        assertEquals(new TIntType(), map("short", records, enums));
        assertEquals(new TIntType(), map("int", records, enums));
        assertEquals(new TIntType(), map("java.lang.Integer", records, enums));
        assertEquals(new TBigintType(), map("long", records, enums));
        assertEquals(new TBigintType(), map("java.lang.Long", records, enums));
        assertEquals(new TTextType(), map("char", records, enums));
        assertEquals(new TTextType(), map("java.lang.Character", records, enums));
        assertEquals(new TNumericType(38, 10), map("float", records, enums));
        assertEquals(new TNumericType(38, 10), map("double", records, enums));
        assertEquals(new TNumericType(38, 10), map("java.lang.Float", records, enums));
        assertEquals(new TNumericType(38, 10), map("java.lang.Double", records, enums));
        assertEquals(new TNumericType(38, 10), map("java.math.BigDecimal", records, enums));
        assertEquals(new TNumericType(38, 10), map("java.math.BigInteger", records, enums));
        assertEquals(new TTextType(), map("java.lang.String", records, enums));
        assertEquals(new TTextType(), map("String", records, enums));
        assertEquals(new TDateType(), map("java.time.LocalDate", records, enums));
        assertEquals(new TTimeType(), map("java.time.LocalTime", records, enums));
        assertEquals(new TTimestampType(), map("java.time.LocalDateTime", records, enums));
        assertEquals(new TTimestampTzType(), map("java.time.Instant", records, enums));
        assertEquals(new TTimestampTzType(), map("java.time.ZonedDateTime", records, enums));
        assertEquals(new TTimestampTzType(), map("java.time.OffsetDateTime", records, enums));
        assertEquals(new TDurationType(), map("java.time.Duration", records, enums));
        assertEquals(new TPeriodType(), map("java.time.Period", records, enums));
        assertEquals(new TUuidType(), map("java.util.UUID", records, enums));
        assertEquals(new TUuidType(), map("UUID", records, enums));
        assertEquals(new TVoidType(), map("void", records, enums));
        assertEquals(new TBytesType(), map("byte[]", records, enums));
        assertEquals(new TArrayType(new TIntType()), map("int[]", records, enums));
        assertEquals(new TArrayType(new TTextType()), map("java.util.List<java.lang.String>", records, enums));
        assertEquals(new TArrayType(new TUuidType()), map("java.util.List<java.util.UUID>", records, enums));
        assertEquals(new TArrayType(new TUuidType()), map("java.util.UUID[]", records, enums));
        assertEquals(new TArrayType(new TRecordType("app", "OrderRow")), map("OrderRow[]", records, enums));
        assertEquals(new TRecordType("app", "OrderRow"), map("OrderRow", records, enums));
        assertEquals(new TTextType(), map("Tier", records, enums));
        // Nested source-local types render as Outer.Nested and resolve via the simple-name tail.
        assertEquals(new TRecordType("app", "OrderRow"), map("OrderDemo.OrderRow", records, enums));
        assertEquals(new TTextType(), map("OrderDemo.Tier", records, enums));
    }

    @Test
    void unknownTypeNameThrowsDiagnosticNamingTypeAndContext() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> map("java.util.Map<java.lang.String,java.lang.Integer>", Set.of(), Set.of()));

        assertTrue(exception.getMessage().contains("TITAN-E001"), exception.getMessage());
        assertTrue(exception.getMessage().contains("java.util.Map<java.lang.String,java.lang.Integer>"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("parameter 'lookup' of Demo#run"), exception.getMessage());
    }

    private static TirType map(String javaTypeName, Set<String> records, Set<String> enums) {
        return JavaTypeToTirMapper.map(javaTypeName, records, enums, "app", "parameter 'lookup' of Demo#run");
    }
}
