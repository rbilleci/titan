package io.titan.transpiler.tir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RowMaterializationPlan(
        QueryTemplatePlan.TableRef root,
        QueryTemplatePlan.RootKind rootKind,
        RowKey rootKey,
        List<FieldBinding> rootFields,
        List<RelationRows> relations,
        List<QueryTemplatePlan.ParameterPredicate> rootPredicates,
        List<QueryTemplatePlan.OrderKey> rootOrderKeys,
        QueryTemplatePlan.CursorWindow cursorWindow,
        List<QueryTemplatePlan.RootJoin> rootJoins,
        List<QueryTemplatePlan.ExpressionPredicate> rootExpressionPredicates,
        List<QueryTemplatePlan.ExpressionOrderKey> rootExpressionOrderKeys,
        QueryTemplatePlan.ExpressionCursorWindow expressionCursorWindow
) {
    public RowMaterializationPlan(
            QueryTemplatePlan.TableRef root,
            RowKey rootKey,
            List<FieldBinding> rootFields,
            List<RelationRows> relations
    ) {
        this(root, QueryTemplatePlan.RootKind.POINT, rootKey, rootFields, relations, List.of(), List.of(), null,
                List.of(), List.of(), List.of(), null);
    }

    public RowMaterializationPlan(
            QueryTemplatePlan.TableRef root,
            RowKey rootKey,
            List<FieldBinding> rootFields,
            List<RelationRows> relations,
            List<QueryTemplatePlan.ParameterPredicate> rootPredicates
    ) {
        this(root, QueryTemplatePlan.RootKind.POINT, rootKey, rootFields, relations, rootPredicates, List.of(), null,
                List.of(), List.of(), List.of(), null);
    }

    public RowMaterializationPlan {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(rootKind, "rootKind");
        Objects.requireNonNull(rootKey, "rootKey");
        rootFields = List.copyOf(rootFields);
        relations = List.copyOf(relations);
        rootPredicates = List.copyOf(rootPredicates);
        rootOrderKeys = List.copyOf(rootOrderKeys);
        rootJoins = List.copyOf(rootJoins);
        rootExpressionPredicates = List.copyOf(rootExpressionPredicates);
        rootExpressionOrderKeys = List.copyOf(rootExpressionOrderKeys);
        requireAlias(root.alias(), rootKey.column().tableAlias(), "root key");
        for (FieldBinding field : rootFields) {
            requireAlias(root.alias(), field.column().tableAlias(), "root field");
        }
        for (QueryTemplatePlan.ParameterPredicate predicate : rootPredicates) {
            requireAlias(root.alias(), predicate.column().tableAlias(), "root predicate");
        }
        for (QueryTemplatePlan.OrderKey orderKey : rootOrderKeys) {
            requireAlias(root.alias(), orderKey.column().tableAlias(), "root order key");
        }
        if (cursorWindow != null) {
            requireAlias(root.alias(), cursorWindow.cursorColumn().tableAlias(), "root cursor window");
        }
        if (cursorWindow != null && expressionCursorWindow != null) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime supports one root cursor window.");
        }
        LinkedHashMap<String, QueryTemplatePlan.TableRef> knownAliases = new LinkedHashMap<>();
        knownAliases.put(root.alias(), root);
        for (QueryTemplatePlan.RootJoin join : rootJoins) {
            if (!knownAliases.containsKey(join.from().alias())) {
                throw new IllegalArgumentException("Row materialization root join parent alias '"
                        + join.from().alias() + "' must refer to the root or an earlier root join");
            }
            knownAliases.put(join.to().alias(), join.to());
        }
        for (RelationRows relation : relations) {
            String parentAlias = relation.parentKey().column().tableAlias();
            if (!knownAliases.containsKey(parentAlias)) {
                throw new IllegalArgumentException("Row materialization relation parent alias '"
                        + parentAlias + "' must refer to the root or an earlier relation");
            }
            knownAliases.put(relation.childTable().alias(), relation.childTable());
        }
    }

    public static RowMaterializationPlan fromQueryTemplate(
            QueryTemplatePlan plan,
            QueryTemplatePlan.ColumnRef rootIdentityColumn,
            TirType rootIdentityType,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(rootIdentityColumn, "rootIdentityColumn");
        Objects.requireNonNull(rootIdentityType, "rootIdentityType");
        Objects.requireNonNull(columnTypes, "columnTypes");
        requireAlias(plan.root().alias(), rootIdentityColumn.tableAlias(), "root identity");

        LinkedHashMap<String, ArrayList<FieldBinding>> relationFields = new LinkedHashMap<>();
        LinkedHashMap<String, QueryTemplatePlan.RelationEdge> relationAliases = new LinkedHashMap<>();
        LinkedHashMap<String, QueryTemplatePlan.TableRef> knownAliases = new LinkedHashMap<>();
        knownAliases.put(plan.root().alias(), plan.root());
        for (QueryTemplatePlan.RelationEdge edge : plan.relationEdges()) {
            if (!knownAliases.containsKey(edge.from().alias())) {
                throw new IllegalArgumentException("Row materialization relation parent alias '"
                        + edge.from().alias() + "' must refer to the root or an earlier relation");
            }
            requireAlias(edge.from().alias(), edge.fromColumn().tableAlias(), "relation parent key");
            requireAlias(edge.to().alias(), edge.toColumn().tableAlias(), "relation child key");
            relationFields.putIfAbsent(edge.to().alias(), new ArrayList<>());
            QueryTemplatePlan.RelationEdge previous = relationAliases.putIfAbsent(edge.to().alias(), edge);
            if (previous != null) {
                throw new IllegalArgumentException("Row materialization relation alias '"
                        + edge.to().alias() + "' must be unique");
            }
            knownAliases.put(edge.to().alias(), edge.to());
        }

        ArrayList<FieldBinding> rootFields = new ArrayList<>();
        for (QueryTemplatePlan.Projection projection : plan.projections()) {
            QueryTemplatePlan.ColumnRef column = projection.column();
            FieldBinding field = new FieldBinding(
                    projection.outputKey(),
                    column,
                    requireColumnType(columnTypes, column));
            if (column.tableAlias().equals(plan.root().alias())) {
                rootFields.add(field);
            } else {
                ArrayList<FieldBinding> childFields = relationFields.get(column.tableAlias());
                if (childFields == null) {
                    throw new IllegalArgumentException("Row materialization projection alias '"
                            + column.tableAlias() + "' must refer to the root or a declared relation");
                }
                childFields.add(field);
            }
        }

        return new RowMaterializationPlan(
                plan.root(),
                plan.rootKind(),
                new RowKey(plan.root().alias() + "_identity", rootIdentityColumn, rootIdentityType),
                rootFields,
                relationRows(relationAliases, relationFields, columnTypes),
                rootPredicates(plan, columnTypes),
                rootOrderKeys(plan, columnTypes),
                rootCursorWindow(plan, columnTypes),
                rootJoins(plan),
                rootExpressionPredicates(plan, columnTypes),
                rootExpressionOrderKeys(plan, columnTypes),
                rootExpressionCursorWindow(plan, columnTypes, rootIdentityType));
    }

    private static List<QueryTemplatePlan.RootJoin> rootJoins(QueryTemplatePlan plan) {
        return plan.rootJoins();
    }

    private static List<QueryTemplatePlan.ExpressionPredicate> rootExpressionPredicates(
            QueryTemplatePlan plan,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes
    ) {
        ArrayList<QueryTemplatePlan.ExpressionPredicate> predicates = new ArrayList<>();
        for (QueryTemplatePlan.ExpressionPredicate predicate : plan.expressionPredicates()) {
            requireRowValueTypes(columnTypes, predicate.expression());
            predicates.add(predicate);
        }
        return predicates;
    }

    private static List<QueryTemplatePlan.ExpressionOrderKey> rootExpressionOrderKeys(
            QueryTemplatePlan plan,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes
    ) {
        ArrayList<QueryTemplatePlan.ExpressionOrderKey> orderKeys = new ArrayList<>();
        for (QueryTemplatePlan.ExpressionOrderKey orderKey : plan.expressionOrderKeys()) {
            requireRowValueTypes(columnTypes, orderKey.expression());
            orderKeys.add(orderKey);
        }
        return orderKeys;
    }

    private static QueryTemplatePlan.ExpressionCursorWindow rootExpressionCursorWindow(
            QueryTemplatePlan plan,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes,
            TirType rootIdentityType
    ) {
        QueryTemplatePlan.ExpressionCursorWindow cursorWindow = plan.expressionCursorWindow();
        if (cursorWindow == null) {
            return null;
        }
        TirType expressionType = requireRowValueTypes(columnTypes, cursorWindow.expression());
        if (!expressionType.equals(cursorWindow.cursorParameter().type())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime cursor parameter type must match root expression");
        }
        if (!rootIdentityType.equals(cursorWindow.identityParameter().type())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime cursor identity parameter type must match root identity key");
        }
        return cursorWindow;
    }

    private static List<QueryTemplatePlan.OrderKey> rootOrderKeys(
            QueryTemplatePlan plan,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes
    ) {
        ArrayList<QueryTemplatePlan.OrderKey> rootOrderKeys = new ArrayList<>();
        for (QueryTemplatePlan.OrderKey orderKey : plan.orderKeys()) {
            if (!orderKey.column().tableAlias().equals(plan.root().alias())) {
                throw new IllegalArgumentException("TITAN-E001 row materialization runtime order alias '"
                        + orderKey.column().tableAlias() + "' must currently refer to the root alias '"
                        + plan.root().alias() + "'");
            }
            requireColumnType(columnTypes, orderKey.column());
            rootOrderKeys.add(orderKey);
        }
        return rootOrderKeys;
    }

    private static QueryTemplatePlan.CursorWindow rootCursorWindow(
            QueryTemplatePlan plan,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes
    ) {
        QueryTemplatePlan.CursorWindow cursorWindow = plan.cursorWindow();
        if (cursorWindow == null) {
            return null;
        }
        if (!cursorWindow.cursorColumn().tableAlias().equals(plan.root().alias())) {
            throw new IllegalArgumentException("TITAN-E001 row materialization runtime cursor alias '"
                    + cursorWindow.cursorColumn().tableAlias() + "' must currently refer to the root alias '"
                    + plan.root().alias() + "'");
        }
        TirType columnType = requireColumnType(columnTypes, cursorWindow.cursorColumn());
        if (!columnType.equals(cursorWindow.cursorParameter().type())) {
            throw new IllegalArgumentException("TITAN-E001 row materialization runtime cursor parameter type"
                    + " must match column '" + cursorWindow.cursorColumn().tableAlias()
                    + "." + cursorWindow.cursorColumn().column().value() + "'");
        }
        return cursorWindow;
    }

    private static List<QueryTemplatePlan.ParameterPredicate> rootPredicates(
            QueryTemplatePlan plan,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes
    ) {
        ArrayList<QueryTemplatePlan.ParameterPredicate> rootPredicates = new ArrayList<>();
        for (QueryTemplatePlan.ParameterPredicate predicate : plan.predicates()) {
            if (!predicate.column().tableAlias().equals(plan.root().alias())) {
                throw new IllegalArgumentException("TITAN-E001 row materialization runtime predicate alias '"
                        + predicate.column().tableAlias() + "' must currently refer to the root alias '"
                        + plan.root().alias() + "'");
            }
            TirType columnType = requireColumnType(columnTypes, predicate.column());
            if (!columnType.equals(predicate.parameter().type())) {
                throw new IllegalArgumentException("TITAN-E001 row materialization runtime predicate parameter type"
                        + " must match column '" + predicate.column().tableAlias()
                        + "." + predicate.column().column().value() + "'");
            }
            rootPredicates.add(predicate);
        }
        return rootPredicates;
    }

    private static List<RelationRows> relationRows(
            Map<String, QueryTemplatePlan.RelationEdge> relationAliases,
            Map<String, ArrayList<FieldBinding>> relationFields,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes
    ) {
        ArrayList<RelationRows> rows = new ArrayList<>();
        for (Map.Entry<String, QueryTemplatePlan.RelationEdge> entry : relationAliases.entrySet()) {
            QueryTemplatePlan.RelationEdge edge = entry.getValue();
            rows.add(new RelationRows(
                    edge.name(),
                    edge.to(),
                    new RowKey(edge.from().alias() + "_key", edge.fromColumn(),
                            requireColumnType(columnTypes, edge.fromColumn())),
                    new RowKey(childKeyName(edge, relationAliases), edge.toColumn(),
                            requireColumnType(columnTypes, edge.toColumn())),
                    edge.cardinality(),
                    relationFields.get(entry.getKey()),
                    relationOrderKeys(edge, columnTypes),
                    relationCursorWindow(edge, columnTypes)));
        }
        return rows;
    }

    private static String childKeyName(
            QueryTemplatePlan.RelationEdge edge,
            Map<String, QueryTemplatePlan.RelationEdge> relationAliases
    ) {
        for (QueryTemplatePlan.RelationEdge other : relationAliases.values()) {
            if (other.from().alias().equals(edge.to().alias()) && !other.fromColumn().equals(edge.toColumn())) {
                return edge.to().alias() + "_" + edge.toColumn().column().value() + "_key";
            }
        }
        return edge.to().alias() + "_key";
    }

    private static List<QueryTemplatePlan.OrderKey> relationOrderKeys(
            QueryTemplatePlan.RelationEdge edge,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes
    ) {
        ArrayList<QueryTemplatePlan.OrderKey> orderKeys = new ArrayList<>();
        for (QueryTemplatePlan.OrderKey orderKey : edge.orderKeys()) {
            requireAlias(edge.to().alias(), orderKey.column().tableAlias(), "relation order key");
            requireColumnType(columnTypes, orderKey.column());
            orderKeys.add(orderKey);
        }
        return orderKeys;
    }

    private static QueryTemplatePlan.RelationCursorWindow relationCursorWindow(
            QueryTemplatePlan.RelationEdge edge,
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes
    ) {
        QueryTemplatePlan.RelationCursorWindow cursorWindow = edge.cursorWindow();
        if (cursorWindow == null) {
            return null;
        }
        TirType cursorType = requireColumnType(columnTypes, cursorWindow.cursorColumn());
        if (!cursorType.equals(cursorWindow.cursorParameter().type())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime relation cursor parameter type must match column '"
                            + cursorWindow.cursorColumn().tableAlias()
                            + "." + cursorWindow.cursorColumn().column().value() + "'");
        }
        TirType identityType = requireColumnType(columnTypes, cursorWindow.identityColumn());
        if (!identityType.equals(cursorWindow.identityParameter().type())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime relation cursor identity parameter type must match column '"
                            + cursorWindow.identityColumn().tableAlias()
                            + "." + cursorWindow.identityColumn().column().value() + "'");
        }
        requireAlias(edge.to().alias(), cursorWindow.cursorColumn().tableAlias(), "relation cursor window");
        requireAlias(edge.to().alias(), cursorWindow.identityColumn().tableAlias(), "relation cursor identity");
        return cursorWindow;
    }

    public record RowKey(String name, QueryTemplatePlan.ColumnRef column, TirType type) {
        public RowKey {
            name = requireName(name, "row key name");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(type, "type");
        }
    }

    public record FieldBinding(String fieldName, QueryTemplatePlan.ColumnRef column, TirType type) {
        public FieldBinding {
            fieldName = requireName(fieldName, "field name");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(type, "type");
        }
    }

    public record RelationRows(
            String name,
            QueryTemplatePlan.TableRef childTable,
            RowKey parentKey,
            RowKey childKey,
            QueryTemplatePlan.RelationCardinality cardinality,
            List<FieldBinding> fields,
            List<QueryTemplatePlan.OrderKey> orderKeys,
            QueryTemplatePlan.RelationCursorWindow cursorWindow
    ) {
        public RelationRows(
                String name,
                QueryTemplatePlan.TableRef childTable,
                RowKey parentKey,
                RowKey childKey,
                QueryTemplatePlan.RelationCardinality cardinality,
                List<FieldBinding> fields
        ) {
            this(name, childTable, parentKey, childKey, cardinality, fields, List.of(), null);
        }

        public RelationRows(
                String name,
                QueryTemplatePlan.TableRef childTable,
                RowKey parentKey,
                RowKey childKey,
                QueryTemplatePlan.RelationCardinality cardinality,
                List<FieldBinding> fields,
                List<QueryTemplatePlan.OrderKey> orderKeys
        ) {
            this(name, childTable, parentKey, childKey, cardinality, fields, orderKeys, null);
        }

        public RelationRows {
            name = requireName(name, "relation name");
            Objects.requireNonNull(childTable, "childTable");
            Objects.requireNonNull(parentKey, "parentKey");
            Objects.requireNonNull(childKey, "childKey");
            Objects.requireNonNull(cardinality, "cardinality");
            fields = List.copyOf(fields);
            orderKeys = List.copyOf(orderKeys);
            requireAlias(childTable.alias(), childKey.column().tableAlias(), "relation child key");
            if (!parentKey.type().equals(childKey.type())) {
                throw new IllegalArgumentException("Row materialization relation key types must match");
            }
            for (FieldBinding field : fields) {
                requireAlias(childTable.alias(), field.column().tableAlias(), "relation field");
            }
            for (QueryTemplatePlan.OrderKey orderKey : orderKeys) {
                requireAlias(childTable.alias(), orderKey.column().tableAlias(), "relation order key");
            }
            if (cursorWindow != null) {
                requireAlias(childTable.alias(), cursorWindow.cursorColumn().tableAlias(), "relation cursor window");
                requireAlias(childTable.alias(), cursorWindow.identityColumn().tableAlias(), "relation cursor identity");
            }
        }
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Row materialization " + field + " must not be blank");
        }
        return value;
    }

    private static TirType requireColumnType(
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes,
            QueryTemplatePlan.ColumnRef column
    ) {
        TirType type = columnTypes.get(column);
        if (type == null) {
            throw new IllegalArgumentException("Row materialization column '"
                    + column.tableAlias() + "." + column.column().value() + "' must have a row field type");
        }
        return type;
    }

    private static TirType requireRowValueTypes(
            Map<QueryTemplatePlan.ColumnRef, TirType> columnTypes,
            QueryTemplatePlan.RowValue value
    ) {
        if (value instanceof QueryTemplatePlan.ColumnValue columnValue) {
            TirType type = requireColumnType(columnTypes, columnValue.column());
            if (!type.equals(columnValue.type())) {
                throw new IllegalArgumentException("TITAN-E001 row materialization runtime expression type"
                        + " must match column '" + columnValue.column().tableAlias()
                        + "." + columnValue.column().column().value() + "'");
            }
            return type;
        }
        if (value instanceof QueryTemplatePlan.FunctionValue functionValue) {
            for (QueryTemplatePlan.RowValue argument : functionValue.arguments()) {
                requireRowValueTypes(columnTypes, argument);
            }
            return functionValue.type();
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime expression value is unsupported");
    }

    private static void requireAlias(String expected, String actual, String field) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException("Row materialization " + field
                    + " alias must be '" + expected + "', but was '" + actual + "'");
        }
    }
}
