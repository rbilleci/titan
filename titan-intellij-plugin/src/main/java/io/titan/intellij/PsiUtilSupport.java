package io.titan.intellij;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiType;

final class PsiUtilSupport {
    private PsiUtilSupport() {
    }

    static PsiClass resolveClass(PsiType type) {
        if (type instanceof PsiClassType classType) {
            return classType.resolve();
        }
        return null;
    }

    static boolean isTypeOrSubtype(PsiType candidate, String expectedFqn) {
        if (!(candidate instanceof PsiClassType classType)) {
            return false;
        }
        PsiClass resolved = classType.resolve();
        if (resolved == null) {
            return false;
        }
        if (expectedFqn.equals(resolved.getQualifiedName())) {
            return true;
        }
        for (PsiClass superClass : resolved.getSupers()) {
            String fqn = superClass.getQualifiedName();
            if (expectedFqn.equals(fqn)) {
                return true;
            }
        }
        return false;
    }
}
