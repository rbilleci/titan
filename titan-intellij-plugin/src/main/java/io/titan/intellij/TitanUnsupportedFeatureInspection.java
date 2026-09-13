package io.titan.intellij;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiAssertStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiSwitchBlock;
import com.intellij.psi.PsiSwitchExpression;
import com.intellij.psi.PsiSwitchLabelStatement;
import com.intellij.psi.PsiSwitchLabelStatementBase;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiSynchronizedStatement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

public final class TitanUnsupportedFeatureInspection extends AbstractBaseJavaLocalInspectionTool {

    private static final String STORED_PROCEDURE_FQN = "titan.dsl.StoredProcedure";
    private static final String STORED_FUNCTION_FQN = "titan.dsl.StoredFunction";
    private static final String TRIGGER_FQN = "titan.dsl.Trigger";
    private static final String SCHEDULED_JOB_FQN = "titan.dsl.ScheduledJob";
    private static final String ERROR_PREFIX = "TITAN-E001 Unsupported feature in Titan entry point: ";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) {
                if (!isInsideTitanEntrypoint(expression)) {
                    return;
                }
                holder.registerProblem(expression, ERROR_PREFIX + "lambda expression");
            }

            @Override
            public void visitSwitchStatement(@NotNull PsiSwitchStatement statement) {
                if (!isInsideTitanEntrypoint(statement)) {
                    return;
                }
                if (hasColonStyleCase(statement)) {
                    holder.registerProblem(statement, ERROR_PREFIX + "switch statement with colon-style cases");
                }
            }

            @Override
            public void visitMethodReferenceExpression(@NotNull PsiMethodReferenceExpression expression) {
                if (!isInsideTitanEntrypoint(expression)) {
                    return;
                }
                holder.registerProblem(expression, ERROR_PREFIX + "method reference");
            }

            @Override
            public void visitSynchronizedStatement(@NotNull PsiSynchronizedStatement statement) {
                if (!isInsideTitanEntrypoint(statement)) {
                    return;
                }
                holder.registerProblem(statement, ERROR_PREFIX + "synchronized block");
            }

            @Override
            public void visitAssertStatement(@NotNull PsiAssertStatement statement) {
                if (!isInsideTitanEntrypoint(statement)) {
                    return;
                }
                holder.registerProblem(statement, ERROR_PREFIX + "assert statement");
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (!isInsideTitanEntrypoint(expression)) {
                    return;
                }
                String calledName = expression.getMethodExpression().getReferenceName();
                if (!"abortWithError".equals(calledName)) {
                    return;
                }
                PsiMethod enclosingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod.class);
                if (enclosingMethod == null) {
                    return;
                }
                if (hasAnnotation(enclosingMethod, TRIGGER_FQN)) {
                    return;
                }
                holder.registerProblem(expression, ERROR_PREFIX + "abortWithError() outside trigger context");
            }

            @Override
            public void visitClass(@NotNull PsiClass aClass) {
                if (!isInsideTitanEntrypoint(aClass)) {
                    return;
                }
                if (PsiTreeUtil.getParentOfType(aClass, PsiMethod.class, true) == null) {
                    return;
                }
                if (aClass.getName() == null) {
                    return;
                }
                holder.registerProblem(aClass, ERROR_PREFIX + "nested type declaration");
            }

            @Override
            public void visitSwitchExpression(@NotNull PsiSwitchExpression expression) {
                if (!isInsideTitanEntrypoint(expression)) {
                    return;
                }
                if (hasColonStyleCase(expression)) {
                    holder.registerProblem(expression, ERROR_PREFIX + "switch expression with colon-style cases");
                    return;
                }
                if (isNonExhaustiveEnumSwitch(expression)) {
                    holder.registerProblem(expression, ERROR_PREFIX + "non-exhaustive switch expression (default case required unless all enum constants are covered)");
                }
            }
        };
    }

    private static boolean isInsideTitanEntrypoint(@NotNull com.intellij.psi.PsiElement element) {
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) {
            return false;
        }
        return hasAnnotation(method, STORED_PROCEDURE_FQN)
                || hasAnnotation(method, STORED_FUNCTION_FQN)
                || hasAnnotation(method, TRIGGER_FQN)
                || hasAnnotation(method, SCHEDULED_JOB_FQN);
    }

    private static boolean hasAnnotation(PsiMethod method, String fqn) {
        PsiAnnotation annotation = method.getAnnotation(fqn);
        return annotation != null;
    }

    private static boolean hasColonStyleCase(PsiSwitchBlock switchBlock) {
        for (PsiSwitchLabelStatementBase label : PsiTreeUtil.findChildrenOfType(switchBlock, PsiSwitchLabelStatementBase.class)) {
            if (label instanceof PsiSwitchLabelStatement) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNonExhaustiveEnumSwitch(PsiSwitchExpression expression) {
        if (expression.getExpression() == null || expression.getExpression().getType() == null) {
            return false;
        }
        PsiClass selectorClass = PsiUtil.resolveClassInType(expression.getExpression().getType());
        if (selectorClass == null || !selectorClass.isEnum()) {
            return false;
        }

        Set<String> enumConstants = new HashSet<>();
        for (var field : selectorClass.getFields()) {
            if (field instanceof PsiEnumConstant constant) {
                enumConstants.add(constant.getName());
            }
        }
        if (enumConstants.isEmpty()) {
            return false;
        }

        Set<String> covered = new HashSet<>();
        boolean hasDefault = false;

        for (PsiSwitchLabelStatementBase label : PsiTreeUtil.findChildrenOfType(expression, PsiSwitchLabelStatementBase.class)) {
            if (label.isDefaultCase()) {
                hasDefault = true;
                continue;
            }
            var values = label.getCaseValues();
            if (values == null) {
                continue;
            }
            for (var value : values.getExpressions()) {
                if (value instanceof PsiReferenceExpression ref) {
                    String name = ref.getReferenceName();
                    if (name != null) {
                        covered.add(name);
                    }
                }
            }
        }

        return !hasDefault && !covered.containsAll(enumConstants);
    }
}
