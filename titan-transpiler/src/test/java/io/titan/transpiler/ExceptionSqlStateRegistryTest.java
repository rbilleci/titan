package io.titan.transpiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.type.TypeMirror;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExceptionSqlStateRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsKnownExceptionTypesToStableSqlStates() {
        ExceptionSqlStateRegistry registry = new ExceptionSqlStateRegistry();

        assertEquals(List.of("22012"), registry.sqlStatesFor("java.lang.ArithmeticException"));
        assertEquals(List.of("45001"), registry.sqlStatesFor("NullPointerException"));
        assertEquals("22023", registry.sqlStateForThrow("java.lang.IllegalArgumentException"));
    }

    @Test
    void expandsCatchSqlStatesUsingKnownHierarchy() {
        ExceptionSqlStateRegistry registry = new ExceptionSqlStateRegistry();

        assertEquals(
                List.of("22023", "55000", "22012", "45001", "2202E", "0A000", "45000"),
                registry.sqlStatesFor("RuntimeException"));
        assertEquals(
                List.of("22023", "55000", "22012", "45001", "2202E", "0A000", "45000", "HY000"),
                registry.sqlStatesFor("java.lang.Exception"));
    }

    @Test
    void fallsBackToGenericStateForUnknownTypes() {
        ExceptionSqlStateRegistry registry = new ExceptionSqlStateRegistry();

        assertEquals(List.of("45000"), registry.sqlStatesFor("com.example.CustomDomainException"));
        assertEquals("45000", registry.sqlStateForThrow((String) null));
        assertEquals("45000", registry.sqlStateForThrow("com.example.CustomDomainException"));
    }

    // ------------------------------------------------------------------
    // Plan 2.2 (F-11): FQN-keyed per-run user exception allocation.
    // ------------------------------------------------------------------

    private static final String USER_EXCEPTION_SOURCE = """
            class UserExceptionFixture {
                static final class QuotaException extends RuntimeException {
                    QuotaException(String message) { super(message); }
                }

                static final class StaleException extends RuntimeException {
                    StaleException(String message) { super(message); }
                }

                static void quota() {
                    throw new QuotaException("quota");
                }

                static void stale() {
                    throw new StaleException("stale");
                }

                static void generic() {
                    throw new RuntimeException("generic");
                }
            }
            """;

    @Test
    void allocatesDistinctDeterministicStatesPerUserExceptionFqn() throws Exception {
        ParsedSources parsed = parseFixture();
        ExceptionSqlStateRegistry registry = ExceptionSqlStateRegistry.forSources(parsed);
        List<TypeMirror> thrownTypes = thrownTypesInSourceOrder(parsed);

        assertEquals(3, thrownTypes.size());
        String quotaState = registry.sqlStateForThrow(thrownTypes.get(0));
        String staleState = registry.sqlStateForThrow(thrownTypes.get(1));
        String genericState = registry.sqlStateForThrow(thrownTypes.get(2));

        // First-seen source order, starting after the JDK-owned states (45000 generic,
        // 45001 NullPointerException).
        assertEquals("45002", quotaState);
        assertEquals("45003", staleState);
        assertNotEquals(quotaState, staleState);
        // JDK mappings stay as-is: a plain RuntimeException keeps the generic state.
        assertEquals("45000", genericState);
    }

    @Test
    void perRunRegistryIsMemoizedPerParsedSources() throws Exception {
        ParsedSources parsed = parseFixture();
        assertSame(ExceptionSqlStateRegistry.forSources(parsed), ExceptionSqlStateRegistry.forSources(parsed));
    }

    @Test
    void catchDispatchSetsFollowTheResolvedHierarchy() throws Exception {
        ParsedSources parsed = parseFixture();
        ExceptionSqlStateRegistry registry = ExceptionSqlStateRegistry.forSources(parsed);
        List<TypeMirror> thrownTypes = thrownTypesInSourceOrder(parsed);
        TypeMirror quota = thrownTypes.get(0);
        TypeMirror stale = thrownTypes.get(1);
        TypeMirror runtimeException = thrownTypes.get(2);

        // A catch of one user exception matches only that exception: never the generic 45000
        // raise, never the sibling user exception (the F-11 wrong-catch interception bug).
        assertEquals(List.of("45002"), registry.sqlStatesForCatch(quota, "QuotaException"));
        assertEquals(List.of("45003"), registry.sqlStatesForCatch(stale, "StaleException"));

        // A catch of the common supertype catches the JDK set plus every assignable thrown
        // user exception.
        List<String> supertypeStates = registry.sqlStatesForCatch(runtimeException, "RuntimeException");
        assertTrue(supertypeStates.contains("45000"));
        assertTrue(supertypeStates.contains("45002"));
        assertTrue(supertypeStates.contains("45003"));
        assertFalse(supertypeStates.contains("HY000"), "SQLException is not a RuntimeException subtype");
    }

    private ParsedSources parseFixture() throws Exception {
        Path sourceFile = tempDir.resolve("UserExceptionFixture.java");
        Files.writeString(sourceFile, USER_EXCEPTION_SOURCE);
        return new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
    }

    private static List<TypeMirror> thrownTypesInSourceOrder(ParsedSources parsed) {
        List<TypeMirror> thrownTypes = new ArrayList<>();
        for (var unit : parsed.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitThrow(ThrowTree node, Void unused) {
                    Tree expression = node.getExpression();
                    thrownTypes.add(parsed.trees().getTypeMirror(new TreePath(getCurrentPath(), expression)));
                    return super.visitThrow(node, unused);
                }
            }.scan(unit, null);
        }
        return thrownTypes;
    }
}
