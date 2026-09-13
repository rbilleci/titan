package io.titan.transpiler.tir;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.UnionTypeTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import io.titan.transpiler.ExceptionSqlStateRegistry;
import io.titan.transpiler.LoweringContext;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.ArrayType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Statement lowering for the Java-to-TIR pass: blocks, control flow (if/switch/loops),
 * try/catch, declarations, assignments, return/throw, and statement-level dispatch into
 * {@link ExpressionLowerer} and {@link DslQueryLowerer}.
 */
final class StatementLowerer {

    private StatementLowerer() {
    }

    static Block lowerBlock(TreePath blockPath, ParsedSources parsedSources) {
        BlockTree blockTree = (BlockTree) blockPath.getLeaf();
        List<DeclarationNode> declarations = new ArrayList<>();
        List<StatementNode> statements = new ArrayList<>();

        for (StatementTree statement : blockTree.getStatements()) {
            TreePath statementPath = new TreePath(blockPath, statement);
            if (statement.getKind() == Tree.Kind.EMPTY_STATEMENT) {
                // A lone ';' is legal Java with no effect; it lowers to nothing (§10.1 audit:
                // previously fell into the default-deny branch and failed lowering).
                continue;
            }
            if (statement.getKind() == Tree.Kind.VARIABLE) {
                lowerVariableDeclarationStatement((VariableTree) statement, statementPath, declarations, statements, parsedSources);
            } else {
                statements.add(lowerStatement(statement, statementPath, parsedSources));
            }
        }

        return new Block(List.copyOf(declarations), List.copyOf(statements), List.of());
    }

    /**
     * Lowers a local variable declaration, splitting out the Phase A4 / G2 read-into-local case:
     * when the initializer is a typed DSL read-into-local terminal ({@code fetchScalar()} /
     * {@code fetchExistsValue()}), the declaration becomes a bare DECLARE plus a
     * {@link SelectIntoStatement} that binds the query result to the local (SELECT ... INTO),
     * rather than the usual DECLARE + Assign(initializer). The declared local is then a usable
     * {@link VariableRefExpression} in later control flow, so {@code if (local) ...} lowers via the
     * ordinary if/return path.
     */
    private static void lowerVariableDeclarationStatement(
            VariableTree variableTree,
            TreePath variablePath,
            List<DeclarationNode> declarations,
            List<StatementNode> statements,
            ParsedSources parsedSources
    ) {
        if (variableTree.getInitializer() instanceof MethodInvocationTree initializerInvocation
                && DslQueryLowerer.isDslReadIntoLocalTerminal(initializerInvocation)) {
            TypeMirror resolvedType = parsedSources.trees().getTypeMirror(variablePath);
            TirType type = LowererSupport.mapType(resolvedType, "variable '" + variableTree.getName() + "'");
            String name = variableTree.getName().toString();
            declarations.add(new DeclareVariable(name, type, isNullable(resolvedType), null));
            TreePath initializerPath = new TreePath(variablePath, initializerInvocation);
            SelectSql query = DslQueryLowerer.lowerDslReadIntoLocal(initializerInvocation, initializerPath, parsedSources);
            statements.add(new SelectIntoStatement(name, query));
            return;
        }

        DeclareVariable declaration = lowerVariableDeclaration(variableTree, variablePath, parsedSources);
        if (declaration != null) {
            addVariableDeclaration(declaration, declarations, statements);
        }
    }

    private static void addVariableDeclaration(
            DeclareVariable declaration,
            List<DeclarationNode> declarations,
            List<StatementNode> statements
    ) {
        declarations.add(declaration);
        if (declaration.initializer() != null) {
            statements.add(new Assign(
                    new VariableRefExpression(declaration.name()),
                    declaration.initializer()));
        }
    }

    private static DeclareVariable lowerVariableDeclaration(
            VariableTree variableTree,
            TreePath variablePath,
            ParsedSources parsedSources
    ) {
        if (DslQueryLowerer.isDslScaffoldingDeclaration(variableTree, parsedSources)) {
            return null;
        }
        if (isConcreteInstanceReceiverTokenDeclaration(variableTree, variablePath, parsedSources)) {
            return null;
        }
        TypeMirror resolvedType = parsedSources.trees().getTypeMirror(variablePath);
        // Generic DSL table aliases (Table<...>/TableLike<...>) are query-construction
        // scaffolding, not SQL data variables: skip the declaration so DSL chain lowering
        // can report its targeted alias diagnostic instead of failing the Java-type mapping.
        if (resolvedType instanceof DeclaredType aliasDeclaredType
                && aliasDeclaredType.asElement() instanceof TypeElement aliasTypeElement
                && DslQueryLowerer.isGenericDslTableAlias(aliasTypeElement)) {
            return null;
        }
        TirType type = LowererSupport.mapType(resolvedType, "variable '" + variableTree.getName() + "'");
        ExpressionNode initializer = variableTree.getInitializer() == null
                ? null
                : ExpressionLowerer.lowerExpression(variableTree.getInitializer(), parsedSources);
        return new DeclareVariable(variableTree.getName().toString(), type, isNullable(resolvedType), initializer);
    }

    private static boolean isConcreteInstanceReceiverTokenDeclaration(
            VariableTree variableTree,
            TreePath variablePath,
            ParsedSources parsedSources
    ) {
        if (variableTree.getInitializer() == null) {
            return false;
        }
        TypeMirror variableType = parsedSources.trees().getTypeMirror(variablePath);
        if (!(parsedSources.types().asElement(variableType) instanceof TypeElement owner)
                || owner.getKind() != ElementKind.CLASS
                || !owner.getModifiers().contains(Modifier.FINAL)
                || !isSourceLocalType(owner, parsedSources)) {
            return false;
        }
        return isCompilerKnownReceiverToken(variableTree.getInitializer(), owner, parsedSources);
    }

    private static boolean isCompilerKnownReceiverToken(
            ExpressionTree expression,
            TypeElement owner,
            ParsedSources parsedSources
    ) {
        TreePath path = LowererSupport.resolveTreePath(parsedSources, expression);
        Element element = path == null ? null : parsedSources.trees().getElement(path);
        if (!(element instanceof VariableElement variable)
                || !sameErasedType(variable.asType(), owner.asType(), parsedSources)) {
            return false;
        }
        if (variable.getKind() == ElementKind.FIELD) {
            return variable.getModifiers().contains(Modifier.STATIC)
                    && variable.getModifiers().contains(Modifier.FINAL);
        }
        if (variable.getKind() == ElementKind.LOCAL_VARIABLE) {
            VariableTree declaration = sourceLocalVariableTree(variable, parsedSources);
            return declaration != null
                    && declaration.getInitializer() != null
                    && isCompilerKnownReceiverToken(declaration.getInitializer(), owner, parsedSources);
        }
        return false;
    }

    private static VariableTree sourceLocalVariableTree(VariableElement target, ParsedSources parsedSources) {
        return LoweringContext.forSources(parsedSources).sourceVariableTree(target);
    }

    private static boolean sameErasedType(TypeMirror left, TypeMirror right, ParsedSources parsedSources) {
        if (left == null || right == null) {
            return false;
        }
        return parsedSources.types().isSameType(
                parsedSources.types().erasure(left),
                parsedSources.types().erasure(right));
    }

    private static boolean isSourceLocalType(TypeElement target, ParsedSources parsedSources) {
        return LoweringContext.forSources(parsedSources).isSourceLocal(target);
    }

    static StatementNode lowerStatement(StatementTree statement, TreePath statementPath, ParsedSources parsedSources) {
        return switch (statement.getKind()) {
            case BLOCK -> lowerBlock(statementPath, parsedSources);
            case IF -> lowerIf((IfTree) statement, statementPath, parsedSources);
            case SWITCH -> lowerSwitch((SwitchTree) statement, statementPath, parsedSources);
            case WHILE_LOOP -> lowerWhile((WhileLoopTree) statement, statementPath, parsedSources);
            case DO_WHILE_LOOP -> lowerDoWhile((DoWhileLoopTree) statement, statementPath, parsedSources);
            case FOR_LOOP -> lowerForLoop((ForLoopTree) statement, statementPath, parsedSources);
            case ENHANCED_FOR_LOOP -> lowerEnhancedForLoop((EnhancedForLoopTree) statement, statementPath, parsedSources);
            case BREAK -> lowerBreak((BreakTree) statement, parsedSources);
            case CONTINUE -> lowerContinue((ContinueTree) statement, parsedSources);
            case LABELED_STATEMENT -> throw LowererSupport.unsupportedFeature(
                    "labeled statement",
                    statement,
                    parsedSources,
                    "Use unlabeled loops; labeled break/continue targets are not supported");
            case RETURN -> lowerReturn((ReturnTree) statement, parsedSources);
            case THROW -> lowerThrow((ThrowTree) statement, statementPath, parsedSources);
            case TRY -> lowerTry((TryTree) statement, statementPath, parsedSources);
            case EXPRESSION_STATEMENT -> lowerExpressionStatement((ExpressionStatementTree) statement, statementPath, parsedSources);
            // An empty statement as a control-structure body (e.g. 'if (x) ;', 'while (x) ;')
            // is equivalent to an empty braces block: lower to an empty no-op Block, matching
            // what '{}' produces. Empty statements inside blocks are skipped in lowerBlock.
            case EMPTY_STATEMENT -> new Block(List.of(), List.of(), List.of());
            default -> throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported P0 statement during lowering: " + statement.getKind(),
                    statement,
                    parsedSources,
                    null);
        };
    }

    private static IfStatement lowerIf(IfTree ifTree, TreePath ifPath, ParsedSources parsedSources) {
        ExpressionNode condition = ExpressionLowerer.lowerExpression(ifTree.getCondition(), parsedSources);
        Block thenBlock = lowerAsBlock(new TreePath(ifPath, ifTree.getThenStatement()), parsedSources);

        List<ElseIfClause> elseIfClauses = new ArrayList<>();
        Block elseBlock = null;

        StatementTree elseStatement = ifTree.getElseStatement();
        TreePath elsePath = elseStatement == null ? null : new TreePath(ifPath, elseStatement);
        while (elseStatement instanceof IfTree nestedIf && elsePath != null) {
            elseIfClauses.add(new ElseIfClause(
                    ExpressionLowerer.lowerExpression(nestedIf.getCondition(), parsedSources),
                    lowerAsBlock(new TreePath(elsePath, nestedIf.getThenStatement()), parsedSources)
            ));
            elseStatement = nestedIf.getElseStatement();
            elsePath = elseStatement == null ? null : new TreePath(elsePath, elseStatement);
        }

        if (elseStatement != null && elsePath != null) {
            elseBlock = lowerAsBlock(elsePath, parsedSources);
        }

        return new IfStatement(condition, thenBlock, List.copyOf(elseIfClauses), elseBlock);
    }

    private static Block lowerSwitch(SwitchTree switchTree, TreePath switchPath, ParsedSources parsedSources) {
        TypeMirror selectorType = parsedSources.trees().getTypeMirror(new TreePath(switchPath, switchTree.getExpression()));
        long switchStartPosition = parsedSources.trees().getSourcePositions()
                .getStartPosition(switchPath.getCompilationUnit(), switchTree);
        String switchValueName = "__titan_switch_value_" + Math.max(switchStartPosition, 0);
        DeclareVariable switchValue = new DeclareVariable(
                switchValueName,
                LowererSupport.mapType(selectorType, "switch selector '" + switchTree.getExpression() + "'"),
                isNullable(selectorType),
                ExpressionLowerer.lowerExpression(switchTree.getExpression(), parsedSources)
        );

        List<? extends CaseTree> cases = switchTree.getCases();
        IfStatement chain = null;
        List<ElseIfClause> elseIfClauses = new ArrayList<>();
        Block defaultBlock = null;

        for (CaseTree caseTree : cases) {
            if (caseTree.getCaseKind() != CaseTree.CaseKind.RULE) {
                throw LowererSupport.unsupportedFeature(
                        "switch statement only supports arrow-style rule cases",
                        caseTree,
                        parsedSources,
                        "Rewrite the switch using arrow-style (case X ->) rules");
            }

            TreePath casePath = new TreePath(switchPath, caseTree);
            Block caseBlock = lowerSwitchRuleCaseBody(caseTree, casePath, parsedSources);
            boolean isDefault = caseTree.getLabels().stream()
                    .anyMatch(label -> label.getKind() == Tree.Kind.DEFAULT_CASE_LABEL);

            if (isDefault) {
                defaultBlock = caseBlock;
                continue;
            }

            ExpressionNode condition = lowerSwitchCaseCondition(caseTree.getLabels(), switchValueName, parsedSources);
            if (chain == null) {
                chain = new IfStatement(condition, caseBlock, List.of(), null);
            } else {
                elseIfClauses.add(new ElseIfClause(condition, caseBlock));
            }
        }

        if (chain == null) {
            return new Block(List.of(switchValue), defaultBlock == null ? List.of() : defaultBlock.statements(), List.of());
        }

        IfStatement lowered = new IfStatement(chain.condition(), chain.thenBlock(), List.copyOf(elseIfClauses), defaultBlock);
        return new Block(List.of(switchValue), List.of(lowered), List.of());
    }

    private static ExpressionNode lowerSwitchCaseCondition(List<? extends CaseLabelTree> labels, String switchValueName, ParsedSources parsedSources) {
        ExpressionNode combined = null;
        for (CaseLabelTree label : labels) {
            if (label.getKind() == Tree.Kind.DEFAULT_CASE_LABEL) {
                continue;
            }
            ExpressionNode labelExpression;
            if (label instanceof ExpressionTree expressionLabel) {
                labelExpression = ExpressionLowerer.lowerExpression(expressionLabel, parsedSources);
            } else if (label instanceof ConstantCaseLabelTree constantLabel) {
                labelExpression = ExpressionLowerer.lowerExpression(constantLabel.getConstantExpression(), parsedSources);
            } else {
                throw LowererSupport.unsupportedFeature("switch statement label must be a constant expression", label, parsedSources);
            }
            ExpressionNode equals = new BinaryOpExpression(
                    new VariableRefExpression(switchValueName),
                    BinaryOperator.EQUAL,
                    labelExpression
            );
            combined = combined == null
                    ? equals
                    : new BinaryOpExpression(combined, BinaryOperator.OR, equals);
        }
        if (combined == null) {
            throw LowererSupport.unsupportedFeature(
                    "switch statement case has no labels",
                    labels.isEmpty() ? null : labels.getFirst(),
                    parsedSources);
        }
        return combined;
    }

    private static Block lowerSwitchRuleCaseBody(CaseTree caseTree, TreePath casePath, ParsedSources parsedSources) {
        Tree body = caseTree.getBody();
        if (body == null) {
            return new Block(List.of(), List.of(), List.of());
        }
        if (body instanceof BlockTree blockTree) {
            return lowerBlock(new TreePath(casePath, blockTree), parsedSources);
        }
        if (body instanceof StatementTree statementTree) {
            return lowerAsBlock(new TreePath(casePath, statementTree), parsedSources);
        }
        if (body instanceof ExpressionTree expressionTree) {
            StatementNode loweredExpressionBody = lowerSwitchRuleExpressionBody(expressionTree, casePath, parsedSources);
            return new Block(List.of(), List.of(loweredExpressionBody), List.of());
        }
        throw LowererSupport.unsupportedFeature("switch rule body must be a block, statement, or statement-expression", caseTree, parsedSources);
    }

    private static StatementNode lowerSwitchRuleExpressionBody(
            ExpressionTree expressionTree,
            TreePath casePath,
            ParsedSources parsedSources
    ) {
        return switch (expressionTree.getKind()) {
            case ASSIGNMENT -> {
                AssignmentTree assignment = (AssignmentTree) expressionTree;
                yield new Assign(
                        ExpressionLowerer.lowerExpression(assignment.getVariable(), parsedSources),
                        ExpressionLowerer.lowerExpression(assignment.getExpression(), parsedSources)
                );
            }
            case PLUS_ASSIGNMENT, MINUS_ASSIGNMENT, MULTIPLY_ASSIGNMENT, DIVIDE_ASSIGNMENT, REMAINDER_ASSIGNMENT -> {
                CompoundAssignmentTree compoundAssignment = (CompoundAssignmentTree) expressionTree;
                ExpressionNode target = ExpressionLowerer.lowerExpression(compoundAssignment.getVariable(), parsedSources);
                BinaryOperator operator = switch (compoundAssignment.getKind()) {
                    case PLUS_ASSIGNMENT -> BinaryOperator.ADD;
                    case MINUS_ASSIGNMENT -> BinaryOperator.SUBTRACT;
                    case MULTIPLY_ASSIGNMENT -> BinaryOperator.MULTIPLY;
                    case DIVIDE_ASSIGNMENT -> BinaryOperator.DIVIDE;
                    case REMAINDER_ASSIGNMENT -> BinaryOperator.MODULO;
                    default -> throw LowererSupport.loweringError(
                            TitanErrorCode.E001,
                            "Unsupported compound assignment in switch rule expression body: " + compoundAssignment.getKind(),
                            compoundAssignment,
                            parsedSources,
                            null);
                };
                ExpressionNode value = ExpressionLowerer.lowerExpression(compoundAssignment.getExpression(), parsedSources);
                yield new Assign(target, lowerCompoundAssignmentValue(compoundAssignment, target, operator, value, parsedSources));
            }
            case POSTFIX_INCREMENT, PREFIX_INCREMENT, POSTFIX_DECREMENT, PREFIX_DECREMENT ->
                    lowerUnaryExpressionStatement((UnaryTree) expressionTree, parsedSources);
            case METHOD_INVOCATION ->
                    lowerMethodInvocationStatement((MethodInvocationTree) expressionTree, new TreePath(casePath, expressionTree), parsedSources);
            default -> throw LowererSupport.unsupportedFeature("switch rule expression body must be a statement expression", expressionTree, parsedSources);
        };
    }

    private static WhileStatement lowerWhile(WhileLoopTree whileTree, TreePath whilePath, ParsedSources parsedSources) {
        return new WhileStatement(
                ExpressionLowerer.lowerExpression(whileTree.getCondition(), parsedSources),
                lowerAsBlock(new TreePath(whilePath, whileTree.getStatement()), parsedSources),
                null
        );
    }

    /**
     * Lowers {@code do { body } while (cond)} into a plain loop whose body starts with the
     * condition check (audit D12):
     *
     * <pre>
     *   first := true;
     *   LOOP
     *     IF first THEN first := false;
     *     ELSE cond_temp := cond; IF cond_temp IS NULL OR cond_temp = false THEN BREAK; END IF;
     *     END IF;
     *     ...original body...
     *   END LOOP;
     * </pre>
     *
     * <p>The lowered condition is bound to one generated temp per iteration (instead of being
     * duplicated into both the IS NULL and = false checks) and is evaluated at the TOP of the
     * loop body: an unlabeled {@code continue} jumps to the top of plain LOOP bodies on both
     * dialects, so this placement makes continue re-test the condition exactly like Java's
     * do-while. Temp names follow the switch-temp scheme (deterministic source-position suffix).</p>
     */
    private static Block lowerDoWhile(DoWhileLoopTree doWhileTree, TreePath doWhilePath, ParsedSources parsedSources) {
        long startPosition = parsedSources.trees().getSourcePositions()
                .getStartPosition(doWhilePath.getCompilationUnit(), doWhileTree);
        String suffix = String.valueOf(Math.max(startPosition, 0));
        String conditionName = "__titan_dowhile_cond_" + suffix;
        String firstIterationName = "__titan_dowhile_first_" + suffix;

        ExpressionNode continueCondition = ExpressionLowerer.lowerExpression(doWhileTree.getCondition(), parsedSources);
        ExpressionNode exitWhenFalseOrNull = new BinaryOpExpression(
                new IsNullExpression(new VariableRefExpression(conditionName)),
                BinaryOperator.OR,
                new BinaryOpExpression(
                        new VariableRefExpression(conditionName),
                        BinaryOperator.EQUAL,
                        new LiteralExpression(false, new TBooleanType())
                )
        );

        IfStatement iterationGate = new IfStatement(
                new VariableRefExpression(firstIterationName),
                new Block(
                        List.of(),
                        List.of(new Assign(
                                new VariableRefExpression(firstIterationName),
                                new LiteralExpression(false, new TBooleanType()))),
                        List.of()),
                List.of(),
                new Block(
                        List.of(),
                        List.of(
                                new Assign(new VariableRefExpression(conditionName), continueCondition),
                                new IfStatement(
                                        exitWhenFalseOrNull,
                                        new Block(List.of(), List.of(new BreakStatement(null)), List.of()),
                                        List.of(),
                                        null)),
                        List.of()));

        Block originalBody = lowerAsBlock(new TreePath(doWhilePath, doWhileTree.getStatement()), parsedSources);
        List<StatementNode> loopStatements = new ArrayList<>();
        loopStatements.add(iterationGate);
        loopStatements.addAll(originalBody.statements());
        Block loopBody = new Block(originalBody.declarations(), List.copyOf(loopStatements), originalBody.exceptionHandlers());

        return new Block(
                List.of(
                        new DeclareVariable(conditionName, new TBooleanType(), true, null),
                        new DeclareVariable(firstIterationName, new TBooleanType(), false,
                                new LiteralExpression(true, new TBooleanType()))),
                List.of(
                        new Assign(
                                new VariableRefExpression(firstIterationName),
                                new LiteralExpression(true, new TBooleanType())),
                        new LoopStatement(loopBody, null, null)),
                List.of());
    }

    private static BreakStatement lowerBreak(BreakTree breakTree, ParsedSources parsedSources) {
        if (breakTree.getLabel() != null) {
            throw LowererSupport.unsupportedFeature(
                    "labeled break statement",
                    breakTree,
                    parsedSources,
                    "Use an unlabeled break (which exits the innermost loop) or restructure the outer loop with a boolean flag");
        }
        return new BreakStatement(null);
    }

    private static ContinueStatement lowerContinue(ContinueTree continueTree, ParsedSources parsedSources) {
        if (continueTree.getLabel() != null) {
            throw LowererSupport.unsupportedFeature(
                    "labeled continue statement",
                    continueTree,
                    parsedSources,
                    "Use an unlabeled continue (which targets the innermost loop) or restructure the outer loop with a boolean flag");
        }
        return new ContinueStatement(null);
    }

    private static Block lowerForLoop(ForLoopTree forLoopTree, TreePath forPath, ParsedSources parsedSources) {
        Block countingLoop = tryLowerCountingForLoop(forLoopTree, forPath, parsedSources);
        if (countingLoop != null) {
            return countingLoop;
        }

        List<DeclarationNode> declarations = new ArrayList<>();
        List<StatementNode> statements = new ArrayList<>();

        for (StatementTree initializer : forLoopTree.getInitializer()) {
            TreePath initializerPath = new TreePath(forPath, initializer);
            if (initializer.getKind() == Tree.Kind.VARIABLE) {
                lowerVariableDeclarationStatement((VariableTree) initializer, initializerPath, declarations, statements, parsedSources);
            } else if (initializer.getKind() == Tree.Kind.EXPRESSION_STATEMENT) {
                statements.add(lowerExpressionStatement((ExpressionStatementTree) initializer, initializerPath, parsedSources));
            }
        }

        ExpressionNode condition = forLoopTree.getCondition() == null
                ? new LiteralExpression(true, new TBooleanType())
                : ExpressionLowerer.lowerExpression(forLoopTree.getCondition(), parsedSources);

        Block body = lowerAsBlock(new TreePath(forPath, forLoopTree.getStatement()), parsedSources);
        List<StatementNode> whileBodyStatements = new ArrayList<>(body.statements());
        for (ExpressionStatementTree update : forLoopTree.getUpdate()) {
            whileBodyStatements.add(lowerExpressionStatement(update, new TreePath(forPath, update), parsedSources));
        }

        Block whileBody = new Block(body.declarations(), List.copyOf(whileBodyStatements), List.of());
        statements.add(new WhileStatement(condition, whileBody, null));

        return new Block(List.copyOf(declarations), List.copyOf(statements), List.of());
    }

    /**
     * Recognizes the classic counting loop {@code for (int i = A; i < B; i++)} (also {@code <=}
     * and {@code i += 1}) and lowers it to {@link ForRangeStatement} with an inclusive end bound,
     * unlocking native {@code FOR i IN a..b LOOP} emission on PostgreSQL. The recognition is
     * deliberately conservative — anything that does not match keeps the while-desugar:
     *
     * <ul>
     *   <li>the loop variable is a freshly declared {@code int} and is never reassigned in the
     *       body (PL/pgSQL FOR variables are read-only inside the loop);</li>
     *   <li>the bound is built only from literals, simple identifiers, and arithmetic over them
     *       (no calls/field reads), and none of those identifiers — nor the loop variable — is
     *       written in the body. Java re-evaluates the condition every iteration while
     *       PL/pgSQL FOR evaluates the range once on entry, so the bound must be loop-invariant
     *       for the two semantics to coincide.</li>
     * </ul>
     *
     * <p>The loop variable's declaration is kept on the wrapping block: the MySQL emitter's
     * range desugar needs a DECLAREd variable, and on PostgreSQL the implicit FOR variable
     * simply shadows it.</p>
     */
    private static Block tryLowerCountingForLoop(ForLoopTree forLoopTree, TreePath forPath, ParsedSources parsedSources) {
        if (forLoopTree.getInitializer().size() != 1
                || !(forLoopTree.getInitializer().getFirst() instanceof VariableTree loopVariable)
                || loopVariable.getInitializer() == null
                || forLoopTree.getCondition() == null
                || forLoopTree.getUpdate().size() != 1) {
            return null;
        }
        TreePath loopVariablePath = new TreePath(forPath, loopVariable);
        TypeMirror loopVariableType = parsedSources.trees().getTypeMirror(loopVariablePath);
        if (loopVariableType == null || loopVariableType.getKind() != TypeKind.INT) {
            return null;
        }
        String loopVariableName = loopVariable.getName().toString();

        ExpressionTree condition = LowererSupport.unwrapParenthesized(forLoopTree.getCondition());
        if (!(condition instanceof BinaryTree comparison)
                || (comparison.getKind() != Tree.Kind.LESS_THAN && comparison.getKind() != Tree.Kind.LESS_THAN_EQUAL)
                || !isIdentifierNamed(comparison.getLeftOperand(), loopVariableName)) {
            return null;
        }
        ExpressionTree bound = comparison.getRightOperand();
        Set<String> boundIdentifiers = new HashSet<>();
        if (!isLoopInvariantBoundExpression(bound, boundIdentifiers) || boundIdentifiers.contains(loopVariableName)) {
            return null;
        }

        if (!isUnitIncrementOf(forLoopTree.getUpdate().getFirst().getExpression(), loopVariableName)) {
            return null;
        }

        Set<String> writtenInBody = assignedVariableNames(forLoopTree.getStatement());
        if (writtenInBody.contains(loopVariableName)) {
            return null;
        }
        for (String boundIdentifier : boundIdentifiers) {
            if (writtenInBody.contains(boundIdentifier)) {
                return null;
            }
        }

        ExpressionNode start = ExpressionLowerer.lowerExpression(loopVariable.getInitializer(), parsedSources);
        ExpressionNode boundExpression = ExpressionLowerer.lowerExpression(bound, parsedSources);
        ExpressionNode endInclusive = comparison.getKind() == Tree.Kind.LESS_THAN_EQUAL
                ? boundExpression
                : exclusiveBoundMinusOne(boundExpression);

        Block body = lowerAsBlock(new TreePath(forPath, forLoopTree.getStatement()), parsedSources);
        DeclareVariable declaration = new DeclareVariable(loopVariableName, new TIntType(), false, null);
        return new Block(
                List.of(declaration),
                List.of(new ForRangeStatement(loopVariableName, start, endInclusive, body, null)),
                List.of());
    }

    private static ExpressionNode exclusiveBoundMinusOne(ExpressionNode boundExpression) {
        if (boundExpression instanceof LiteralExpression literal && literal.value() instanceof Integer literalBound) {
            return new LiteralExpression(literalBound - 1, new TIntType());
        }
        return new BinaryOpExpression(boundExpression, BinaryOperator.SUBTRACT, new LiteralExpression(1, new TIntType()));
    }

    private static boolean isIdentifierNamed(ExpressionTree expression, String name) {
        return LowererSupport.unwrapParenthesized(expression) instanceof IdentifierTree identifier
                && identifier.getName().contentEquals(name);
    }

    /** Accepts {@code i++}, {@code ++i}, and {@code i += 1} updates of the loop variable. */
    private static boolean isUnitIncrementOf(ExpressionTree update, String loopVariableName) {
        ExpressionTree unwrapped = LowererSupport.unwrapParenthesized(update);
        if (unwrapped instanceof UnaryTree unary
                && (unary.getKind() == Tree.Kind.POSTFIX_INCREMENT || unary.getKind() == Tree.Kind.PREFIX_INCREMENT)) {
            return isIdentifierNamed(unary.getExpression(), loopVariableName);
        }
        if (unwrapped instanceof CompoundAssignmentTree compound
                && compound.getKind() == Tree.Kind.PLUS_ASSIGNMENT
                && isIdentifierNamed(compound.getVariable(), loopVariableName)) {
            ExpressionTree amount = LowererSupport.unwrapParenthesized(compound.getExpression());
            return amount.getKind() == Tree.Kind.INT_LITERAL
                    && ((com.sun.source.tree.LiteralTree) amount).getValue() instanceof Integer one
                    && one == 1;
        }
        return false;
    }

    /**
     * Whether the bound expression is built only from literals, simple identifiers, and
     * arithmetic over them; collects the identifiers it reads into {@code identifiers}.
     */
    private static boolean isLoopInvariantBoundExpression(ExpressionTree expression, Set<String> identifiers) {
        ExpressionTree unwrapped = LowererSupport.unwrapParenthesized(expression);
        return switch (unwrapped.getKind()) {
            case INT_LITERAL -> true;
            case IDENTIFIER -> {
                identifiers.add(((IdentifierTree) unwrapped).getName().toString());
                yield true;
            }
            case UNARY_MINUS, UNARY_PLUS ->
                    isLoopInvariantBoundExpression(((UnaryTree) unwrapped).getExpression(), identifiers);
            case PLUS, MINUS, MULTIPLY, DIVIDE, REMAINDER -> {
                BinaryTree binary = (BinaryTree) unwrapped;
                yield isLoopInvariantBoundExpression(binary.getLeftOperand(), identifiers)
                        && isLoopInvariantBoundExpression(binary.getRightOperand(), identifiers);
            }
            default -> false;
        };
    }

    /** Names of local variables written (assigned, compound-assigned, or incremented/decremented) in the subtree. */
    private static Set<String> assignedVariableNames(StatementTree body) {
        Set<String> written = new HashSet<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitAssignment(AssignmentTree node, Void unused) {
                record(node.getVariable());
                return super.visitAssignment(node, unused);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                record(node.getVariable());
                return super.visitCompoundAssignment(node, unused);
            }

            @Override
            public Void visitUnary(UnaryTree node, Void unused) {
                switch (node.getKind()) {
                    case POSTFIX_INCREMENT, PREFIX_INCREMENT, POSTFIX_DECREMENT, PREFIX_DECREMENT ->
                            record(node.getExpression());
                    default -> {
                    }
                }
                return super.visitUnary(node, unused);
            }

            private void record(ExpressionTree target) {
                if (LowererSupport.unwrapParenthesized(target) instanceof IdentifierTree identifier) {
                    written.add(identifier.getName().toString());
                }
            }
        }.scan(body, null);
        return written;
    }

    private static Block lowerEnhancedForLoop(EnhancedForLoopTree enhancedForLoopTree, TreePath forPath, ParsedSources parsedSources) {
        VariableTree variableTree = enhancedForLoopTree.getVariable();
        TreePath variablePath = new TreePath(forPath, variableTree);
        TypeMirror elementType = parsedSources.trees().getTypeMirror(variablePath);
        TypeMirror iterableExpressionType = parsedSources.trees().getTypeMirror(new TreePath(forPath, enhancedForLoopTree.getExpression()));
        TypeMirror iterableElementType = resolveEnhancedForElementType(iterableExpressionType, parsedSources.types(), parsedSources.elements());
        if (iterableElementType == null) {
            throw LowererSupport.unsupportedFeature(
                    "Enhanced for-loop requires an array or Iterable expression, found: " + safeTypeName(iterableExpressionType),
                    enhancedForLoopTree,
                    parsedSources);
        }
        if (elementType != null && elementType.getKind() != TypeKind.ERROR
                && !parsedSources.types().isAssignable(iterableElementType, elementType)) {
            throw LowererSupport.unsupportedFeature(
                    "Enhanced for-loop variable type '%s' is not compatible with iterable element type '%s'"
                            .formatted(safeTypeName(elementType), safeTypeName(iterableElementType)),
                    enhancedForLoopTree,
                    parsedSources);
        }

        DeclareVariable iterationVariable = new DeclareVariable(
                variableTree.getName().toString(),
                LowererSupport.mapType(elementType, "for-each variable '" + variableTree.getName() + "'"),
                isNullable(elementType),
                null
        );

        ForEachStatement forEach = new ForEachStatement(
                variableTree.getName().toString(),
                LowererSupport.mapType(elementType, "for-each variable '" + variableTree.getName() + "'"),
                ExpressionLowerer.lowerExpression(enhancedForLoopTree.getExpression(), parsedSources),
                lowerAsBlock(new TreePath(forPath, enhancedForLoopTree.getStatement()), parsedSources),
                null
        );

        return new Block(List.of(iterationVariable), List.of(forEach), List.of());
    }

    private static TypeMirror resolveEnhancedForElementType(TypeMirror iterableType, Types types, Elements elements) {
        if (iterableType == null || iterableType.getKind() == TypeKind.ERROR) {
            return null;
        }
        if (iterableType.getKind() == TypeKind.ARRAY) {
            return ((ArrayType) iterableType).getComponentType();
        }
        if (!(iterableType instanceof DeclaredType declaredType)) {
            return null;
        }
        TypeElement iterableElement = elements.getTypeElement("java.lang.Iterable");
        if (iterableElement == null) {
            return null;
        }
        if (!types.isAssignable(types.erasure(declaredType), types.erasure(iterableElement.asType()))) {
            return null;
        }
        if (!declaredType.getTypeArguments().isEmpty()) {
            return declaredType.getTypeArguments().get(0);
        }
        TypeElement objectElement = elements.getTypeElement("java.lang.Object");
        return objectElement == null ? null : objectElement.asType();
    }

    private static String safeTypeName(TypeMirror typeMirror) {
        return typeMirror == null ? "<unknown>" : typeMirror.toString();
    }

    private static ReturnStatement lowerReturn(ReturnTree returnTree, ParsedSources parsedSources) {
        ExpressionNode expression = returnTree.getExpression() == null
                ? null
                : ExpressionLowerer.lowerExpression(returnTree.getExpression(), parsedSources);
        return new ReturnStatement(expression);
    }

    static RaiseStatement lowerThrow(ThrowTree throwTree, TreePath throwPath, ParsedSources parsedSources) {
        if (throwTree.getExpression() == null) {
            return new RaiseStatement("45000", new LiteralExpression("Java throw", new TTextType()), List.of());
        }
        TypeMirror thrownType = parsedSources.trees().getTypeMirror(new TreePath(throwPath, throwTree.getExpression()));
        // F-11 (plan 2.2): the SQLSTATE comes from the resolved TypeMirror's FQN, never from
        // source-text matching; user exception types get distinct per-run states.
        String sqlState = ExceptionSqlStateRegistry.forSources(parsedSources).sqlStateForThrow(thrownType);
        return new RaiseStatement(sqlState, lowerThrowMessage(throwTree, parsedSources), List.of());
    }

    /**
     * F-10 (plan 2.2): the raise message is a real lowered expression, not the throw's source
     * text. {@code new SomeException(<expr>)} lowers its first constructor argument (dynamic
     * messages — concatenations, variables, formatted strings — survive); a no-argument
     * constructor keeps the historical default of the throw expression's source text as a
     * literal (there is no message to carry); a rethrow like {@code throw e} lowers the
     * exception value itself, which the emitters represent as the TEXT catch variable holding
     * the original message.
     */
    private static ExpressionNode lowerThrowMessage(ThrowTree throwTree, ParsedSources parsedSources) {
        ExpressionTree thrown = LowererSupport.unwrapParenthesized(throwTree.getExpression());
        if (thrown instanceof NewClassTree constructed) {
            if (constructed.getArguments().isEmpty()) {
                return new LiteralExpression(throwTree.getExpression().toString(), new TTextType());
            }
            return ExpressionLowerer.lowerExpression(constructed.getArguments().getFirst(), parsedSources);
        }
        return ExpressionLowerer.lowerExpression(thrown, parsedSources);
    }

    private static TryCatchFinallyStatement lowerTry(TryTree tryTree, TreePath tryPath, ParsedSources parsedSources) {
        Block loweredTryBody = lowerBlock(new TreePath(tryPath, tryTree.getBlock()), parsedSources);

        List<DeclarationNode> tryDeclarations = new ArrayList<>(loweredTryBody.declarations());
        List<StatementNode> closeStatements = new ArrayList<>();

        for (Tree resource : tryTree.getResources()) {
            if (resource instanceof VariableTree resourceVariable) {
                TreePath resourcePath = new TreePath(tryPath, resourceVariable);
                String resourceName = resourceVariable.getName().toString();
                String cursorOpenFlag = "__titan_cursor_open_" + DslQueryLowerer.sanitizeIdentifier(resourceName);
                // A try-with-resources resource lowers to a named cursor (closed via
                // CloseCursorStatement below), so its declaration is typed as a TEXT cursor
                // handle instead of routing the resource's Java type through
                // JavaTypeToTirMapper: the AutoCloseable wrapper is a cursor token, not a
                // SQL data type.
                TypeMirror resourceType = parsedSources.trees().getTypeMirror(resourcePath);
                tryDeclarations.add(new DeclareVariable(
                        resourceName,
                        new TTextType(),
                        isNullable(resourceType),
                        resourceVariable.getInitializer() == null
                                ? null
                                : ExpressionLowerer.lowerExpression(resourceVariable.getInitializer(), parsedSources)
                ));
                tryDeclarations.add(new DeclareVariable(
                        cursorOpenFlag,
                        new TBooleanType(),
                        false,
                        new LiteralExpression(true, new TBooleanType())
                ));
                closeStatements.add(0, new IfStatement(
                        new VariableRefExpression(cursorOpenFlag),
                        new Block(
                                List.of(),
                                List.of(
                                        new CloseCursorStatement(resourceName),
                                        new Assign(
                                                new VariableRefExpression(cursorOpenFlag),
                                                new LiteralExpression(false, new TBooleanType())
                                        )
                                ),
                                List.of()
                        ),
                        List.of(),
                        null
                ));
            }
        }

        Block tryBlock = new Block(
                List.copyOf(tryDeclarations),
                loweredTryBody.statements(),
                loweredTryBody.exceptionHandlers()
        );

        List<CatchClause> catches = new ArrayList<>();
        for (com.sun.source.tree.CatchTree catchTree : tryTree.getCatches()) {
            String exceptionVariable = catchTree.getParameter() == null
                    ? "ex"
                    : catchTree.getParameter().getName().toString();
            String exceptionType = catchTree.getParameter() == null || catchTree.getParameter().getType() == null
                    ? "java.lang.Exception"
                    : catchTree.getParameter().getType().toString();
            List<String> sqlStates = resolveCatchSqlStates(catchTree, parsedSources);
            Block catchBody = lowerBlock(new TreePath(tryPath, catchTree.getBlock()), parsedSources);
            catches.add(new CatchClause(exceptionVariable, exceptionType, sqlStates, catchBody));
        }

        Block userFinallyBlock = tryTree.getFinallyBlock() == null
                ? null
                : lowerBlock(new TreePath(tryPath, tryTree.getFinallyBlock()), parsedSources);

        Block finallyBlock;
        if (closeStatements.isEmpty()) {
            finallyBlock = userFinallyBlock;
        } else {
            List<StatementNode> mergedFinallyStatements = new ArrayList<>(closeStatements);
            if (userFinallyBlock != null) {
                mergedFinallyStatements.addAll(userFinallyBlock.statements());
            }
            finallyBlock = new Block(List.of(), List.copyOf(mergedFinallyStatements), List.of());
        }

        return new TryCatchFinallyStatement(tryBlock, List.copyOf(catches), finallyBlock);
    }

    /**
     * F-11 (plan 2.2): the dispatch set is derived from the resolved catch parameter type —
     * JDK states for caught JDK supertypes plus the allocated state of every thrown user
     * exception assignable to the caught type. Multi-catch unions accumulate the set of each
     * alternative. Source-text matching remains only as a fallback for unresolvable types.
     */
    private static List<String> resolveCatchSqlStates(com.sun.source.tree.CatchTree catchTree, ParsedSources parsedSources) {
        if (catchTree == null || catchTree.getParameter() == null || catchTree.getParameter().getType() == null) {
            return List.of("45000");
        }

        ExceptionSqlStateRegistry registry = ExceptionSqlStateRegistry.forSources(parsedSources);
        Tree typeTree = catchTree.getParameter().getType();
        if (typeTree instanceof UnionTypeTree unionTypeTree) {
            LinkedHashSet<String> states = new LinkedHashSet<>();
            for (Tree alternativeType : unionTypeTree.getTypeAlternatives()) {
                states.addAll(registry.sqlStatesForCatch(
                        resolvedTypeOf(alternativeType, parsedSources),
                        alternativeType.toString()));
            }
            return states.isEmpty() ? List.of("45000") : List.copyOf(states);
        }

        return registry.sqlStatesForCatch(resolvedTypeOf(typeTree, parsedSources), typeTree.toString());
    }

    private static TypeMirror resolvedTypeOf(Tree typeTree, ParsedSources parsedSources) {
        TreePath typePath = LowererSupport.resolveTreePath(parsedSources, typeTree);
        return typePath == null ? null : parsedSources.trees().getTypeMirror(typePath);
    }

    private static StatementNode lowerExpressionStatement(
            ExpressionStatementTree expressionStatement,
            TreePath statementPath,
            ParsedSources parsedSources
    ) {
        ExpressionTree expression = expressionStatement.getExpression();

        if (expression instanceof AssignmentTree assignment) {
            String staticFieldKey = ExpressionLowerer.resolveStaticFieldKey(assignment.getVariable(), parsedSources);
            if (staticFieldKey != null) {
                return new CallStatement("__titan_static_set", List.of(
                        new LiteralExpression(staticFieldKey, new TTextType()),
                        ExpressionLowerer.lowerExpression(assignment.getExpression(), parsedSources)
                ));
            }
            return new Assign(
                    ExpressionLowerer.lowerExpression(assignment.getVariable(), parsedSources),
                    ExpressionLowerer.lowerExpression(assignment.getExpression(), parsedSources)
            );
        }

        if (expression instanceof CompoundAssignmentTree compoundAssignment) {
            String staticFieldKey = ExpressionLowerer.resolveStaticFieldKey(compoundAssignment.getVariable(), parsedSources);
            if (staticFieldKey != null) {
                ExpressionNode current = new FunctionCallExpression("__titan_static_get", List.of(
                        new LiteralExpression(staticFieldKey, new TTextType())
                ), null);
                BinaryOperator op = switch (compoundAssignment.getKind()) {
                    case PLUS_ASSIGNMENT -> BinaryOperator.ADD;
                    case MINUS_ASSIGNMENT -> BinaryOperator.SUBTRACT;
                    case MULTIPLY_ASSIGNMENT -> BinaryOperator.MULTIPLY;
                    case DIVIDE_ASSIGNMENT -> BinaryOperator.DIVIDE;
                    case REMAINDER_ASSIGNMENT -> BinaryOperator.MODULO;
                    default -> throw LowererSupport.loweringError(
                            TitanErrorCode.E001,
                            "Unsupported compound assignment in P0 lowering: " + compoundAssignment.getKind(),
                            compoundAssignment,
                            parsedSources,
                            null);
                };
                ExpressionNode value = ExpressionLowerer.lowerExpression(compoundAssignment.getExpression(), parsedSources);
                return new CallStatement("__titan_static_set", List.of(
                        new LiteralExpression(staticFieldKey, new TTextType()),
                        lowerCompoundAssignmentValue(compoundAssignment, current, op, value, parsedSources)
                ));
            }
            ExpressionNode target = ExpressionLowerer.lowerExpression(compoundAssignment.getVariable(), parsedSources);
            BinaryOperator op = switch (compoundAssignment.getKind()) {
                case PLUS_ASSIGNMENT -> BinaryOperator.ADD;
                case MINUS_ASSIGNMENT -> BinaryOperator.SUBTRACT;
                case MULTIPLY_ASSIGNMENT -> BinaryOperator.MULTIPLY;
                case DIVIDE_ASSIGNMENT -> BinaryOperator.DIVIDE;
                case REMAINDER_ASSIGNMENT -> BinaryOperator.MODULO;
                default -> throw LowererSupport.loweringError(
                        TitanErrorCode.E001,
                        "Unsupported compound assignment in P0 lowering: " + compoundAssignment.getKind(),
                        compoundAssignment,
                        parsedSources,
                        null);
            };
            ExpressionNode value = ExpressionLowerer.lowerExpression(compoundAssignment.getExpression(), parsedSources);
            return new Assign(target, lowerCompoundAssignmentValue(compoundAssignment, target, op, value, parsedSources));
        }

        if (expression instanceof UnaryTree unaryTree) {
            return lowerUnaryExpressionStatement(unaryTree, parsedSources);
        }

        if (expression instanceof MethodInvocationTree invocation) {
            return lowerMethodInvocationStatement(invocation, new TreePath(statementPath, invocation), parsedSources);
        }

        throw LowererSupport.loweringError(
                TitanErrorCode.E001,
                "Unsupported expression statement in P0 lowering: " + expression.getKind(),
                expression,
                parsedSources,
                null);
    }

    private static StatementNode lowerUnaryExpressionStatement(UnaryTree unaryTree, ParsedSources parsedSources) {
        ExpressionTree operand = unaryTree.getExpression();
        String staticFieldKey = ExpressionLowerer.resolveStaticFieldKey(operand, parsedSources);
        if (staticFieldKey != null) {
            ExpressionNode current = new FunctionCallExpression("__titan_static_get", List.of(
                    new LiteralExpression(staticFieldKey, new TTextType())
            ), null);
            return switch (unaryTree.getKind()) {
                case POSTFIX_INCREMENT, PREFIX_INCREMENT -> new CallStatement("__titan_static_set", List.of(
                        new LiteralExpression(staticFieldKey, new TTextType()),
                        new BinaryOpExpression(current, BinaryOperator.ADD, new LiteralExpression(1, new TIntType()))
                ));
                case POSTFIX_DECREMENT, PREFIX_DECREMENT -> new CallStatement("__titan_static_set", List.of(
                        new LiteralExpression(staticFieldKey, new TTextType()),
                        new BinaryOpExpression(current, BinaryOperator.SUBTRACT, new LiteralExpression(1, new TIntType()))
                ));
                default -> throw LowererSupport.loweringError(
                        TitanErrorCode.E001,
                        "Unsupported unary expression statement in P0 lowering: " + unaryTree.getKind(),
                        unaryTree,
                        parsedSources,
                        null);
            };
        }

        ExpressionNode target = ExpressionLowerer.lowerExpression(operand, parsedSources);

        return switch (unaryTree.getKind()) {
            case POSTFIX_INCREMENT, PREFIX_INCREMENT -> new Assign(
                    target,
                    new BinaryOpExpression(target, BinaryOperator.ADD, new LiteralExpression(1, new TIntType()))
            );
            case POSTFIX_DECREMENT, PREFIX_DECREMENT -> new Assign(
                    target,
                    new BinaryOpExpression(target, BinaryOperator.SUBTRACT, new LiteralExpression(1, new TIntType()))
            );
            default -> throw LowererSupport.loweringError(
                    TitanErrorCode.E001,
                    "Unsupported unary expression statement in P0 lowering: " + unaryTree.getKind(),
                    unaryTree,
                    parsedSources,
                    null);
        };
    }

    private static StatementNode lowerMethodInvocationStatement(
            MethodInvocationTree invocation,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        if (isSystemOutPrintln(invocation)) {
            ExpressionNode message = invocation.getArguments().isEmpty()
                    ? new LiteralExpression("", new TTextType())
                    : ExpressionLowerer.lowerExpression(invocation.getArguments().getFirst(), parsedSources);
            return new DebugPrintStatement(message);
        }

        if (isDslAbortWithError(invocation, invocationPath, parsedSources)) {
            // F-10 (plan 2.2): the abort message is a real lowered expression, not the
            // argument's source text (which rendered string literals with their quotes).
            ExpressionNode message = invocation.getArguments().isEmpty()
                    ? new LiteralExpression("Trigger aborted", new TTextType())
                    : ExpressionLowerer.lowerExpression(invocation.getArguments().getFirst(), parsedSources);
            return new RaiseStatement("45000", message, List.of());
        }

        StatementNode triggerRowMutation = tryLowerTriggerRowMutation(invocation, parsedSources);
        if (triggerRowMutation != null) {
            return triggerRowMutation;
        }

        DslQueryLowerer.rejectUnsafeDynamicSqlConcatenation(invocation, invocationPath, parsedSources);

        SqlNode loweredDslSql = DslQueryLowerer.tryLowerDslSqlInvocation(invocation, invocationPath, parsedSources);
        if (loweredDslSql != null) {
            return new ExecuteSqlStatement(loweredDslSql);
        }

        String callee = LowererSupport.resolvedMethodSignatureKey(invocationPath, parsedSources);
        if (callee == null) {
            callee = LowererSupport.invocationName(invocation);
        }
        List<ExpressionNode> args = invocation.getArguments().stream()
                .map(arg -> ExpressionLowerer.lowerExpression(arg, parsedSources))
                .toList();
        return new CallStatement(callee, args);
    }

    private static boolean isSystemOutPrintln(MethodInvocationTree invocation) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree printlnSelect)) {
            return false;
        }
        if (!"println".contentEquals(printlnSelect.getIdentifier())) {
            return false;
        }
        if (!(printlnSelect.getExpression() instanceof MemberSelectTree outSelect)) {
            return false;
        }
        return "out".contentEquals(outSelect.getIdentifier())
                && outSelect.getExpression() instanceof IdentifierTree owner
                && "System".contentEquals(owner.getName());
    }

    private static boolean isDslAbortWithError(
            MethodInvocationTree invocation,
            TreePath invocationPath,
            ParsedSources parsedSources
    ) {
        if (!"abortWithError".equals(LowererSupport.invocationName(invocation))) {
            return false;
        }
        ExecutableElement method = LowererSupport.resolvedMethodElement(invocationPath, parsedSources);
        if (method == null) {
            return false;
        }
        return "titan.dsl.DSL".contentEquals(method.getEnclosingElement().toString())
                && "abortWithError".contentEquals(method.getSimpleName());
    }

    private static StatementNode tryLowerTriggerRowMutation(
            MethodInvocationTree invocation,
            ParsedSources parsedSources
    ) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)
                || !"set".contentEquals(select.getIdentifier())
                || invocation.getArguments().size() != 2
                || !(select.getExpression() instanceof MethodInvocationTree receiverInvocation)) {
            return null;
        }

        String receiverMethod = LowererSupport.invocationName(receiverInvocation);
        if (!"newRow".equals(receiverMethod) && !"oldRow".equals(receiverMethod)) {
            return null;
        }
        if ("oldRow".equals(receiverMethod)) {
            throw LowererSupport.unsupportedFeature("oldRow().set(...) is not allowed", invocation, parsedSources);
        }

        String column = triggerRowColumnName(invocation.getArguments().get(0), parsedSources);
        ExpressionNode value = ExpressionLowerer.lowerExpression(invocation.getArguments().get(1), parsedSources);
        return new Assign(new ColumnRefExpression("NEW", column), value);
    }

    static String triggerRowColumnName(ExpressionTree argument, ParsedSources parsedSources) {
        ExpressionTree unwrapped = LowererSupport.unwrapParenthesized(argument);
        if (unwrapped instanceof MemberSelectTree memberSelectTree) {
            return memberSelectTree.getIdentifier().toString();
        }
        if (unwrapped instanceof IdentifierTree identifierTree) {
            return identifierTree.getName().toString();
        }
        throw LowererSupport.unsupportedFeature("Trigger row column reference must be a column constant", argument, parsedSources);
    }

    private static Block lowerAsBlock(TreePath statementPath, ParsedSources parsedSources) {
        StatementTree statement = (StatementTree) statementPath.getLeaf();
        if (statement.getKind() == Tree.Kind.BLOCK) {
            return lowerBlock(statementPath, parsedSources);
        }
        return new Block(List.of(), List.of(lowerStatement(statement, statementPath, parsedSources)), List.of());
    }

    private static ExpressionNode lowerCompoundAssignmentValue(
            CompoundAssignmentTree compoundAssignment,
            ExpressionNode target,
            BinaryOperator operator,
            ExpressionNode value,
            ParsedSources parsedSources
    ) {
        if (operator == BinaryOperator.ADD && ExpressionLowerer.isStringTypedExpression(compoundAssignment, parsedSources)) {
            return new FunctionCallExpression("__titan_str_concat", List.of(target, value), null);
        }
        return new BinaryOpExpression(target, operator, value);
    }

    static boolean isNullable(TypeMirror typeMirror) {
        return typeMirror == null || !typeMirror.getKind().isPrimitive();
    }
}
