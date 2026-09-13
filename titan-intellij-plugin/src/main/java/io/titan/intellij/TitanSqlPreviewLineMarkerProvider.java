package io.titan.intellij;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public final class TitanSqlPreviewLineMarkerProvider implements LineMarkerProvider {
    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier identifier)) {
            return null;
        }
        if (!(identifier.getParent() instanceof PsiMethod method)) {
            return null;
        }
        if (!isTitanEntrypoint(method)) {
            return null;
        }
        return new LineMarkerInfo<PsiElement>(
                identifier,
                identifier.getTextRange(),
                AllIcons.Actions.Preview,
                element1 -> "Preview Titan SQL",
                (mouseEvent, elt) -> {
                    if (elt instanceof PsiIdentifier methodIdentifier
                            && methodIdentifier.getParent() instanceof PsiMethod entryPointMethod) {
                        TitanSqlPreviewPopup.show(entryPointMethod);
                    }
                },
                GutterIconRenderer.Alignment.LEFT,
                () -> "Titan SQL Preview"
        );
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
                                       @NotNull Collection<? super LineMarkerInfo<?>> result) {
        for (PsiElement element : elements) {
            LineMarkerInfo<?> info = getLineMarkerInfo(element);
            if (info != null) {
                result.add(info);
            }
        }
    }

    private boolean isTitanEntrypoint(PsiMethod method) {
        return hasAnnotation(method, "titan.dsl.StoredProcedure")
                || hasAnnotation(method, "titan.dsl.StoredFunction")
                || hasAnnotation(method, "titan.dsl.Trigger")
                || hasAnnotation(method, "titan.dsl.ScheduledJob");
    }

    private boolean hasAnnotation(PsiMethod method, String qualifiedName) {
        PsiAnnotation annotation = method.getModifierList().findAnnotation(qualifiedName);
        return annotation != null;
    }
}
