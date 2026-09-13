package io.titan.intellij;

import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class TitanSqlPreviewGeneratorTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("titan/dsl/StoredProcedure.java", """
                package titan.dsl;
                public @interface StoredProcedure {}
                """);
        myFixture.addFileToProject("titan/dsl/StoredFunction.java", """
                package titan.dsl;
                public @interface StoredFunction {}
                """);
        myFixture.addFileToProject("titan/dsl/Trigger.java", """
                package titan.dsl;
                public @interface Trigger {
                    String table();
                    TriggerTiming timing();
                    TriggerEvent[] event();
                    TriggerForEach forEach() default TriggerForEach.ROW;
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
                public enum TriggerForEach { ROW }
                """);
        myFixture.addFileToProject("titan/dsl/ScheduledJob.java", """
                package titan.dsl;
                public @interface ScheduledJob {
                    String cron();
                    String name() default "";
                }
                """);
        myFixture.addFileToProject("titan/dsl/SecurityDefiner.java", """
                package titan.dsl;
                public @interface SecurityDefiner {}
                """);
    }

    public void testGeneratesPostgresAndMySqlPreviewForStoredProcedure() {

        myFixture.configureByText("Test.java", """
                import titan.dsl.StoredProcedure;
                class Test {
                    @StoredProcedure
                    static void createUser(int id, String email) {}
                }
                """);

        PsiMethod method = myFixture.findElementByText("createUser", PsiMethod.class);
        assertNotNull(method);
        assertTrue(TitanSqlPreviewGenerator.supports(method));

        TitanSqlPreviewGenerator.SqlPreview preview = TitanSqlPreviewGenerator.generate(method);
        assertTrue(preview.postgresql().contains("CREATE OR REPLACE PROCEDURE createUser"));
        assertTrue(preview.postgresql().contains("IN p_id INT"));
        assertTrue(preview.mysql().contains("DROP PROCEDURE IF EXISTS createUser$$"));
        assertTrue(preview.mysql().contains("CREATE PROCEDURE createUser"));
        assertTrue(preview.mysql().contains("IN p_email TEXT"));
    }

    public void testGeneratesMySqlFunctionPreviewWithDropPreamble() {
        myFixture.configureByText("FunctionTest.java", """
                import titan.dsl.StoredFunction;
                class FunctionTest {
                    @StoredFunction
                    static String displayName(int id) { return \"\"; }
                }
                """);

        PsiMethod method = myFixture.findElementByText("displayName", PsiMethod.class);
        assertNotNull(method);

        TitanSqlPreviewGenerator.SqlPreview preview = TitanSqlPreviewGenerator.generate(method);
        assertTrue(preview.mysql().contains("DROP FUNCTION IF EXISTS displayName$$"));
        assertTrue(preview.mysql().contains("CREATE FUNCTION displayName"));
    }

    public void testGeneratesSecurityDefinerRoutinePreview() {
        myFixture.configureByText("SecurityDefinerTest.java", """
                import titan.dsl.SecurityDefiner;
                import titan.dsl.StoredProcedure;
                class SecurityDefinerTest {
                    @StoredProcedure
                    @SecurityDefiner
                    static void rotateKeys() {}
                }
                """);

        PsiMethod method = myFixture.findElementByText("rotateKeys", PsiMethod.class);
        assertNotNull(method);

        TitanSqlPreviewGenerator.SqlPreview preview = TitanSqlPreviewGenerator.generate(method);
        assertTrue(preview.postgresql().contains("SECURITY DEFINER"));
        assertTrue(preview.mysql().contains("SQL SECURITY DEFINER"));
    }

    public void testGeneratesPreviewForTriggerEntryPoint() {
        myFixture.configureByText("TriggerTest.java", """
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerForEach;
                import titan.dsl.TriggerTiming;
                class TriggerTest {
                    @Trigger(
                        table = "public.accounts",
                        timing = TriggerTiming.AFTER,
                        event = {TriggerEvent.UPDATE, TriggerEvent.DELETE},
                        forEach = TriggerForEach.ROW
                    )
                    static void enforceAccountPolicy() {}
                }
                """);

        PsiMethod method = myFixture.findElementByText("enforceAccountPolicy", PsiMethod.class);
        assertNotNull(method);
        assertTrue(TitanSqlPreviewGenerator.supports(method));

        TitanSqlPreviewGenerator.SqlPreview preview = TitanSqlPreviewGenerator.generate(method);
        assertTrue(preview.postgresql().contains("CREATE OR REPLACE FUNCTION enforceAccountPolicy() RETURNS TRIGGER"));
        assertTrue(preview.postgresql().contains("AFTER UPDATE OR DELETE ON public.accounts"));
        assertTrue(preview.mysql().contains("CREATE TRIGGER enforceAccountPolicy_trg_update AFTER UPDATE ON public.accounts"));
        assertTrue(preview.mysql().contains("CREATE TRIGGER enforceAccountPolicy_trg_delete AFTER DELETE ON public.accounts"));
    }

    public void testGeneratesPreviewForScheduledJobEntryPoint() {
        myFixture.configureByText("JobTest.java", """
                import titan.dsl.ScheduledJob;
                class JobTest {
                    @ScheduledJob(cron = "0 * * * *", name = "hourly_refresh")
                    static void refreshMaterializedData() {}
                }
                """);

        PsiMethod method = myFixture.findElementByText("refreshMaterializedData", PsiMethod.class);
        assertNotNull(method);
        assertTrue(TitanSqlPreviewGenerator.supports(method));

        TitanSqlPreviewGenerator.SqlPreview preview = TitanSqlPreviewGenerator.generate(method);
        assertTrue(preview.postgresql().contains("IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron')"));
        assertTrue(preview.postgresql().contains("PERFORM cron.schedule('hourly_refresh', '0 * * * *'"));
        assertTrue(preview.mysql().contains("DROP EVENT IF EXISTS hourly_refresh;"));
        assertTrue(preview.mysql().contains("CREATE EVENT hourly_refresh"));
        assertTrue(preview.mysql().contains("ON SCHEDULE EVERY 1 HOUR STARTS CURRENT_DATE + INTERVAL 0 MINUTE"));
        assertTrue(preview.mysql().contains("CALL refreshMaterializedData();"));
    }

    public void testScheduledJobPreviewShowsFallbackNoteForUnmappableCron() {
        myFixture.configureByText("ComplexJobTest.java", """
                import titan.dsl.ScheduledJob;
                class ComplexJobTest {
                    @ScheduledJob(cron = "0 9 * * MON-FRI", name = "weekday_refresh")
                    static void refreshWeekday() {}
                }
                """);

        PsiMethod method = myFixture.findElementByText("refreshWeekday", PsiMethod.class);
        assertNotNull(method);

        TitanSqlPreviewGenerator.SqlPreview preview = TitanSqlPreviewGenerator.generate(method);
        assertTrue(preview.mysql().contains("DROP EVENT IF EXISTS weekday_refresh;"));
        assertTrue(preview.mysql().contains("cron expression is not directly representable"));
        assertTrue(preview.mysql().contains("ON SCHEDULE EVERY 1 DAY STARTS CURRENT_TIMESTAMP"));
    }
}
