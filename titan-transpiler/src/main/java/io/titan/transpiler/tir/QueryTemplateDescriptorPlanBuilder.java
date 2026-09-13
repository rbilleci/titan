package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class QueryTemplateDescriptorPlanBuilder {
    private QueryTemplateDescriptorPlanBuilder() {
    }

    public static QueryTemplatePlan buildRootPlan(
            RootDescriptor root,
            List<FieldDescriptor> projections,
            List<RuntimePredicate> predicates,
            List<RelationDescriptor> relations
    ) {
        return buildRootPlan(root, projections, predicates, relations, List.of(), null);
    }

    public static QueryTemplatePlan buildRootPlan(
            RootDescriptor root,
            List<FieldDescriptor> projections,
            List<RuntimePredicate> predicates,
            List<RelationDescriptor> relations,
            List<OrderDescriptor> orderKeys,
            CursorWindowDescriptor cursorWindow
    ) {
        Objects.requireNonNull(root, "root");
        List<FieldDescriptor> projectionSnapshot = List.copyOf(projections);
        List<RuntimePredicate> predicateSnapshot = List.copyOf(predicates);
        List<RelationDescriptor> relationSnapshot = List.copyOf(relations);
        List<OrderDescriptor> orderSnapshot = List.copyOf(orderKeys);
        if (!root.listRoot() && predicateSnapshot.isEmpty()) {
            throw new IllegalArgumentException("Query-template point root must carry a lookup predicate");
        }
        if (!root.listRoot() && (!orderSnapshot.isEmpty() || cursorWindow != null)) {
            throw new IllegalArgumentException("TITAN-E001 query-template order/window metadata requires a list root");
        }

        QueryTemplatePlan.TableRef rootTable = new QueryTemplatePlan.TableRef(
                null,
                root.table(),
                root.alias());
        Map<String, QueryTemplatePlan.StructuralIdentifier> aliases = aliases(root, relationSnapshot);

        return new QueryTemplatePlan(
                rootTable,
                root.listRoot() ? QueryTemplatePlan.RootKind.LIST : QueryTemplatePlan.RootKind.POINT,
                projectionSnapshot.stream()
                        .map(field -> new QueryTemplatePlan.Projection(
                                new QueryTemplatePlan.ColumnRef(
                                        validatedAlias(aliases, field.tableAlias(), field.table()),
                                        field.column()),
                                field.outputKey()))
                        .toList(),
                predicateSnapshot.stream()
                        .map(predicate -> new QueryTemplatePlan.ParameterPredicate(
                                new QueryTemplatePlan.ColumnRef(
                                        validatedAlias(aliases, predicate.tableAlias(), predicate.table()),
                                        predicate.column()),
                                predicate.operator(),
                                predicate.parameter()))
                        .toList(),
                relationEdges(root, rootTable, relationSnapshot),
                orderSnapshot.stream()
                        .map(order -> new QueryTemplatePlan.OrderKey(
                                new QueryTemplatePlan.ColumnRef(
                                        validatedAlias(aliases, order.tableAlias(), order.table()),
                                        order.column()),
                                order.direction()))
                        .toList(),
                cursorWindow == null
                        ? null
                        : new QueryTemplatePlan.CursorWindow(
                                new QueryTemplatePlan.ColumnRef(
                                        validatedAlias(aliases, cursorWindow.tableAlias(), cursorWindow.table()),
                                        cursorWindow.column()),
                                cursorWindow.operator(),
                                cursorWindow.parameter(),
                                cursorWindow.limit()));
    }

    private static List<QueryTemplatePlan.RelationEdge> relationEdges(
            RootDescriptor root,
            QueryTemplatePlan.TableRef rootTable,
            List<RelationDescriptor> relations
    ) {
        ArrayList<QueryTemplatePlan.RelationEdge> edges = new ArrayList<>();
        for (RelationDescriptor relation : relations) {
            String fromAlias = relation.fromAlias();
            QueryTemplatePlan.TableRef fromTable = fromAlias.equals(root.alias())
                    ? rootTable
                    : new QueryTemplatePlan.TableRef(null, relation.fromTable(), fromAlias);
            QueryTemplatePlan.TableRef toTable = new QueryTemplatePlan.TableRef(
                    null,
                    relation.toTable(),
                    relation.toAlias());
            edges.add(new QueryTemplatePlan.RelationEdge(
                    relation.name(),
                    fromTable,
                    new QueryTemplatePlan.ColumnRef(fromAlias, relation.fromColumn()),
                    toTable,
                    new QueryTemplatePlan.ColumnRef(relation.toAlias(), relation.toColumn()),
                    relation.cardinality(),
                    relation.orderKeys().stream()
                            .map(order -> new QueryTemplatePlan.OrderKey(
                                    new QueryTemplatePlan.ColumnRef(
                                            validatedRelationOrderAlias(relation, order),
                                            order.column()),
                                    order.direction()))
                            .toList(),
                    relation.cursorWindow() == null
                            ? null
                            : new QueryTemplatePlan.RelationCursorWindow(
                                    new QueryTemplatePlan.ColumnRef(
                                            validatedRelationCursorAlias(relation, relation.cursorWindow().tableAlias(),
                                                    relation.cursorWindow().table()),
                                            relation.cursorWindow().column()),
                                    relation.cursorWindow().operator(),
                                    relation.cursorWindow().parameter(),
                                    new QueryTemplatePlan.ColumnRef(
                                            validatedRelationCursorAlias(relation,
                                                    relation.cursorWindow().identityTableAlias(),
                                                    relation.cursorWindow().identityTable()),
                                            relation.cursorWindow().identityColumn()),
                                    relation.cursorWindow().identityParameter(),
                                    relation.cursorWindow().limit())));
        }
        return edges;
    }

    private static String validatedRelationOrderAlias(RelationDescriptor relation, OrderDescriptor order) {
        if (!order.tableAlias().equals(relation.toAlias()) || !order.table().equals(relation.toTable())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 query-template relation order key alias must refer to relation '"
                            + relation.name() + "' child alias '" + relation.toAlias() + "'");
        }
        return order.tableAlias();
    }

    private static String validatedRelationCursorAlias(
            RelationDescriptor relation,
            String alias,
            QueryTemplatePlan.StructuralIdentifier table
    ) {
        if (!alias.equals(relation.toAlias()) || !table.equals(relation.toTable())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 query-template relation cursor window alias must refer to relation '"
                            + relation.name() + "' child alias '" + relation.toAlias() + "'");
        }
        return alias;
    }

    private static Map<String, QueryTemplatePlan.StructuralIdentifier> aliases(
            RootDescriptor root,
            List<RelationDescriptor> relations
    ) {
        LinkedHashMap<String, QueryTemplatePlan.StructuralIdentifier> aliases = new LinkedHashMap<>();
        aliases.put(root.alias(), root.table());
        for (RelationDescriptor relation : relations) {
            validatedAlias(aliases, relation.fromAlias(), relation.fromTable());
            putAlias(aliases, relation.toAlias(), relation.toTable());
        }
        return aliases;
    }

    private static String validatedAlias(
            Map<String, QueryTemplatePlan.StructuralIdentifier> aliases,
            String alias,
            QueryTemplatePlan.StructuralIdentifier table
    ) {
        alias = requireAlias(alias, "alias");
        table = requireTable(table);
        QueryTemplatePlan.StructuralIdentifier knownTable = aliases.get(alias);
        if (knownTable == null) {
            throw new IllegalArgumentException("Query-template descriptor alias '" + alias
                    + "' must refer to the root table or a declared relation table");
        }
        if (!knownTable.equals(table)) {
            throw new IllegalArgumentException("Query-template descriptor alias '" + alias
                    + "' refers to table '" + knownTable.value() + "', not '" + table.value() + "'");
        }
        return alias;
    }

    private static void putAlias(
            Map<String, QueryTemplatePlan.StructuralIdentifier> aliases,
            String alias,
            QueryTemplatePlan.StructuralIdentifier table
    ) {
        alias = requireAlias(alias, "alias");
        table = requireTable(table);
        QueryTemplatePlan.StructuralIdentifier previous = aliases.putIfAbsent(alias, table);
        if (previous != null && !previous.equals(table)) {
            throw new IllegalArgumentException("Query-template descriptor alias '" + alias
                    + "' is already bound to table '" + previous.value() + "'");
        }
    }

    public record RootDescriptor(
            String name,
            QueryTemplatePlan.StructuralIdentifier table,
            String alias,
            boolean listRoot
    ) {
        public RootDescriptor {
            name = requireName(name, "name");
            table = requireTable(table);
            alias = requireAlias(alias, "alias");
        }
    }

    public record FieldDescriptor(
            String name,
            QueryTemplatePlan.StructuralIdentifier table,
            String tableAlias,
            QueryTemplatePlan.StructuralIdentifier column,
            String outputKey
    ) {
        public FieldDescriptor {
            name = requireName(name, "name");
            table = requireTable(table);
            tableAlias = requireAlias(tableAlias, "tableAlias");
            column = requireColumn(column);
            outputKey = requireName(outputKey, "outputKey");
        }
    }

    public record RelationDescriptor(
            String name,
            QueryTemplatePlan.StructuralIdentifier fromTable,
            String fromAlias,
            QueryTemplatePlan.StructuralIdentifier fromColumn,
            QueryTemplatePlan.StructuralIdentifier toTable,
            QueryTemplatePlan.StructuralIdentifier toColumn,
            String toAlias,
            QueryTemplatePlan.RelationCardinality cardinality,
            List<OrderDescriptor> orderKeys,
            RelationCursorWindowDescriptor cursorWindow
    ) {
        public RelationDescriptor(
                String name,
                QueryTemplatePlan.StructuralIdentifier fromTable,
                String fromAlias,
                QueryTemplatePlan.StructuralIdentifier fromColumn,
                QueryTemplatePlan.StructuralIdentifier toTable,
                QueryTemplatePlan.StructuralIdentifier toColumn,
                String toAlias,
                QueryTemplatePlan.RelationCardinality cardinality
        ) {
            this(name, fromTable, fromAlias, fromColumn, toTable, toColumn, toAlias, cardinality, List.of(), null);
        }

        public RelationDescriptor(
                String name,
                QueryTemplatePlan.StructuralIdentifier fromTable,
                String fromAlias,
                QueryTemplatePlan.StructuralIdentifier fromColumn,
                QueryTemplatePlan.StructuralIdentifier toTable,
                QueryTemplatePlan.StructuralIdentifier toColumn,
                String toAlias,
                QueryTemplatePlan.RelationCardinality cardinality,
                List<OrderDescriptor> orderKeys
        ) {
            this(name, fromTable, fromAlias, fromColumn, toTable, toColumn, toAlias, cardinality, orderKeys, null);
        }

        public RelationDescriptor {
            name = requireName(name, "name");
            fromTable = requireTable(fromTable);
            fromAlias = requireAlias(fromAlias, "fromAlias");
            fromColumn = requireColumn(fromColumn);
            toTable = requireTable(toTable);
            toColumn = requireColumn(toColumn);
            toAlias = requireAlias(toAlias, "toAlias");
            Objects.requireNonNull(cardinality, "cardinality");
            orderKeys = List.copyOf(orderKeys);
        }
    }

    public record RuntimePredicate(
            QueryTemplatePlan.StructuralIdentifier table,
            String tableAlias,
            QueryTemplatePlan.StructuralIdentifier column,
            QueryTemplatePlan.PredicateOperator operator,
            QueryTemplatePlan.RuntimeParameter parameter
    ) {
        public RuntimePredicate {
            table = requireTable(table);
            tableAlias = requireAlias(tableAlias, "tableAlias");
            column = requireColumn(column);
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(parameter, "parameter");
        }
    }

    public record OrderDescriptor(
            QueryTemplatePlan.StructuralIdentifier table,
            String tableAlias,
            QueryTemplatePlan.StructuralIdentifier column,
            SortDirection direction
    ) {
        public OrderDescriptor {
            table = requireTable(table);
            tableAlias = requireAlias(tableAlias, "tableAlias");
            column = requireColumn(column);
            Objects.requireNonNull(direction, "direction");
        }
    }

    public record CursorWindowDescriptor(
            QueryTemplatePlan.StructuralIdentifier table,
            String tableAlias,
            QueryTemplatePlan.StructuralIdentifier column,
            QueryTemplatePlan.PredicateOperator operator,
            QueryTemplatePlan.RuntimeParameter parameter,
            int limit
    ) {
        public CursorWindowDescriptor {
            table = requireTable(table);
            tableAlias = requireAlias(tableAlias, "tableAlias");
            column = requireColumn(column);
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(parameter, "parameter");
            if (limit <= 0) {
                throw new IllegalArgumentException("TITAN-E001 query-template cursor window limit must be positive");
            }
        }
    }

    public record RelationCursorWindowDescriptor(
            QueryTemplatePlan.StructuralIdentifier table,
            String tableAlias,
            QueryTemplatePlan.StructuralIdentifier column,
            QueryTemplatePlan.PredicateOperator operator,
            QueryTemplatePlan.RuntimeParameter parameter,
            QueryTemplatePlan.StructuralIdentifier identityTable,
            String identityTableAlias,
            QueryTemplatePlan.StructuralIdentifier identityColumn,
            QueryTemplatePlan.RuntimeParameter identityParameter,
            int limit
    ) {
        public RelationCursorWindowDescriptor {
            table = requireTable(table);
            tableAlias = requireAlias(tableAlias, "tableAlias");
            column = requireColumn(column);
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(parameter, "parameter");
            identityTable = requireTable(identityTable);
            identityTableAlias = requireAlias(identityTableAlias, "identityTableAlias");
            identityColumn = requireColumn(identityColumn);
            Objects.requireNonNull(identityParameter, "identityParameter");
            if (limit <= 0) {
                throw new IllegalArgumentException(
                        "TITAN-E001 query-template relation cursor window limit must be positive");
            }
        }
    }

    private static QueryTemplatePlan.StructuralIdentifier requireTable(
            QueryTemplatePlan.StructuralIdentifier identifier
    ) {
        Objects.requireNonNull(identifier, "table");
        if (identifier.kind() != TABLE) {
            throw new IllegalArgumentException("Expected query-template table identifier but found "
                    + identifier.kind().label());
        }
        return identifier;
    }

    private static QueryTemplatePlan.StructuralIdentifier requireColumn(
            QueryTemplatePlan.StructuralIdentifier identifier
    ) {
        Objects.requireNonNull(identifier, "column");
        if (identifier.kind() != COLUMN) {
            throw new IllegalArgumentException("Expected query-template column identifier but found "
                    + identifier.kind().label());
        }
        return identifier;
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Query-template descriptor " + field + " must not be blank");
        }
        return value;
    }

    private static String requireAlias(String value, String field) {
        value = requireName(value, field);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Query-template descriptor " + field
                    + " must be a compiler-known SQL alias");
        }
        return value;
    }
}
