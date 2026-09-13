package io.titan.transpiler;

import java.util.List;
import javax.lang.model.element.ExecutableElement;

/**
 * The single authority for the {@code Owner#name(paramType,paramType)} method-signature key
 * used to correlate discovered entry points, internal helpers and resolved invocations across
 * discovery, validation, dependency analysis and lowering (audit D11).
 *
 * <p>The format is pinned by {@code MethodSignatureKeysTest}; every producer and consumer of
 * the key must go through this class.
 */
public final class MethodSignatureKeys {

    private MethodSignatureKeys() {
    }

    /** Key for a resolved {@link ExecutableElement}, e.g. {@code com.acme.Owners#fetch(int)}. */
    public static String of(ExecutableElement method) {
        return of(
                method.getEnclosingElement().toString(),
                method.getSimpleName().toString(),
                method.getParameters().stream().map(parameter -> parameter.asType().toString()).toList());
    }

    /** Key from pre-rendered parts, e.g. for {@code DiscoveredEntryPoint}. */
    public static String of(String className, String methodName, List<String> parameterTypes) {
        return className + "#" + methodName + "(" + String.join(",", parameterTypes) + ")";
    }
}
