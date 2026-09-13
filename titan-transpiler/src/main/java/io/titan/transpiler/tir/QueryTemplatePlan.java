package io.titan.transpiler.tir;

import java.util.List;
import java.util.Objects;

public record QueryTemplatePlan(
        TableRef root,
        RootKind rootKind,
        List<Projection> projections,
        List<ParameterPredicate> predicates,
        List<RelationEdge> relationEdges,
        List<OrderKey> orderKeys,
        CursorWindow cursorWindow,
        List<RootJoin> rootJoins,
        List<ExpressionPredicate> expressionPredicates,
        List<ExpressionOrderKey> expressionOrderKeys,
        ExpressionCursorWindow expressionCursorWindow
) {
    public QueryTemplatePlan(
            TableRef root,
            List<Projection> projections,
            List<ParameterPredicate> predicates,
            List<RelationEdge> relationEdges
    ) {
        this(root, RootKind.POINT, projections, predicates, relationEdges, List.of(), null);
    }

    public QueryTemplatePlan(
            TableRef root,
            RootKind rootKind,
            List<Projection> projections,
            List<ParameterPredicate> predicates,
            List<RelationEdge> relationEdges,
            List<OrderKey> orderKeys,
            CursorWindow cursorWindow
    ) {
        this(root, rootKind, projections, predicates, relationEdges, orderKeys, cursorWindow,
                List.of(), List.of(), List.of(), null);
    }

    public QueryTemplatePlan {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(rootKind, "rootKind");
        projections = List.copyOf(projections);
        predicates = List.copyOf(predicates);
        relationEdges = List.copyOf(relationEdges);
        orderKeys = List.copyOf(orderKeys);
        rootJoins = List.copyOf(rootJoins);
        expressionPredicates = List.copyOf(expressionPredicates);
        expressionOrderKeys = List.copyOf(expressionOrderKeys);
        if ((!orderKeys.isEmpty() || !expressionOrderKeys.isEmpty()) && rootKind != RootKind.LIST) {
            throw new IllegalArgumentException("TITAN-E001 query-template order metadata requires a list root");
        }
        if ((cursorWindow != null || expressionCursorWindow != null) && rootKind != RootKind.LIST) {
            throw new IllegalArgumentException("TITAN-E001 query-template cursor window requires a list root");
        }
        for (OrderKey orderKey : orderKeys) {
            requireAlias(root.alias(), orderKey.column().tableAlias(), "order key");
        }
        if (cursorWindow != null) {
            requireAlias(root.alias(), cursorWindow.cursorColumn().tableAlias(), "cursor window");
        }
        KnownAliases aliases = knownAliases(root, rootJoins);
        for (ExpressionPredicate predicate : expressionPredicates) {
            requireKnownAliases(aliases, predicate.expression(), "root expression predicate");
        }
        for (ExpressionOrderKey orderKey : expressionOrderKeys) {
            requireKnownAliases(aliases, orderKey.expression(), "root expression order key");
        }
        if (expressionCursorWindow != null) {
            requireKnownAliases(aliases, expressionCursorWindow.expression(), "root expression cursor window");
            if (!expressionCursorWindow.expression().type().equals(expressionCursorWindow.cursorParameter().type())) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template expression cursor parameter type must match cursor expression type");
            }
        }
    }

    public record TableRef(StructuralIdentifier schema, StructuralIdentifier table, String alias) {
        public TableRef {
            table = requireKind(table, IdentifierKind.TABLE);
            alias = requireName(alias, "alias");
            if (schema != null) {
                schema = requireKind(schema, IdentifierKind.SCHEMA);
            }
        }
    }

    public record ColumnRef(String tableAlias, StructuralIdentifier column) {
        public ColumnRef {
            tableAlias = requireName(tableAlias, "tableAlias");
            column = requireKind(column, IdentifierKind.COLUMN);
        }
    }

    public record Projection(ColumnRef column, String outputKey) {
        public Projection {
            Objects.requireNonNull(column, "column");
            outputKey = requireName(outputKey, "outputKey");
        }
    }

    public record ParameterPredicate(ColumnRef column, PredicateOperator operator, RuntimeParameter parameter) {
        public ParameterPredicate {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(parameter, "parameter");
        }
    }

    public record ExpressionPredicate(RowValue expression, PredicateOperator operator, RuntimeParameter parameter) {
        public ExpressionPredicate {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(parameter, "parameter");
            if (!expression.type().equals(parameter.type())) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template expression predicate parameter type must match expression type");
            }
        }
    }

    public record RelationEdge(
            String name,
            TableRef from,
            ColumnRef fromColumn,
            TableRef to,
            ColumnRef toColumn,
            RelationCardinality cardinality,
            List<OrderKey> orderKeys,
            RelationCursorWindow cursorWindow
    ) {
        public RelationEdge(
                String name,
                TableRef from,
                ColumnRef fromColumn,
                TableRef to,
                ColumnRef toColumn,
                RelationCardinality cardinality
        ) {
            this(name, from, fromColumn, to, toColumn, cardinality, List.of(), null);
        }

        public RelationEdge(
                String name,
                TableRef from,
                ColumnRef fromColumn,
                TableRef to,
                ColumnRef toColumn,
                RelationCardinality cardinality,
                List<OrderKey> orderKeys
        ) {
            this(name, from, fromColumn, to, toColumn, cardinality, orderKeys, null);
        }

        public RelationEdge {
            name = requireName(name, "name");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(fromColumn, "fromColumn");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(toColumn, "toColumn");
            Objects.requireNonNull(cardinality, "cardinality");
            orderKeys = List.copyOf(orderKeys);
            for (OrderKey orderKey : orderKeys) {
                requireAlias(to.alias(), orderKey.column().tableAlias(), "relation order key");
            }
            if (cursorWindow != null) {
                requireAlias(to.alias(), cursorWindow.cursorColumn().tableAlias(), "relation cursor window");
                requireAlias(to.alias(), cursorWindow.identityColumn().tableAlias(), "relation cursor identity");
            }
        }
    }

    public record OrderKey(ColumnRef column, SortDirection direction) {
        public OrderKey {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(direction, "direction");
        }
    }

    public record ExpressionOrderKey(RowValue expression, SortDirection direction) {
        public ExpressionOrderKey {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(direction, "direction");
        }
    }

    public record CursorWindow(
            ColumnRef cursorColumn,
            PredicateOperator operator,
            RuntimeParameter cursorParameter,
            int limit
    ) {
        public CursorWindow {
            Objects.requireNonNull(cursorColumn, "cursorColumn");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(cursorParameter, "cursorParameter");
            if (limit <= 0) {
                throw new IllegalArgumentException("TITAN-E001 query-template cursor window limit must be positive");
            }
        }
    }

    public record ExpressionCursorWindow(
            RowValue expression,
            PredicateOperator operator,
            RuntimeParameter cursorParameter,
            RuntimeParameter identityParameter,
            int limit
    ) {
        public ExpressionCursorWindow {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(cursorParameter, "cursorParameter");
            Objects.requireNonNull(identityParameter, "identityParameter");
            if (limit <= 0) {
                throw new IllegalArgumentException("TITAN-E001 query-template cursor window limit must be positive");
            }
        }
    }

    public record RelationCursorWindow(
            ColumnRef cursorColumn,
            PredicateOperator operator,
            RuntimeParameter cursorParameter,
            ColumnRef identityColumn,
            RuntimeParameter identityParameter,
            int limit
    ) {
        public RelationCursorWindow {
            Objects.requireNonNull(cursorColumn, "cursorColumn");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(cursorParameter, "cursorParameter");
            Objects.requireNonNull(identityColumn, "identityColumn");
            Objects.requireNonNull(identityParameter, "identityParameter");
            if (limit <= 0) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template relation cursor window limit must be positive");
            }
        }
    }

    public record RootJoin(TableRef from, ColumnRef fromColumn, TableRef to, ColumnRef toColumn) {
        public RootJoin {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(fromColumn, "fromColumn");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(toColumn, "toColumn");
            requireAlias(from.alias(), fromColumn.tableAlias(), "root join parent key");
            requireAlias(to.alias(), toColumn.tableAlias(), "root join child key");
        }
    }

    public interface RowValue {
        TirType type();
    }

    public record ColumnValue(ColumnRef column, TirType type) implements RowValue {
        public ColumnValue {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(type, "type");
        }
    }

    public record FunctionValue(String name, List<RowValue> arguments, TirType type) implements RowValue {
        public FunctionValue {
            name = requireName(name, "function name");
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(type, "type");
            if (!"CHAR_LENGTH".equals(name)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template root expression function '" + name + "' is unsupported");
            }
            if (arguments.size() != 1 || !(arguments.getFirst().type() instanceof TTextType)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template CHAR_LENGTH root expression requires one text argument");
            }
            if (!(type instanceof TIntType)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template CHAR_LENGTH root expression must produce an integer");
            }
        }
    }

    public record RuntimeParameter(String name, TirType type) {
        public RuntimeParameter {
            name = requireName(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    public static final class StructuralIdentifier {
        private final IdentifierKind kind;
        private final String value;
        private final IdentifierOrigin origin;

        private StructuralIdentifier(IdentifierKind kind, String value, IdentifierOrigin origin) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.value = requireName(value, "value");
            this.origin = Objects.requireNonNull(origin, "origin");
        }

        public static StructuralIdentifier compilerKnown(IdentifierKind kind, String value) {
            return new StructuralIdentifier(kind, value, IdentifierOrigin.COMPILER_KNOWN);
        }

        public static StructuralIdentifier runtimeValue(IdentifierKind kind, String value) {
            Objects.requireNonNull(kind, "kind");
            requireName(value, "value");
            throw new IllegalArgumentException(
                    "TITAN-E001 query-template structural identifier '" + kind.label()
                            + "' must be compiler-known, but was " + IdentifierOrigin.RUNTIME_VALUE.label());
        }

        public IdentifierKind kind() {
            return kind;
        }

        public String value() {
            return value;
        }

        public IdentifierOrigin origin() {
            return origin;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StructuralIdentifier that)) {
                return false;
            }
            return kind == that.kind
                    && value.equals(that.value)
                    && origin == that.origin;
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, value, origin);
        }
    }

    public enum IdentifierKind {
        SCHEMA("schema"),
        TABLE("table"),
        COLUMN("column");

        private final String label;

        IdentifierKind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    public enum IdentifierOrigin {
        COMPILER_KNOWN("compiler-known"),
        RUNTIME_VALUE("runtime value");

        private final String label;

        IdentifierOrigin(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    public enum PredicateOperator {
        EQ,
        NE,
        LT,
        LTE,
        GT,
        GTE
    }

    public enum RootKind {
        POINT,
        LIST
    }

    public enum RelationCardinality {
        ZERO_OR_ONE,
        ONE,
        MANY
    }

    private static StructuralIdentifier requireKind(StructuralIdentifier identifier, IdentifierKind expected) {
        Objects.requireNonNull(identifier, expected.label());
        if (identifier.kind() != expected) {
            throw new IllegalArgumentException("Expected query-template " + expected.label()
                    + " identifier but found " + identifier.kind().label());
        }
        return identifier;
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Query-template " + field + " must not be blank");
        }
        return value;
    }

    private static void requireAlias(String expected, String actual, String field) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException("TITAN-E001 query-template " + field
                    + " alias must be '" + expected + "', but was '" + actual + "'");
        }
    }

    private static KnownAliases knownAliases(TableRef root, List<RootJoin> rootJoins) {
        KnownAliases aliases = new KnownAliases();
        aliases.add(root.alias(), root.table());
        for (RootJoin join : rootJoins) {
            aliases.require(join.from().alias(), join.from().table(), "root join parent alias");
            aliases.add(join.to().alias(), join.to().table());
        }
        return aliases;
    }

    private static void requireKnownAliases(KnownAliases aliases, RowValue value, String field) {
        if (value instanceof ColumnValue columnValue) {
            aliases.require(
                    columnValue.column().tableAlias(),
                    null,
                    field + " column alias");
            return;
        }
        if (value instanceof FunctionValue functionValue) {
            for (RowValue argument : functionValue.arguments()) {
                requireKnownAliases(aliases, argument, field);
            }
            return;
        }
        throw new IllegalArgumentException(
                "TITAN-E001 query-template " + field + " uses an unsupported expression value");
    }

    private static final class KnownAliases {
        private final java.util.LinkedHashMap<String, StructuralIdentifier> aliases = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<String, String> aliasByTable = new java.util.LinkedHashMap<>();

        void add(String alias, StructuralIdentifier table) {
            StructuralIdentifier previous = aliases.putIfAbsent(alias, table);
            if (previous != null && !previous.equals(table)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template root expression alias '" + alias + "' is ambiguous");
            }
            String previousAlias = aliasByTable.putIfAbsent(table.value(), alias);
            if (previousAlias != null && !previousAlias.equals(alias)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template root expression relation path for table '"
                                + table.value() + "' is ambiguous");
            }
        }

        void require(String alias, StructuralIdentifier table, String field) {
            StructuralIdentifier known = aliases.get(alias);
            if (known == null) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template " + field + " must refer to the root or a declared root join");
            }
            if (table != null && !known.equals(table)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template " + field + " refers to table '"
                                + known.value() + "', not '" + table.value() + "'");
            }
        }
    }
}
