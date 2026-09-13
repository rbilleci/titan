package io.titan.intellij;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Basic completion support for Titan table descriptors.
 */
public final class TitanDslCompletionContributor extends CompletionContributor {

    private static final String COLUMN_FQN = "titan.dsl.Column";

    public TitanDslCompletionContributor() {
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement(),
                new CompletionProvider<>() {
                    @Override
                    protected void addCompletions(
                            @NotNull CompletionParameters parameters,
                            @NotNull ProcessingContext context,
                            @NotNull CompletionResultSet result
                    ) {
                        PsiReferenceExpression reference = PsiTreeUtil.getParentOfType(
                                parameters.getPosition(),
                                PsiReferenceExpression.class
                        );

                        // ACCOUNTS.<caret> -> suggest table columns
                        if (reference != null && reference.getQualifierExpression() != null) {
                            PsiType qualifierType = reference.getQualifierExpression().getType();
                            if (qualifierType == null) {
                                return;
                            }

                            PsiClass resolved = PsiUtilSupport.resolveClass(qualifierType);
                            if (resolved == null) {
                                return;
                            }

                            addColumnFieldSuggestions(resolved, result);
                            return;
                        }

                        // .where(<caret>) -> suggest column comparison templates
                        PsiMethodCallExpression whereCall = PsiTreeUtil.getParentOfType(
                                parameters.getPosition(),
                                PsiMethodCallExpression.class
                        );
                        if (whereCall == null || !"where".equals(whereCall.getMethodExpression().getReferenceName())) {
                            return;
                        }

                        Set<PsiClass> queryTableClasses = resolveQueryTableClasses(whereCall);
                        if (queryTableClasses.isEmpty()) {
                            return;
                        }

                        for (PsiClass tableClass : queryTableClasses) {
                            for (PsiField field : tableClass.getAllFields()) {
                                if (!PsiUtilSupport.isTypeOrSubtype(field.getType(), COLUMN_FQN)) {
                                    continue;
                                }
                                String column = field.getName();
                                result.addElement(LookupElementBuilder.create(column + ".eq(...)"));
                                result.addElement(LookupElementBuilder.create(column + ".ne(...)"));
                                result.addElement(LookupElementBuilder.create(column + ".lt(...)"));
                                result.addElement(LookupElementBuilder.create(column + ".le(...)"));
                                result.addElement(LookupElementBuilder.create(column + ".gt(...)"));
                                result.addElement(LookupElementBuilder.create(column + ".ge(...)"));
                                result.addElement(LookupElementBuilder.create(column + ".in(...)"));
                                result.addElement(LookupElementBuilder.create(column + ".between(..., ...)"));
                                result.addElement(LookupElementBuilder.create(column + ".like(...)"));
                                result.addElement(LookupElementBuilder.create(column + ".isNull()"));
                                result.addElement(LookupElementBuilder.create(column + ".isNotNull()"));
                            }
                        }
                    }
                }
        );
    }

    private static void addColumnFieldSuggestions(PsiClass resolved, CompletionResultSet result) {
        for (PsiField field : resolved.getAllFields()) {
            if (PsiUtilSupport.isTypeOrSubtype(field.getType(), COLUMN_FQN)) {
                result.addElement(LookupElementBuilder.create(field.getName())
                        .withTypeText(field.getType().getPresentableText(), true));
            }
        }
    }

    private static Set<PsiClass> resolveQueryTableClasses(PsiMethodCallExpression whereCall) {
        Set<PsiClass> tableClasses = new LinkedHashSet<>();
        PsiExpression qualifier = whereCall.getMethodExpression().getQualifierExpression();
        while (qualifier instanceof PsiMethodCallExpression methodCall) {
            String name = methodCall.getMethodExpression().getReferenceName();
            if ("from".equals(name)
                    || "join".equals(name)
                    || "leftJoin".equals(name)
                    || "rightJoin".equals(name)
                    || "fullOuterJoin".equals(name)
                    || "crossJoin".equals(name)) {
                PsiExpression[] args = methodCall.getArgumentList().getExpressions();
                if (args.length > 0) {
                    PsiType tableType = args[0].getType();
                    PsiClass tableClass = tableType == null ? null : PsiUtilSupport.resolveClass(tableType);
                    if (tableClass != null) {
                        tableClasses.add(tableClass);
                    }
                }
            }
            qualifier = methodCall.getMethodExpression().getQualifierExpression();
        }
        return tableClasses;
    }
}
