package io.titan.intellij;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class TitanUnsupportedFeatureInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new TitanUnsupportedFeatureInspection());
    }

    public void testHighlightsUnsupportedLambdaInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("Proc.java", """
                import titan.dsl.StoredProcedure;
                class Proc {
                    interface Fn { void run(); }

                    @StoredProcedure
                    static void run() {
                        Fn r = <error descr="TITAN-E001 Unsupported feature in Titan entry point: lambda expression">() -> { }</error>;
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testHighlightsUnsupportedLambdaInsideStoredFunction() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("Func.java", """
                import titan.dsl.StoredFunction;
                class Func {
                    interface Fn { int run(); }

                    @StoredFunction
                    static int run() {
                        Fn r = <error descr="TITAN-E001 Unsupported feature in Titan entry point: lambda expression">() -> 1</error>;
                        return r.run();
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testHighlightsUnsupportedLambdaInsideTrigger() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("TriggerProc.java", """
                import titan.dsl.Trigger;
                import titan.dsl.TriggerTiming;
                import titan.dsl.TriggerEvent;

                class TriggerProc {
                    interface Fn { void run(); }

                    @Trigger(table = "accounts", timing = TriggerTiming.BEFORE, event = { TriggerEvent.INSERT })
                    static void validate() {
                        Fn r = <error descr="TITAN-E001 Unsupported feature in Titan entry point: lambda expression">() -> { }</error>;
                        r.run();
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testHighlightsUnsupportedLambdaInsideScheduledJob() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("NightlyJob.java", """
                import titan.dsl.ScheduledJob;

                class NightlyJob {
                    interface Fn { void run(); }

                    @ScheduledJob(cron = "0 2 * * *", name = "nightly")
                    static void run() {
                        Fn r = <error descr="TITAN-E001 Unsupported feature in Titan entry point: lambda expression">() -> { }</error>;
                        r.run();
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testHighlightsMethodReferenceInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcMethodRef.java", """
                import titan.dsl.StoredProcedure;
                class ProcMethodRef {
                    interface Fn { int run(int x); }

                    static int helper(int x) { return x; }

                    @StoredProcedure
                    static void run() {
                        Fn fn = <error descr="TITAN-E001 Unsupported feature in Titan entry point: method reference">ProcMethodRef::helper</error>;
                        fn.run(1);
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testHighlightsSynchronizedInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcSync.java", """
                import titan.dsl.StoredProcedure;
                class ProcSync {
                    static class Lock {}

                    @StoredProcedure
                    static void run() {
                        Lock lock = new Lock();
                        <error descr="TITAN-E001 Unsupported feature in Titan entry point: synchronized block">synchronized (lock) {
                        }</error>
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testHighlightsAssertInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcAssert.java", """
                import titan.dsl.StoredProcedure;
                class ProcAssert {
                    @StoredProcedure
                    static void run() {
                        <error descr="TITAN-E001 Unsupported feature in Titan entry point: assert statement">assert true;</error>
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testHighlightsColonStyleSwitchStatementInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcSwitchStmt.java", """
                import titan.dsl.StoredProcedure;
                class ProcSwitchStmt {
                    @StoredProcedure
                    static void run(int x) {
                        <error descr="TITAN-E001 Unsupported feature in Titan entry point: switch statement with colon-style cases">switch (x) {
                            case 1:
                                break;
                            default:
                                break;
                        }</error>
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testDoesNotHighlightArrowSwitchStatementInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcSwitchArrowStmt.java", """
                import titan.dsl.StoredProcedure;
                class ProcSwitchArrowStmt {
                    @StoredProcedure
                    static void run(int x) {
                        switch (x) {
                            case 1 -> { }
                            default -> { }
                        }
                    }
                }
                """);

        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR));
    }

    public void testHighlightsNestedTypeDeclarationInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcNestedType.java", """
                import titan.dsl.StoredProcedure;
                class ProcNestedType {
                    @StoredProcedure
                    static void run() {
                        <error descr="TITAN-E001 Unsupported feature in Titan entry point: nested type declaration">class LocalHelper {
                            int value() { return 1; }
                        }</error>
                        new LocalHelper().value();
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testDoesNotHighlightUnsupportedFeatureOutsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("Plain.java", """
                class Plain {
                    interface Fn { void run(); }
                    static void run() {
                        Fn r = () -> { };
                    }
                }
                """);

        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR));
    }

    public void testDoesNotHighlightDoWhileInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcDoWhile.java", """
                import titan.dsl.StoredProcedure;
                class ProcDoWhile {
                    @StoredProcedure
                    static void run() {
                        int i = 0;
                        do {
                            i++;
                        } while (i < 1);
                    }
                }
                """);

        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR));
    }

    public void testHighlightsAbortWithErrorOutsideTriggerContext() {
        addTitanEntrypointAnnotations();
        addTitanDslClass();

        myFixture.configureByText("ProcAbortWithError.java", """
                import titan.dsl.DSL;
                import titan.dsl.StoredProcedure;

                class ProcAbortWithError {
                    @StoredProcedure
                    static void run() {
                        <error descr="TITAN-E001 Unsupported feature in Titan entry point: abortWithError() outside trigger context">DSL.abortWithError("boom")</error>;
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testDoesNotHighlightAbortWithErrorInsideTriggerContext() {
        addTitanEntrypointAnnotations();
        addTitanDslClass();

        myFixture.configureByText("TriggerAbortWithError.java", """
                import titan.dsl.DSL;
                import titan.dsl.Trigger;
                import titan.dsl.TriggerTiming;
                import titan.dsl.TriggerEvent;

                class TriggerAbortWithError {
                    @Trigger(table = "accounts", timing = TriggerTiming.BEFORE, event = { TriggerEvent.INSERT })
                    static void run() {
                        DSL.abortWithError("boom");
                    }
                }
                """);

        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR));
    }

    public void testHighlightsColonStyleSwitchExpressionInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcSwitchExpr.java", """
                import titan.dsl.StoredProcedure;
                class ProcSwitchExpr {
                    @StoredProcedure
                    static int run(int x) {
                        return <error descr="TITAN-E001 Unsupported feature in Titan entry point: switch expression with colon-style cases">switch (x) {
                            case 1: yield 10;
                            default: yield 0;
                        }</error>;
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testDoesNotHighlightArrowSwitchExpressionInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcSwitchArrowExpr.java", """
                import titan.dsl.StoredProcedure;
                class ProcSwitchArrowExpr {
                    @StoredProcedure
                    static int run(int x) {
                        return switch (x) {
                            case 1 -> 10;
                            default -> 0;
                        };
                    }
                }
                """);

        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR));
    }

    public void testHighlightsNonExhaustiveEnumSwitchExpressionInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcSwitchEnumNonExhaustive.java", """
                import titan.dsl.StoredProcedure;
                class ProcSwitchEnumNonExhaustive {
                    enum Status { NEW, DONE }

                    @StoredProcedure
                    static int run(Status s) {
                        return <error descr="TITAN-E001 Unsupported feature in Titan entry point: non-exhaustive switch expression (default case required unless all enum constants are covered)">switch (<error descr="'switch' expression does not cover all possible input values">s</error>) {
                            case NEW -> 1;
                        }</error>;
                    }
                }
                """);

        myFixture.checkHighlighting();
    }

    public void testDoesNotHighlightExhaustiveEnumSwitchExpressionInsideStoredProcedure() {
        addTitanEntrypointAnnotations();

        myFixture.configureByText("ProcSwitchEnumExhaustive.java", """
                import titan.dsl.StoredProcedure;
                class ProcSwitchEnumExhaustive {
                    enum Status { NEW, DONE }

                    @StoredProcedure
                    static int run(Status s) {
                        return switch (s) {
                            case NEW -> 1;
                            case DONE -> 2;
                        };
                    }
                }
                """);

        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR));
    }

    private void addTitanDslClass() {
        myFixture.addFileToProject("titan/dsl/DSL.java", """
                package titan.dsl;

                public final class DSL {
                    private DSL() {}

                    public static void abortWithError(String message) {
                    }
                }
                """);
    }

    private void addTitanEntrypointAnnotations() {
        myFixture.addFileToProject("titan/dsl/StoredProcedure.java", """
                package titan.dsl;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface StoredProcedure {}
                """);
        myFixture.addFileToProject("titan/dsl/StoredFunction.java", """
                package titan.dsl;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface StoredFunction {}
                """);
        myFixture.addFileToProject("titan/dsl/Trigger.java", """
                package titan.dsl;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface Trigger {
                    String table();
                    TriggerTiming timing();
                    TriggerEvent[] event();
                    TriggerForEach forEach() default TriggerForEach.ROW;
                }
                """);
        myFixture.addFileToProject("titan/dsl/ScheduledJob.java", """
                package titan.dsl;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface ScheduledJob {
                    String cron();
                    String name() default "";
                }
                """);
        myFixture.addFileToProject("titan/dsl/TriggerTiming.java", """
                package titan.dsl;
                public enum TriggerTiming { BEFORE, AFTER }
                """);
        myFixture.addFileToProject("titan/dsl/TriggerEvent.java", """
                package titan.dsl;
                public enum TriggerEvent { INSERT, UPDATE, DELETE }
                """);
        myFixture.addFileToProject("titan/dsl/TriggerForEach.java", """
                package titan.dsl;
                public enum TriggerForEach { ROW, STATEMENT }
                """);
    }
}
