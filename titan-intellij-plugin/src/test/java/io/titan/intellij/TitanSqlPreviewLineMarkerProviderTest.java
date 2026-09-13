package io.titan.intellij;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class TitanSqlPreviewLineMarkerProviderTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("titan/dsl/StoredProcedure.java", """
                package titan.dsl;
                public @interface StoredProcedure {}
                """);
        myFixture.addFileToProject("titan/dsl/ScheduledJob.java", """
                package titan.dsl;
                public @interface ScheduledJob {
                    String cron();
                }
                """);
    }

    public void testShowsSqlPreviewGutterIconOnStoredProcedureMethod() {

        myFixture.configureByText("Test.java", """
                import titan.dsl.StoredProcedure;
                class Test {
                    @StoredProcedure
                    static void doWork() {}
                }
                """);

        var gutters = myFixture.findAllGutters();
        assertFalse(gutters.isEmpty());
        assertTrue(gutters.stream().anyMatch(g -> g.getIcon() != null));

        LineMarkerInfo<?> marker = lineMarkerOnMethodNamed("doWork");
        assertNotNull(marker);
        assertNotNull(marker.getNavigationHandler());
    }

    public void testShowsSqlPreviewGutterIconOnScheduledJobMethod() {
        myFixture.configureByText("JobTest.java", """
                import titan.dsl.ScheduledJob;
                class JobTest {
                    @ScheduledJob(cron = "0 * * * *")
                    static void hourlyWork() {}
                }
                """);

        var gutters = myFixture.findAllGutters();
        assertFalse(gutters.isEmpty());
        assertTrue(gutters.stream().anyMatch(g -> g.getIcon() != null));
    }

    private LineMarkerInfo<?> lineMarkerOnMethodNamed(String methodName) {
        PsiMethod method = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiMethod.class).stream()
                .filter(candidate -> methodName.equals(candidate.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(method);
        PsiIdentifier identifier = method.getNameIdentifier();
        assertNotNull(identifier);
        return new TitanSqlPreviewLineMarkerProvider().getLineMarkerInfo(identifier);
    }
}
