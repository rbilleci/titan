package io.titan.transpiler.tir;

import io.titan.transpiler.NamingConventionEngine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class RoutineSqlNameAllocator {
    private final NamingConventionEngine namingConventionEngine;
    private final Set<String> reservedFoldedNames = new LinkedHashSet<>();
    private final Set<String> routineParameterDeclarationNames = new LinkedHashSet<>();
    private final java.util.LinkedHashMap<String, String> emittedNamesBySourceName = new java.util.LinkedHashMap<>();
    // B-10: source-name -> declared TIR type for parameters and local variables, so the emitter can
    // tell that a VariableRefExpression carries a boolean and coerce it to 'true'/'false' text.
    private final java.util.LinkedHashMap<String, TirType> variableTypesBySourceName = new java.util.LinkedHashMap<>();
    private final List<RoutineParameter> routineParameters;

    private RoutineSqlNameAllocator() {
        this.namingConventionEngine = new NamingConventionEngine();
        this.routineParameters = List.of();
    }

    RoutineSqlNameAllocator(
            NamingConventionEngine namingConventionEngine,
            List<RoutineParameter> routineParameters,
            Block body,
            List<String> reservedNames
    ) {
        this.namingConventionEngine = Objects.requireNonNull(namingConventionEngine, "namingConventionEngine");
        reserveAll(reservedNames);
        this.routineParameters = allocateRoutineParameters(routineParameters == null ? List.of() : routineParameters);
        collectBlock(body);
    }

    static RoutineSqlNameAllocator empty() {
        return new RoutineSqlNameAllocator();
    }

    List<RoutineParameter> routineParameters() {
        return routineParameters;
    }

    boolean isRoutineParameterDeclaration(String name) {
        return name != null && routineParameterDeclarationNames.contains(name);
    }

    String emittedName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        return emittedNamesBySourceName.getOrDefault(name, name);
    }

    /** Declared TIR type of a parameter or local variable by its source name, or null if unknown (B-10). */
    TirType variableType(String sourceName) {
        if (sourceName == null) {
            return null;
        }
        return variableTypesBySourceName.get(sourceName);
    }

    String allocateGeneratedName(String preferredBase) {
        return allocateUnique(preferredBase);
    }

    private List<RoutineParameter> allocateRoutineParameters(List<RoutineParameter> parameters) {
        List<RoutineParameter> allocated = new ArrayList<>();
        for (RoutineParameter parameter : parameters) {
            String preferredBase = parameter.sqlName() == null || parameter.sqlName().isBlank()
                    ? namingConventionEngine.parameterName(parameter.javaName())
                    : parameter.sqlName();
            String emitted = allocateUnique(preferredBase);
            allocated.add(new RoutineParameter(parameter.javaName(), emitted, parameter.type()));
            routineParameterDeclarationNames.add(parameter.javaName());
            routineParameterDeclarationNames.add(parameter.sqlName());
            emittedNamesBySourceName.put(parameter.javaName(), emitted);
            emittedNamesBySourceName.put(parameter.sqlName(), emitted);
            recordVariableType(parameter.javaName(), parameter.type());
            recordVariableType(parameter.sqlName(), parameter.type());
        }
        return List.copyOf(allocated);
    }

    private void collectBlock(Block block) {
        if (block == null) {
            return;
        }
        for (DeclarationNode declaration : block.declarations()) {
            collectDeclaration(declaration);
        }
        for (DeclarationNode declaration : block.exceptionHandlers()) {
            collectDeclaration(declaration);
        }
        for (StatementNode statement : block.statements()) {
            collectStatement(statement);
        }
    }

    private void collectDeclaration(DeclarationNode declaration) {
        if (declaration instanceof DeclareVariable variable) {
            if (!isRoutineParameterDeclaration(variable.name())) {
                allocateLocalAlias(variable.name());
            }
            // Record the local variable's type too (B-10) so a boolean local reaching a text
            // context is coerced; parameter types are already recorded during allocation.
            recordVariableType(variable.name(), variable.type());
            return;
        }
        if (declaration instanceof DeclareCursor cursor) {
            allocateLocalAlias(cursor.name());
        }
    }

    private void recordVariableType(String sourceName, TirType type) {
        if (sourceName == null || sourceName.isBlank() || type == null) {
            return;
        }
        variableTypesBySourceName.putIfAbsent(sourceName, type);
    }

    private void collectStatement(StatementNode statement) {
        if (statement instanceof Block block) {
            collectBlock(block);
            return;
        }
        if (statement instanceof IfStatement ifStatement) {
            collectBlock(ifStatement.thenBlock());
            for (ElseIfClause elseIfClause : ifStatement.elseIfClauses()) {
                collectBlock(elseIfClause.block());
            }
            collectBlock(ifStatement.elseBlock());
            return;
        }
        if (statement instanceof WhileStatement whileStatement) {
            collectBlock(whileStatement.body());
            return;
        }
        if (statement instanceof LoopStatement loopStatement) {
            collectBlock(loopStatement.body());
            return;
        }
        if (statement instanceof ForCursorStatement forCursorStatement) {
            collectBlock(forCursorStatement.body());
            return;
        }
        if (statement instanceof RawReadIntoStatement rawReadIntoStatement) {
            // The INTO targets are routine locals bound from the read (JDBC I-4 unparsed); allocate an
            // alias for each so they collide-check against every other emitted name.
            for (String variableName : rawReadIntoStatement.variableNames()) {
                allocateLocalAlias(variableName);
            }
            return;
        }
        if (statement instanceof RawCursorStatement rawCursorStatement) {
            // The FETCH targets are per-row routine locals (JDBC I-5 unparsed); allocate each, then
            // recurse the loop body exactly like ForCursorStatement.
            for (String variableName : rawCursorStatement.variableNames()) {
                allocateLocalAlias(variableName);
            }
            collectBlock(rawCursorStatement.body());
            return;
        }
        if (statement instanceof GeneratedKeyReadStatement generatedKeyReadStatement) {
            // The key local is a routine local bound from the inserted row's auto-increment key
            // (JDBC I-7 §6.3); allocate an alias so it collide-checks against every other emitted name.
            allocateLocalAlias(generatedKeyReadStatement.keyLocal());
            return;
        }
        if (statement instanceof ForEachStatement forEachStatement) {
            collectBlock(forEachStatement.body());
            return;
        }
        if (statement instanceof ForRangeStatement forRangeStatement) {
            collectBlock(forRangeStatement.body());
            return;
        }
        if (statement instanceof TryCatchFinallyStatement tryCatchFinallyStatement) {
            collectBlock(tryCatchFinallyStatement.tryBlock());
            if (tryCatchFinallyStatement.catches() != null) {
                for (CatchClause catchClause : tryCatchFinallyStatement.catches()) {
                    allocateLocalAlias(catchClause.exceptionVariable());
                    collectBlock(catchClause.body());
                }
            }
            collectBlock(tryCatchFinallyStatement.finallyBlock());
        }
    }

    private void allocateLocalAlias(String sourceName) {
        if (sourceName == null || sourceName.isBlank() || emittedNamesBySourceName.containsKey(sourceName)) {
            return;
        }
        emittedNamesBySourceName.put(sourceName, allocateUnique(preferredLocalBase(sourceName)));
    }

    private String preferredLocalBase(String sourceName) {
        if (isExplicitSqlLocalName(sourceName)) {
            return sourceName;
        }
        return namingConventionEngine.localVariableName(sourceName);
    }

    private boolean isExplicitSqlLocalName(String sourceName) {
        return isPlainSqlIdentifier(sourceName)
                && (sourceName.startsWith("v_")
                        || sourceName.startsWith("__titan_"));
    }

    private boolean isPlainSqlIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        char first = value.charAt(0);
        if (!(first == '_' || (first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!(current == '_' || (current >= 'A' && current <= 'Z') || (current >= 'a' && current <= 'z') || (current >= '0' && current <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private String allocateUnique(String preferredBase) {
        String base = sanitizeBase(preferredBase);
        String candidate = base;
        int suffix = 2;
        while (reservedFoldedNames.contains(folded(candidate))) {
            candidate = base + "_" + suffix++;
        }
        reservedFoldedNames.add(folded(candidate));
        return candidate;
    }

    private String sanitizeBase(String value) {
        if (value == null || value.isBlank()) {
            return namingConventionEngine.localVariableName("value");
        }
        if (isPlainSqlIdentifier(value)) {
            return value;
        }
        return namingConventionEngine.toSqlIdentifier(value);
    }

    private void reserveAll(List<String> names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            reservedFoldedNames.add(folded(name));
        }
    }

    private String folded(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
