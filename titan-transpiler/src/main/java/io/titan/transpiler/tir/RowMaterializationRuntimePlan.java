package io.titan.transpiler.tir;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record RowMaterializationRuntimePlan(
        MaterializedRootQuery rootQuery,
        List<MaterializedRelationQuery> relationQueries
) {
    public RowMaterializationRuntimePlan {
        Objects.requireNonNull(rootQuery, "rootQuery");
        relationQueries = List.copyOf(relationQueries);
    }

    public static RowMaterializationRuntimePlan from(
            RowMaterializationPlan rows,
            QueryTemplatePlan.RuntimeParameter rootParameter
    ) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(rootParameter, "rootParameter");
        if (rows.rootKind() != QueryTemplatePlan.RootKind.POINT) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime point lookup requires a point root plan.");
        }
        if (!rows.rootKey().type().equals(rootParameter.type())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime root parameter type must match the root identity key type.");
        }
        MaterializedRootQuery rootQuery = rootSelect(rows, rootParameter.name(), rootRelationParentKeys(rows));
        ArrayList<MaterializedRelationQuery> relationQueries = new ArrayList<>();
        for (RowMaterializationPlan.RelationRows relation : rows.relations()) {
            ParentKeySource parentKeySource = parentKeySource(rows, relation, rootQuery, relationQueries);
            String parameterName = parentKeySource.kind() == ParentKeySource.Kind.ROOT_IDENTITY
                    ? rootParameter.name()
                    : carrierParameterName(relation);
            boolean batched = parentKeySource.kind() == ParentKeySource.Kind.RELATION_FIELD
                    || parentKeySource.kind() == ParentKeySource.Kind.RELATION_HIDDEN_KEY;
            relationQueries.add(relationSelect(
                    relation,
                    parentKeySource,
                    parameterName,
                    batched,
                    relationParentKeys(rows, relation.childTable().alias())));
        }
        return new RowMaterializationRuntimePlan(rootQuery, relationQueries);
    }

    public static RowMaterializationRuntimePlan fromListRoot(RowMaterializationPlan rows) {
        return fromListRoot(rows, (Integer) null);
    }

    public static RowMaterializationRuntimePlan fromListRoot(RowMaterializationPlan rows, int firstPageLimit) {
        if (firstPageLimit <= 0) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime first-page limit must be positive.");
        }
        return fromListRoot(rows, Integer.valueOf(firstPageLimit));
    }

    private static RowMaterializationRuntimePlan fromListRoot(RowMaterializationPlan rows, Integer firstPageLimit) {
        Objects.requireNonNull(rows, "rows");
        if (rows.rootKind() != QueryTemplatePlan.RootKind.LIST) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime list root requires a list root plan.");
        }
        boolean hasCursorWindow = rows.cursorWindow() != null || rows.expressionCursorWindow() != null;
        boolean hasOrder = !rows.rootOrderKeys().isEmpty() || !rows.rootExpressionOrderKeys().isEmpty();
        if (hasCursorWindow && firstPageLimit != null) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime first-page limit requires a list root without a cursor window.");
        }
        if ((firstPageLimit != null || hasCursorWindow) && !hasOrder) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime bounded list-root window requires stable root ordering.");
        }
        if (firstPageLimit != null || hasCursorWindow) {
            requireRootIdentityInOrder(rows);
        }
        if (hasCursorWindow) {
            requireCursorAlignedWithRootOrder(rows);
        }
        MaterializedRootQuery rootQuery = listRootSelect(rows, rootRelationParentKeys(rows), firstPageLimit);
        ArrayList<MaterializedRelationQuery> relationQueries = new ArrayList<>();
        for (RowMaterializationPlan.RelationRows relation : rows.relations()) {
            relationQueries.add(relationSelect(
                    relation,
                    parentKeySource(rows, relation, rootQuery, relationQueries),
                    carrierParameterName(relation),
                    true,
                    relationParentKeys(rows, relation.childTable().alias())));
        }
        return new RowMaterializationRuntimePlan(
                rootQuery,
                relationQueries);
    }

    public record MaterializedRootQuery(
            RowMaterializationPlan.RowKey rootKey,
            List<RowFieldProjection> fields,
            List<RowFieldProjection> hiddenKeyProjections,
            Optional<RootWindowFacts> windowFacts,
            SelectSql select
    ) {
        public MaterializedRootQuery(RowMaterializationPlan.RowKey rootKey, SelectSql select) {
            this(rootKey, List.of(), List.of(), Optional.empty(), select);
        }

        public MaterializedRootQuery {
            Objects.requireNonNull(rootKey, "rootKey");
            fields = List.copyOf(fields);
            hiddenKeyProjections = List.copyOf(hiddenKeyProjections);
            Objects.requireNonNull(windowFacts, "windowFacts");
            Objects.requireNonNull(select, "select");
        }
    }

    public record RootWindowFacts(
            int limit,
            Optional<String> cursorAlias,
            String identityAlias,
            Optional<String> cursorParameter
    ) {
        public RootWindowFacts {
            if (limit <= 0) {
                throw new IllegalArgumentException(
                        "TITAN-E001 row materialization runtime root window limit must be positive.");
            }
            cursorAlias = Objects.requireNonNull(cursorAlias, "cursorAlias")
                    .filter(alias -> !alias.isBlank());
            identityAlias = requireName(identityAlias, "identityAlias");
            cursorParameter = Objects.requireNonNull(cursorParameter, "cursorParameter")
                    .filter(parameter -> !parameter.isBlank());
        }
    }

    public record MaterializedRelationQuery(
            String name,
            ParentKeySource parentKeySource,
            ParentKeyCarrier parentKeyCarrier,
            RowMaterializationPlan.RowKey childPredicateKey,
            List<RowFieldProjection> fields,
            List<RowFieldProjection> hiddenKeyProjections,
            List<QueryTemplatePlan.OrderKey> orderKeys,
            QueryTemplatePlan.RelationCursorWindow cursorWindow,
            QueryTemplatePlan.RelationCardinality cardinality,
            SelectSql select
    ) {
        public MaterializedRelationQuery(
                String name,
                RowMaterializationPlan.RowKey parentKey,
                RowMaterializationPlan.RowKey childKey,
                QueryTemplatePlan.RelationCardinality cardinality,
                SelectSql select
        ) {
            this(name, new ParentKeySource(parentKey, ParentKeySource.Kind.ROOT_IDENTITY),
                    new ParentKeyCarrier("p_" + requireName(name, "name") + "_parent_key", parentKey.type()),
                    childKey, List.of(), List.of(), List.of(), null, cardinality, select);
        }

        public MaterializedRelationQuery {
            name = requireName(name, "name");
            Objects.requireNonNull(parentKeySource, "parentKeySource");
            Objects.requireNonNull(parentKeyCarrier, "parentKeyCarrier");
            Objects.requireNonNull(childPredicateKey, "childPredicateKey");
            fields = List.copyOf(fields);
            hiddenKeyProjections = List.copyOf(hiddenKeyProjections);
            orderKeys = List.copyOf(orderKeys);
            Objects.requireNonNull(cardinality, "cardinality");
            Objects.requireNonNull(select, "select");
            if (!parentKeySource.key().type().equals(parentKeyCarrier.type())) {
                throw new IllegalArgumentException(
                        "TITAN-E001 row materialization runtime parent-key carrier type must match relation '"
                                + name + "' parent key type.");
            }
        }

        public RowMaterializationPlan.RowKey parentKey() {
            return parentKeySource.key();
        }

        public RowMaterializationPlan.RowKey childKey() {
            return childPredicateKey;
        }
    }

    public record ParentKeySource(RowMaterializationPlan.RowKey key, Kind kind) {
        public ParentKeySource {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
        }

        public enum Kind {
            ROOT_IDENTITY,
            ROOT_FIELD,
            ROOT_HIDDEN_KEY,
            RELATION_FIELD,
            RELATION_HIDDEN_KEY
        }
    }

    public record ParentKeyCarrier(String parameterName, TirType type) {
        public ParentKeyCarrier {
            parameterName = requireName(parameterName, "parent-key carrier parameter");
            Objects.requireNonNull(type, "type");
        }
    }

    public record CollectedParentKeys(
            String relationName,
            ParentKeySource source,
            List<Object> rowKeys,
            List<Object> queryKeys
    ) {
        public CollectedParentKeys {
            relationName = requireName(relationName, "relation name");
            Objects.requireNonNull(source, "source");
            rowKeys = Collections.unmodifiableList(new ArrayList<>(rowKeys));
            queryKeys = List.copyOf(queryKeys);
        }
    }

    public record RowFieldProjection(
            String alias,
            QueryTemplatePlan.ColumnRef column,
            TirType type,
            boolean hidden
    ) {
        public RowFieldProjection {
            alias = requireName(alias, "alias");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(type, "type");
        }
    }

    public static CollectedParentKeys collectParentKeys(
            MaterializedRelationQuery relation,
            List<Map<String, Object>> rootRows
    ) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(rootRows, "rootRows");
        ArrayList<Object> rowKeys = new ArrayList<>();
        LinkedHashSet<Object> queryKeys = new LinkedHashSet<>();
        String alias = relation.parentKeySource().key().name();
        for (Map<String, Object> rootRow : rootRows) {
            if (!rootRow.containsKey(alias)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 row materialization runtime parent-key projection '" + alias
                                + "' is missing for relation '" + relation.name() + "'.");
            }
            Object value = rootRow.get(alias);
            rowKeys.add(value);
            if (value != null) {
                queryKeys.add(value);
            }
        }
        return new CollectedParentKeys(
                relation.name(),
                relation.parentKeySource(),
                rowKeys,
                new ArrayList<>(queryKeys));
    }

    public static List<Map<String, Object>> windowRootRows(
            MaterializedRootQuery rootQuery,
            List<Map<String, Object>> sourceRows,
            Map<String, Object> runtimeValues
    ) {
        Objects.requireNonNull(rootQuery, "rootQuery");
        Objects.requireNonNull(sourceRows, "sourceRows");
        Objects.requireNonNull(runtimeValues, "runtimeValues");
        SelectSql select = rootQuery.select();
        if (!select.groupBy().isEmpty() || select.having() != null
                || select.offset() != null || select.existsWrapper() || select.locking() != null
                || !select.ctes().isEmpty()) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime root-list window only supports bounded root SELECTs.");
        }

        List<Map<String, Object>> rows = sourceRows.stream()
                .filter(row -> evaluateWhere(rootQuery, select.where(), row, runtimeValues))
                .sorted(rootComparator(rootQuery, select.orderBy(), runtimeValues))
                .limit(select.limit() == null ? Long.MAX_VALUE : select.limit())
                .map(row -> materializeRootRow(rootQuery, row))
                .toList();
        return List.copyOf(rows);
    }

    public static List<Map<String, Object>> windowRelationRows(
            MaterializedRelationQuery relation,
            List<Map<String, Object>> rootRows,
            List<Map<String, Object>> sourceRows,
            Map<Object, Map<String, Object>> runtimeValuesByParentKey
    ) {
        return windowRelationRows(relation, relation.name(), rootRows, sourceRows, runtimeValuesByParentKey);
    }

    public static List<Map<String, Object>> windowRelationRows(
            MaterializedRelationQuery relation,
            String relationPath,
            List<Map<String, Object>> rootRows,
            List<Map<String, Object>> sourceRows,
            Map<Object, Map<String, Object>> runtimeValuesByParentKey
    ) {
        Objects.requireNonNull(relation, "relation");
        relationPath = requireName(relationPath, "relation path");
        Objects.requireNonNull(rootRows, "rootRows");
        Objects.requireNonNull(sourceRows, "sourceRows");
        Objects.requireNonNull(runtimeValuesByParentKey, "runtimeValuesByParentKey");
        QueryTemplatePlan.RelationCursorWindow cursorWindow = relation.cursorWindow();
        if (cursorWindow == null) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime relation window requires cursor-window metadata.");
        }
        if (relation.orderKeys().isEmpty()
                || !relation.orderKeys().getFirst().column().equals(cursorWindow.cursorColumn())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime relation cursor column must match the leading relation order key.");
        }
        if (relation.orderKeys().stream().noneMatch(order -> order.column().equals(cursorWindow.identityColumn()))) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime relation cursor window order must include "
                            + "the stable relation identity key.");
        }

        CollectedParentKeys parentKeys = collectParentKeys(relation, rootRows);
        LinkedHashMap<Object, ArrayList<Map<String, Object>>> grouped = new LinkedHashMap<>();
        String childKey = relation.childPredicateKey().name();
        for (Map<String, Object> sourceRow : sourceRows) {
            if (!sourceRow.containsKey(childKey)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 row materialization runtime relation child key '" + childKey
                                + "' is missing for relation-window execution.");
            }
            Object parentKey = sourceRow.get(childKey);
            grouped.computeIfAbsent(parentKey, ignored -> new ArrayList<>()).add(sourceRow);
        }
        for (Object childParentKey : grouped.keySet()) {
            if (!parentKeys.queryKeys().contains(childParentKey)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 row materialization runtime relation '" + relation.name()
                                + "' at path '" + relationPath
                                + "' row was not requested by the materialized parent-key carrier.");
            }
        }

        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (Object parentKey : parentKeys.queryKeys()) {
            List<Map<String, Object>> group = grouped.getOrDefault(parentKey, new ArrayList<>());
            Map<String, Object> runtimeValues = runtimeValuesByParentKey.getOrDefault(parentKey, Map.of());
            rows.addAll(group.stream()
                    .filter(row -> evaluateRelationCursor(relation, row, runtimeValues))
                    .sorted(relationComparator(relation))
                    .limit(cursorWindow.limit())
                    .toList());
        }
        return List.copyOf(rows);
    }

    private static MaterializedRootQuery rootSelect(
            RowMaterializationPlan rows,
            String parameterName,
            List<RowMaterializationPlan.RowKey> relationParentKeys
    ) {
        RowQueryColumns columns = rowQueryColumns(rows.root(), rows.rootFields(), rows.rootKey(), relationParentKeys);
        return new MaterializedRootQuery(
                rows.rootKey(),
                columns.fields(),
                columns.hiddenKeyProjections(),
                Optional.empty(),
                new SelectSql(
                        columns.columns(),
                        tableName(rows.root()),
                        List.of(),
                        predicate(rows.root(), rows.rootKey(), parameterName, rows.rootPredicates()),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        List.of()));
    }

    private static boolean evaluateWhere(
            MaterializedRootQuery rootQuery,
            ExpressionNode expression,
            Map<String, Object> row,
            Map<String, Object> runtimeValues
    ) {
        if (expression == null) {
            return true;
        }
        Object value = evaluate(rootQuery, expression, row, runtimeValues);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return false;
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime root-list predicate must evaluate to boolean.");
    }

    private static Comparator<Map<String, Object>> rootComparator(
            MaterializedRootQuery rootQuery,
            List<OrderBySpec> orderBy,
            Map<String, Object> runtimeValues
    ) {
        Comparator<Map<String, Object>> comparator = (left, right) -> 0;
        for (OrderBySpec order : orderBy) {
            Comparator<Map<String, Object>> next = (left, right) -> {
                Object leftValue = evaluate(rootQuery, order.expression(), left, runtimeValues);
                Object rightValue = evaluate(rootQuery, order.expression(), right, runtimeValues);
                int compared = compareNonNullValues(leftValue, rightValue);
                return order.direction() == SortDirection.DESC ? -compared : compared;
            };
            comparator = comparator.thenComparing(next);
        }
        return comparator;
    }

    private static Map<String, Object> materializeRootRow(
            MaterializedRootQuery rootQuery,
            Map<String, Object> sourceRow
    ) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (RowFieldProjection field : rootQuery.fields()) {
            row.put(field.alias(), valueForProjection(rootQuery, field, sourceRow));
        }
        for (RowFieldProjection hiddenKey : rootQuery.hiddenKeyProjections()) {
            row.put(hiddenKey.alias(), valueForProjection(rootQuery, hiddenKey, sourceRow));
        }
        return Collections.unmodifiableMap(row);
    }

    private static Object evaluate(
            MaterializedRootQuery rootQuery,
            ExpressionNode expression,
            Map<String, Object> row,
            Map<String, Object> runtimeValues
    ) {
        if (expression instanceof ColumnRefExpression column) {
            return valueForColumn(rootQuery, column.table(), column.column(), row);
        }
        if (expression instanceof VariableRefExpression variable) {
            if (!runtimeValues.containsKey(variable.name())) {
                throw new IllegalArgumentException(
                        "TITAN-E001 row materialization runtime value '" + variable.name()
                                + "' is missing for root-list window execution.");
            }
            return runtimeValues.get(variable.name());
        }
        if (expression instanceof LiteralExpression literal) {
            return literal.value();
        }
        if (expression instanceof FunctionCallExpression function) {
            return evaluateFunction(rootQuery, function, row, runtimeValues);
        }
        if (expression instanceof BinaryOpExpression binary) {
            return evaluateBinary(rootQuery, binary, row, runtimeValues);
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime root-list window expression is unsupported: "
                        + expression.getClass().getSimpleName());
    }

    private static Object evaluateFunction(
            MaterializedRootQuery rootQuery,
            FunctionCallExpression function,
            Map<String, Object> row,
            Map<String, Object> runtimeValues
    ) {
        if ("CHAR_LENGTH".equals(function.name()) && function.arguments().size() == 1) {
            Object value = evaluate(rootQuery, function.arguments().getFirst(), row, runtimeValues);
            return value == null ? null : String.valueOf(value).length();
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime root-list function is unsupported: " + function.name());
    }

    private static Object evaluateBinary(
            MaterializedRootQuery rootQuery,
            BinaryOpExpression binary,
            Map<String, Object> row,
            Map<String, Object> runtimeValues
    ) {
        if (binary.operator() == BinaryOperator.AND || binary.operator() == BinaryOperator.OR) {
            Boolean left = requireSqlBoolean(evaluate(rootQuery, binary.left(), row, runtimeValues), binary.operator());
            Boolean right = requireSqlBoolean(evaluate(rootQuery, binary.right(), row, runtimeValues), binary.operator());
            if (binary.operator() == BinaryOperator.AND) {
                if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) {
                    return false;
                }
                return (left == null || right == null) ? null : true;
            }
            if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) {
                return true;
            }
            return (left == null || right == null) ? null : false;
        }
        Object left = evaluate(rootQuery, binary.left(), row, runtimeValues);
        Object right = evaluate(rootQuery, binary.right(), row, runtimeValues);
        if (left == null || right == null) {
            return null;
        }
        return switch (binary.operator()) {
            case EQUAL -> Objects.equals(left, right);
            case NOT_EQUAL -> !Objects.equals(left, right);
            case LESS_THAN -> compareNonNullValues(left, right) < 0;
            case LESS_THAN_OR_EQUAL -> compareNonNullValues(left, right) <= 0;
            case GREATER_THAN -> compareNonNullValues(left, right) > 0;
            case GREATER_THAN_OR_EQUAL -> compareNonNullValues(left, right) >= 0;
            default -> throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime root-list window operator is unsupported: "
                            + binary.operator());
        };
    }

    private static Boolean requireSqlBoolean(Object value, BinaryOperator operator) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return null;
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime operator " + operator
                        + " requires boolean operands.");
    }

    private static boolean evaluateRelationCursor(
            MaterializedRelationQuery relation,
            Map<String, Object> row,
            Map<String, Object> runtimeValues
    ) {
        QueryTemplatePlan.RelationCursorWindow cursorWindow = relation.cursorWindow();
        if (!runtimeValues.containsKey(cursorWindow.cursorParameter().name())) {
            return true;
        }
        if (!runtimeValues.containsKey(cursorWindow.identityParameter().name())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime value '"
                            + cursorWindow.identityParameter().name()
                            + "' is missing for relation-window execution.");
        }
        Object cursorValue = runtimeValues.get(cursorWindow.cursorParameter().name());
        Object identityValue = runtimeValues.get(cursorWindow.identityParameter().name());
        if (cursorValue == null) {
            return true;
        }
        if (identityValue == null) {
            return false;
        }
        Object rowCursorValue = relationRowValue(relation, cursorWindow.cursorColumn(), row);
        Object rowIdentityValue = relationRowValue(relation, cursorWindow.identityColumn(), row);
        if (rowCursorValue == null || rowIdentityValue == null) {
            return false;
        }
        int cursorCompare = compareNonNullValues(rowCursorValue, cursorValue);
        if (cursorCompare != 0) {
            return cursorWindow.operator() == QueryTemplatePlan.PredicateOperator.GT
                    ? cursorCompare > 0
                    : cursorCompare < 0;
        }
        int identityCompare = compareNonNullValues(rowIdentityValue, identityValue);
        SortDirection identityDirection = relation.orderKeys().stream()
                .filter(orderKey -> orderKey.column().equals(cursorWindow.identityColumn()))
                .findFirst()
                .map(QueryTemplatePlan.OrderKey::direction)
                .orElse(SortDirection.ASC);
        return identityDirection == SortDirection.ASC
                ? identityCompare > 0
                : identityCompare < 0;
    }

    private static Comparator<Map<String, Object>> relationComparator(MaterializedRelationQuery relation) {
        Comparator<Map<String, Object>> comparator = (left, right) -> 0;
        for (QueryTemplatePlan.OrderKey orderKey : relation.orderKeys()) {
            Comparator<Map<String, Object>> next = (left, right) -> {
                Object leftValue = relationRowValue(relation, orderKey.column(), left);
                Object rightValue = relationRowValue(relation, orderKey.column(), right);
                int compared = compareNonNullValues(leftValue, rightValue);
                return orderKey.direction() == SortDirection.DESC ? -compared : compared;
            };
            comparator = comparator.thenComparing(next);
        }
        return comparator;
    }

    private static Object relationRowValue(
            MaterializedRelationQuery relation,
            QueryTemplatePlan.ColumnRef column,
            Map<String, Object> row
    ) {
        if (row.containsKey(column.tableAlias() + "." + column.column().value())) {
            return row.get(column.tableAlias() + "." + column.column().value());
        }
        if (row.containsKey(column.column().value())) {
            return row.get(column.column().value());
        }
        for (RowFieldProjection field : relation.fields()) {
            if (field.column().equals(column) && row.containsKey(field.alias())) {
                return row.get(field.alias());
            }
        }
        for (RowFieldProjection hiddenKey : relation.hiddenKeyProjections()) {
            if (hiddenKey.column().equals(column) && row.containsKey(hiddenKey.alias())) {
                return row.get(hiddenKey.alias());
            }
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime relation column '"
                        + column.tableAlias() + "." + column.column().value()
                        + "' is missing from the source row.");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareNonNullValues(Object left, Object right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime root-list window cannot order nullable values.");
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime root-list window values must be comparable.");
    }

    private static Object valueForProjection(
            MaterializedRootQuery rootQuery,
            RowFieldProjection projection,
            Map<String, Object> row
    ) {
        if (row.containsKey(projection.alias())) {
            return row.get(projection.alias());
        }
        return valueForColumn(rootQuery, null, projection.column().column().value(), row);
    }

    private static Object valueForColumn(
            MaterializedRootQuery rootQuery,
            String table,
            String column,
            Map<String, Object> row
    ) {
        if (table != null && row.containsKey(table + "." + column)) {
            return row.get(table + "." + column);
        }
        if (row.containsKey(column)) {
            return row.get(column);
        }
        for (RowFieldProjection field : rootQuery.fields()) {
            if (field.column().column().value().equals(column) && row.containsKey(field.alias())) {
                return row.get(field.alias());
            }
        }
        for (RowFieldProjection hiddenKey : rootQuery.hiddenKeyProjections()) {
            if (hiddenKey.column().column().value().equals(column) && row.containsKey(hiddenKey.alias())) {
                return row.get(hiddenKey.alias());
            }
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime root-list column '" + column
                        + "' is missing from the source row.");
    }

    private static MaterializedRelationQuery relationSelect(
            RowMaterializationPlan.RelationRows relation,
            ParentKeySource parentKeySource,
            String parameterName
    ) {
        return relationSelect(relation, parentKeySource, parameterName, false, List.of());
    }

    private static MaterializedRelationQuery relationSelect(
            RowMaterializationPlan.RelationRows relation,
            ParentKeySource parentKeySource,
            String parameterName,
            boolean batched,
            List<RowMaterializationPlan.RowKey> additionalHiddenKeys
    ) {
        requireRelationCursorAlignedWithOrder(relation);
        RowQueryColumns columns = rowQueryColumns(
                relation.childTable(),
                relation.fields(),
                relation.childKey(),
                relationHiddenKeys(relation, additionalHiddenKeys));
        return new MaterializedRelationQuery(
                relation.name(),
                parentKeySource,
                new ParentKeyCarrier(parameterName, relation.parentKey().type()),
                relation.childKey(),
                columns.fields(),
                columns.hiddenKeyProjections(),
                relation.orderKeys(),
                relation.cursorWindow(),
                relation.cardinality(),
                new SelectSql(
                        columns.columns(),
                        tableName(relation.childTable()),
                        List.of(),
                        relationPredicate(relation, parameterName, batched),
                        List.of(),
                        null,
                        relationOrderBy(relation),
                        relationLimit(relation, batched),
                        null,
                        null,
                        List.of()));
    }

    private static ExpressionNode relationPredicate(
            RowMaterializationPlan.RelationRows relation,
            String parameterName,
            boolean batched
    ) {
        QueryTemplatePlan.TableRef table = relation.childTable();
        RowMaterializationPlan.RowKey predicateKey = relation.childKey();
        ExpressionNode expression;
        if (!batched) {
            expression = predicate(table, predicateKey, parameterName, List.of());
        } else {
            expression = new FunctionCallExpression(
                    "__titan_parent_key_contains",
                    List.of(new VariableRefExpression(parameterName), column(predicateKey.column(), table)),
                    null);
        }
        return relationCursorPredicate(relation, expression, batched);
    }

    private static ExpressionNode relationCursorPredicate(
            RowMaterializationPlan.RelationRows relation,
            ExpressionNode expression,
            boolean batched
    ) {
        QueryTemplatePlan.RelationCursorWindow cursorWindow = relation.cursorWindow();
        if (cursorWindow == null || batched) {
            return expression;
        }
        ExpressionNode cursorValue = new VariableRefExpression(cursorWindow.cursorParameter().name());
        ExpressionNode identityValue = new VariableRefExpression(cursorWindow.identityParameter().name());
        ExpressionNode cursorColumn = column(cursorWindow.cursorColumn(), relation.childTable());
        ExpressionNode identityColumn = column(cursorWindow.identityColumn(), relation.childTable());
        ExpressionNode cursorPredicate = new BinaryOpExpression(
                new BinaryOpExpression(cursorColumn, operator(cursorWindow.operator()), cursorValue),
                BinaryOperator.OR,
                new BinaryOpExpression(
                        new BinaryOpExpression(cursorColumn, BinaryOperator.EQUAL, cursorValue),
                        BinaryOperator.AND,
                        new BinaryOpExpression(
                                identityColumn,
                                relationIdentityTieBreakerOperator(relation),
                                identityValue)));
        ExpressionNode nullableCursorPredicate = new BinaryOpExpression(
                new IsNullExpression(cursorValue),
                BinaryOperator.OR,
                cursorPredicate);
        return expression == null
                ? nullableCursorPredicate
                : new BinaryOpExpression(expression, BinaryOperator.AND, nullableCursorPredicate);
    }

    private static Integer relationLimit(RowMaterializationPlan.RelationRows relation, boolean batched) {
        if (relation.cursorWindow() == null || batched) {
            return null;
        }
        return relation.cursorWindow().limit();
    }

    private static List<OrderBySpec> relationOrderBy(RowMaterializationPlan.RelationRows relation) {
        ArrayList<OrderBySpec> orderBy = new ArrayList<>();
        for (QueryTemplatePlan.OrderKey orderKey : relation.orderKeys()) {
            ColumnRefExpression orderColumn = column(orderKey.column(), relation.childTable());
            orderBy.add(new OrderBySpec(
                    new FunctionCallExpression("__titan_is_null", List.of(orderColumn), null),
                    SortDirection.ASC));
            orderBy.add(new OrderBySpec(orderColumn, orderKey.direction()));
        }
        return List.copyOf(orderBy);
    }

    private static void requireRelationCursorAlignedWithOrder(RowMaterializationPlan.RelationRows relation) {
        QueryTemplatePlan.RelationCursorWindow cursorWindow = relation.cursorWindow();
        if (cursorWindow == null) {
            return;
        }
        if (relation.orderKeys().isEmpty()
                || !relation.orderKeys().getFirst().column().equals(cursorWindow.cursorColumn())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime relation cursor column must match the leading relation order key.");
        }
        if (relation.orderKeys().size() < 2
                || !relation.orderKeys().get(1).column().equals(cursorWindow.identityColumn())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime relation cursor window order must include "
                            + "the stable relation identity key immediately after the cursor key.");
        }
        SortDirection direction = relation.orderKeys().getFirst().direction();
        boolean ascendingAfter = direction == SortDirection.ASC
                && cursorWindow.operator() == QueryTemplatePlan.PredicateOperator.GT;
        boolean descendingAfter = direction == SortDirection.DESC
                && cursorWindow.operator() == QueryTemplatePlan.PredicateOperator.LT;
        if (!ascendingAfter && !descendingAfter) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime relation cursor operator must match relation order direction.");
        }
    }

    private static List<RowMaterializationPlan.RowKey> relationHiddenKeys(
            RowMaterializationPlan.RelationRows relation,
            List<RowMaterializationPlan.RowKey> additionalHiddenKeys
    ) {
        ArrayList<RowMaterializationPlan.RowKey> hiddenKeys = new ArrayList<>(additionalHiddenKeys);
        if (relation.cursorWindow() != null) {
            QueryTemplatePlan.RelationCursorWindow window = relation.cursorWindow();
            hiddenKeys.add(new RowMaterializationPlan.RowKey(
                    relation.childTable().alias() + "_" + window.cursorColumn().column().value() + "_cursor",
                    window.cursorColumn(),
                    window.cursorParameter().type()));
            hiddenKeys.add(new RowMaterializationPlan.RowKey(
                    relation.childTable().alias() + "_" + window.identityColumn().column().value() + "_identity",
                    window.identityColumn(),
                    window.identityParameter().type()));
        }
        return List.copyOf(hiddenKeys);
    }

    private static MaterializedRootQuery listRootSelect(
            RowMaterializationPlan rows,
            List<RowMaterializationPlan.RowKey> relationParentKeys,
            Integer firstPageLimit
    ) {
        RowQueryColumns columns = rowQueryColumns(rows.root(), rows.rootFields(), rows.rootKey(), relationParentKeys);
        return new MaterializedRootQuery(
                rows.rootKey(),
                columns.fields(),
                columns.hiddenKeyProjections(),
                rootWindowFacts(rows, columns, firstPageLimit),
                new SelectSql(
                        columns.columns(),
                        tableName(rows.root()),
                        rootJoins(rows),
                        listPredicate(rows, firstPageLimit != null
                                || rows.cursorWindow() != null
                                || rows.expressionCursorWindow() != null),
                        List.of(),
                        null,
                        rootOrderBy(rows),
                        rootLimit(rows, firstPageLimit),
                        null,
                        null,
                        List.of()));
    }

    private record RowQueryColumns(
            List<SelectColumn> columns,
            List<RowFieldProjection> fields,
            List<RowFieldProjection> hiddenKeyProjections
    ) {
        private RowQueryColumns {
            columns = List.copyOf(columns);
            fields = List.copyOf(fields);
            hiddenKeyProjections = List.copyOf(hiddenKeyProjections);
        }
    }

    private static RowQueryColumns rowQueryColumns(
            QueryTemplatePlan.TableRef table,
            List<RowMaterializationPlan.FieldBinding> fields,
            RowMaterializationPlan.RowKey key
    ) {
        return rowQueryColumns(table, fields, key, List.of());
    }

    private static RowQueryColumns rowQueryColumns(
            QueryTemplatePlan.TableRef table,
            List<RowMaterializationPlan.FieldBinding> fields,
            RowMaterializationPlan.RowKey key,
            List<RowMaterializationPlan.RowKey> additionalHiddenKeys
    ) {
        ArrayList<SelectColumn> columns = new ArrayList<>();
        ArrayList<RowFieldProjection> visible = new ArrayList<>();
        ArrayList<RowFieldProjection> hidden = new ArrayList<>();
        ArrayList<String> aliases = new ArrayList<>();
        for (RowMaterializationPlan.FieldBinding field : fields) {
            requireUniqueAlias(aliases, field.fieldName(), "field");
            visible.add(new RowFieldProjection(field.fieldName(), field.column(), field.type(), false));
            columns.add(new SelectColumn(column(field.column(), table), field.fieldName()));
        }
        addMissingRowKey(columns, visible, hidden, aliases, key, table);
        for (RowMaterializationPlan.RowKey hiddenKey : additionalHiddenKeys) {
            addMissingRowKey(columns, visible, hidden, aliases, hiddenKey, table);
        }
        return new RowQueryColumns(columns, visible, hidden);
    }

    private static void addMissingRowKey(
            ArrayList<SelectColumn> columns,
            List<RowFieldProjection> fields,
            ArrayList<RowFieldProjection> hiddenKeyProjections,
            ArrayList<String> aliases,
            RowMaterializationPlan.RowKey key,
            QueryTemplatePlan.TableRef table
    ) {
        for (RowFieldProjection field : fields) {
            if (field.alias().equals(key.name())) {
                if (field.column().equals(key.column()) && field.type().equals(key.type())) {
                    return;
                }
                throw new IllegalArgumentException(
                        "TITAN-E001 row materialization runtime row-key alias '" + key.name()
                                + "' conflicts with projected field '" + field.alias() + "'.");
            }
        }
        for (RowFieldProjection hiddenKey : hiddenKeyProjections) {
            if (hiddenKey.alias().equals(key.name())) {
                if (hiddenKey.column().equals(key.column()) && hiddenKey.type().equals(key.type())) {
                    return;
                }
                throw new IllegalArgumentException(
                        "TITAN-E001 row materialization runtime row-key alias '" + key.name()
                                + "' conflicts with hidden key projection '" + hiddenKey.alias() + "'.");
            }
        }
        requireUniqueAlias(aliases, key.name(), "hidden row key");
        hiddenKeyProjections.add(new RowFieldProjection(key.name(), key.column(), key.type(), true));
        columns.add(new SelectColumn(column(key.column(), table), key.name()));
    }

    private static void requireUniqueAlias(List<String> aliases, String alias, String kind) {
        if (aliases.contains(alias)) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime " + kind
                            + " alias '" + alias + "' is not unique.");
        }
        aliases.add(alias);
    }

    private static ExpressionNode listPredicate(RowMaterializationPlan rows, boolean boundedWindow) {
        ExpressionNode expression = null;
        for (QueryTemplatePlan.ParameterPredicate predicate : rows.rootPredicates()) {
            ExpressionNode next = new BinaryOpExpression(
                    column(predicate.column(), rows.root()),
                    operator(predicate.operator()),
                    new VariableRefExpression(predicate.parameter().name()));
            expression = expression == null
                    ? next
                    : new BinaryOpExpression(expression, BinaryOperator.AND, next);
        }
        for (QueryTemplatePlan.ExpressionPredicate predicate : rows.rootExpressionPredicates()) {
            ExpressionNode next = new BinaryOpExpression(
                    rowValue(predicate.expression(), rows),
                    operator(predicate.operator()),
                    new VariableRefExpression(predicate.parameter().name()));
            expression = expression == null
                    ? next
                    : new BinaryOpExpression(expression, BinaryOperator.AND, next);
        }
        QueryTemplatePlan.CursorWindow cursorWindow = rows.cursorWindow();
        if (cursorWindow != null) {
            ExpressionNode next = new BinaryOpExpression(
                    column(cursorWindow.cursorColumn(), rows.root()),
                    operator(cursorWindow.operator()),
                    new VariableRefExpression(cursorWindow.cursorParameter().name()));
            expression = expression == null
                    ? next
                    : new BinaryOpExpression(expression, BinaryOperator.AND, next);
        }
        QueryTemplatePlan.ExpressionCursorWindow expressionCursorWindow = rows.expressionCursorWindow();
        if (expressionCursorWindow != null) {
            ExpressionNode orderedValue = rowValue(expressionCursorWindow.expression(), rows);
            ExpressionNode cursorValue = new VariableRefExpression(expressionCursorWindow.cursorParameter().name());
            ExpressionNode identityValue = column(rows.rootKey().column(), rows.root());
            ExpressionNode cursorIdentityValue =
                    new VariableRefExpression(expressionCursorWindow.identityParameter().name());
            BinaryOperator orderedOperator = operator(expressionCursorWindow.operator());
            BinaryOperator identityOperator = identityTieBreakerOperator(rows);
            ExpressionNode next = new BinaryOpExpression(
                    new BinaryOpExpression(orderedValue, orderedOperator, cursorValue),
                    BinaryOperator.OR,
                    new BinaryOpExpression(
                            new BinaryOpExpression(orderedValue, BinaryOperator.EQUAL, cursorValue),
                            BinaryOperator.AND,
                            new BinaryOpExpression(identityValue, identityOperator, cursorIdentityValue)));
            expression = expression == null
                    ? next
                    : new BinaryOpExpression(expression, BinaryOperator.AND, next);
        }
        if (boundedWindow) {
            for (QueryTemplatePlan.OrderKey orderKey : rows.rootOrderKeys()) {
                ExpressionNode next = new BinaryOpExpression(
                        column(orderKey.column(), rows.root()),
                        BinaryOperator.EQUAL,
                        column(orderKey.column(), rows.root()));
                expression = expression == null
                        ? next
                        : new BinaryOpExpression(expression, BinaryOperator.AND, next);
            }
            for (QueryTemplatePlan.ExpressionOrderKey orderKey : rows.rootExpressionOrderKeys()) {
                ExpressionNode orderExpression = rowValue(orderKey.expression(), rows);
                ExpressionNode next = new BinaryOpExpression(
                        orderExpression,
                        BinaryOperator.EQUAL,
                        orderExpression);
                expression = expression == null
                        ? next
                        : new BinaryOpExpression(expression, BinaryOperator.AND, next);
            }
        }
        return expression;
    }

    private static void requireCursorAlignedWithRootOrder(RowMaterializationPlan rows) {
        QueryTemplatePlan.CursorWindow cursorWindow = rows.cursorWindow();
        if (cursorWindow == null && rows.expressionCursorWindow() != null) {
            requireExpressionCursorAlignedWithRootOrder(rows);
            return;
        }
        QueryTemplatePlan.OrderKey firstOrderKey = rows.rootOrderKeys().getFirst();
        if (!firstOrderKey.column().equals(cursorWindow.cursorColumn())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime cursor column must match the leading root order key.");
        }
        if (!rows.rootKey().column().equals(cursorWindow.cursorColumn())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime cursor column must match the stable root identity key.");
        }
        boolean ascendingAfter = firstOrderKey.direction() == SortDirection.ASC
                && cursorWindow.operator() == QueryTemplatePlan.PredicateOperator.GT;
        boolean descendingAfter = firstOrderKey.direction() == SortDirection.DESC
                && cursorWindow.operator() == QueryTemplatePlan.PredicateOperator.LT;
        if (!ascendingAfter && !descendingAfter) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime cursor operator must match root order direction.");
        }
    }

    private static void requireExpressionCursorAlignedWithRootOrder(RowMaterializationPlan rows) {
        QueryTemplatePlan.ExpressionCursorWindow cursorWindow = rows.expressionCursorWindow();
        if (rows.rootExpressionOrderKeys().isEmpty()
                || !rows.rootExpressionOrderKeys().getFirst().expression().equals(cursorWindow.expression())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime expression cursor must match the leading root order key.");
        }
        SortDirection direction = rows.rootExpressionOrderKeys().getFirst().direction();
        boolean ascendingAfter = direction == SortDirection.ASC
                && cursorWindow.operator() == QueryTemplatePlan.PredicateOperator.GT;
        boolean descendingAfter = direction == SortDirection.DESC
                && cursorWindow.operator() == QueryTemplatePlan.PredicateOperator.LT;
        if (!ascendingAfter && !descendingAfter) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime cursor operator must match root expression order direction.");
        }
    }

    private static void requireRootIdentityInOrder(RowMaterializationPlan rows) {
        boolean hasIdentityOrder = rows.rootOrderKeys().stream()
                .anyMatch(orderKey -> orderKey.column().equals(rows.rootKey().column()));
        if (!hasIdentityOrder) {
            throw new IllegalArgumentException(
                    "TITAN-E001 row materialization runtime bounded list-root window order must include "
                            + "the stable root identity key.");
        }
    }

    private static List<JoinSpec> rootJoins(RowMaterializationPlan rows) {
        ArrayList<JoinSpec> joins = new ArrayList<>();
        for (QueryTemplatePlan.RootJoin join : rows.rootJoins()) {
            joins.add(new JoinSpec(
                    JoinType.INNER,
                    tableName(join.to()),
                    new BinaryOpExpression(
                            column(join.fromColumn(), join.from()),
                            BinaryOperator.EQUAL,
                            column(join.toColumn(), join.to()))));
        }
        return List.copyOf(joins);
    }

    private static List<OrderBySpec> rootOrderBy(RowMaterializationPlan rows) {
        ArrayList<OrderBySpec> orderBy = new ArrayList<>();
        for (QueryTemplatePlan.ExpressionOrderKey orderKey : rows.rootExpressionOrderKeys()) {
            orderBy.add(new OrderBySpec(rowValue(orderKey.expression(), rows), orderKey.direction()));
        }
        for (QueryTemplatePlan.OrderKey orderKey : rows.rootOrderKeys()) {
            orderBy.add(new OrderBySpec(column(orderKey.column(), rows.root()), orderKey.direction()));
        }
        return List.copyOf(orderBy);
    }

    private static Integer rootLimit(RowMaterializationPlan rows, Integer firstPageLimit) {
        if (rows.expressionCursorWindow() != null) {
            return rows.expressionCursorWindow().limit();
        }
        return rows.cursorWindow() == null ? firstPageLimit : Integer.valueOf(rows.cursorWindow().limit());
    }

    private static Optional<RootWindowFacts> rootWindowFacts(
            RowMaterializationPlan rows,
            RowQueryColumns columns,
            Integer firstPageLimit
    ) {
        Integer limit = rootLimit(rows, firstPageLimit);
        if (limit == null) {
            return Optional.empty();
        }
        Optional<String> cursorAlias = Optional.empty();
        Optional<String> cursorParameter = Optional.empty();
        if (rows.expressionCursorWindow() != null) {
            cursorParameter = Optional.of(rows.expressionCursorWindow().cursorParameter().name());
        } else if (rows.cursorWindow() != null) {
            QueryTemplatePlan.CursorWindow cursorWindow = rows.cursorWindow();
            cursorAlias = rowAlias(columns, cursorWindow.cursorColumn());
            cursorParameter = Optional.of(cursorWindow.cursorParameter().name());
        } else if (!rows.rootOrderKeys().isEmpty()) {
            cursorAlias = rowAlias(columns, rows.rootOrderKeys().getFirst().column());
        }
        return Optional.of(new RootWindowFacts(
                limit,
                cursorAlias,
                rows.rootKey().name(),
                cursorParameter));
    }

    private static Optional<String> rowAlias(RowQueryColumns columns, QueryTemplatePlan.ColumnRef column) {
        for (RowFieldProjection field : columns.fields()) {
            if (field.column().equals(column)) {
                return Optional.of(field.alias());
            }
        }
        for (RowFieldProjection hiddenKey : columns.hiddenKeyProjections()) {
            if (hiddenKey.column().equals(column)) {
                return Optional.of(hiddenKey.alias());
            }
        }
        return Optional.empty();
    }

    private static ExpressionNode rowValue(QueryTemplatePlan.RowValue value, RowMaterializationPlan rows) {
        if (value instanceof QueryTemplatePlan.ColumnValue columnValue) {
            return column(columnValue.column(), tableForAlias(rows, columnValue.column().tableAlias()));
        }
        if (value instanceof QueryTemplatePlan.FunctionValue functionValue) {
            return new FunctionCallExpression(
                    functionValue.name(),
                    functionValue.arguments().stream()
                            .map(argument -> rowValue(argument, rows))
                            .toList(),
                    null);
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime root expression value is unsupported");
    }

    private static QueryTemplatePlan.TableRef tableForAlias(RowMaterializationPlan rows, String alias) {
        if (rows.root().alias().equals(alias)) {
            return rows.root();
        }
        for (QueryTemplatePlan.RootJoin join : rows.rootJoins()) {
            if (join.to().alias().equals(alias)) {
                return join.to();
            }
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime root expression alias '" + alias + "' is not declared");
    }

    private static BinaryOperator identityTieBreakerOperator(RowMaterializationPlan rows) {
        for (QueryTemplatePlan.OrderKey orderKey : rows.rootOrderKeys()) {
            if (orderKey.column().equals(rows.rootKey().column())) {
                return orderKey.direction() == SortDirection.ASC
                        ? BinaryOperator.GREATER_THAN
                        : BinaryOperator.LESS_THAN;
            }
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime expression cursor requires stable root identity ordering.");
    }

    private static BinaryOperator relationIdentityTieBreakerOperator(RowMaterializationPlan.RelationRows relation) {
        QueryTemplatePlan.RelationCursorWindow cursorWindow = relation.cursorWindow();
        for (QueryTemplatePlan.OrderKey orderKey : relation.orderKeys()) {
            if (orderKey.column().equals(cursorWindow.identityColumn())) {
                return orderKey.direction() == SortDirection.ASC
                        ? BinaryOperator.GREATER_THAN
                        : BinaryOperator.LESS_THAN;
            }
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime relation cursor requires stable identity ordering.");
    }

    private static ExpressionNode predicate(
            QueryTemplatePlan.TableRef table,
            RowMaterializationPlan.RowKey predicateKey,
            String parameterName,
            List<QueryTemplatePlan.ParameterPredicate> predicates
    ) {
        ExpressionNode expression = new BinaryOpExpression(
                column(predicateKey.column(), table),
                BinaryOperator.EQUAL,
                new VariableRefExpression(parameterName));
        for (QueryTemplatePlan.ParameterPredicate predicate : predicates) {
            if (!predicate.column().tableAlias().equals(table.alias())) {
                throw new IllegalArgumentException("TITAN-E001 row materialization runtime predicate alias '"
                        + predicate.column().tableAlias() + "' must currently refer to root alias '"
                        + table.alias() + "'");
            }
            if (samePredicate(predicate, predicateKey, parameterName)) {
                continue;
            }
            expression = new BinaryOpExpression(
                    expression,
                    BinaryOperator.AND,
                    new BinaryOpExpression(
                            column(predicate.column(), table),
                            operator(predicate.operator()),
                            new VariableRefExpression(predicate.parameter().name())));
        }
        return expression;
    }

    private static BinaryOperator operator(QueryTemplatePlan.PredicateOperator operator) {
        return switch (operator) {
            case EQ -> BinaryOperator.EQUAL;
            case NE -> BinaryOperator.NOT_EQUAL;
            case LT -> BinaryOperator.LESS_THAN;
            case LTE -> BinaryOperator.LESS_THAN_OR_EQUAL;
            case GT -> BinaryOperator.GREATER_THAN;
            case GTE -> BinaryOperator.GREATER_THAN_OR_EQUAL;
        };
    }

    private static boolean samePredicate(
            QueryTemplatePlan.ParameterPredicate predicate,
            RowMaterializationPlan.RowKey predicateKey,
            String parameterName
    ) {
        return predicate.operator() == QueryTemplatePlan.PredicateOperator.EQ
                && predicate.column().equals(predicateKey.column())
                && predicate.parameter().name().equals(parameterName)
                && predicate.parameter().type().equals(predicateKey.type());
    }

    private static ColumnRefExpression column(
            QueryTemplatePlan.ColumnRef column,
            QueryTemplatePlan.TableRef table
    ) {
        return new ColumnRefExpression(tableName(table), column.column().value());
    }

    private static String tableName(QueryTemplatePlan.TableRef table) {
        if (table.schema() == null) {
            return table.table().value();
        }
        return table.schema().value() + "." + table.table().value();
    }

    private static boolean sameKeyColumn(
            RowMaterializationPlan.RowKey left,
            RowMaterializationPlan.RowKey right
    ) {
        return left.column().equals(right.column())
                && left.type().equals(right.type());
    }

    private static List<RowMaterializationPlan.RowKey> rootRelationParentKeys(RowMaterializationPlan rows) {
        return relationParentKeys(rows, rows.root().alias());
    }

    private static List<RowMaterializationPlan.RowKey> relationParentKeys(
            RowMaterializationPlan rows,
            String parentAlias
    ) {
        ArrayList<RowMaterializationPlan.RowKey> parentKeys = new ArrayList<>();
        for (RowMaterializationPlan.RelationRows relation : rows.relations()) {
            if (relation.parentKey().column().tableAlias().equals(parentAlias)
                    && !(parentAlias.equals(rows.root().alias()) && sameKeyColumn(rows.rootKey(), relation.parentKey()))
                    && !parentKeys.contains(relation.parentKey())) {
                parentKeys.add(relation.parentKey());
            }
        }
        return parentKeys;
    }

    private static ParentKeySource parentKeySource(
            RowMaterializationPlan rows,
            RowMaterializationPlan.RelationRows relation,
            MaterializedRootQuery rootQuery,
            List<MaterializedRelationQuery> earlierRelationQueries
    ) {
        if (!relation.parentKey().column().tableAlias().equals(rows.root().alias())) {
            return relationParentKeySource(relation, earlierRelationQueries);
        }
        if (sameKeyColumn(rows.rootKey(), relation.parentKey())) {
            return new ParentKeySource(rows.rootKey(), ParentKeySource.Kind.ROOT_IDENTITY);
        }
        for (RowFieldProjection field : rootQuery.fields()) {
            if (field.alias().equals(relation.parentKey().name())
                    && field.column().equals(relation.parentKey().column())
                    && field.type().equals(relation.parentKey().type())) {
                return new ParentKeySource(relation.parentKey(), ParentKeySource.Kind.ROOT_FIELD);
            }
        }
        for (RowFieldProjection hiddenKey : rootQuery.hiddenKeyProjections()) {
            if (hiddenKey.column().equals(relation.parentKey().column())
                    && hiddenKey.type().equals(relation.parentKey().type())) {
                return new ParentKeySource(relation.parentKey(), ParentKeySource.Kind.ROOT_HIDDEN_KEY);
            }
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime parent-key projection '"
                        + relation.parentKey().name() + "' is missing for relation '"
                        + relation.name() + "'.");
    }

    private static ParentKeySource relationParentKeySource(
            RowMaterializationPlan.RelationRows relation,
            List<MaterializedRelationQuery> earlierRelationQueries
    ) {
        String parentAlias = relation.parentKey().column().tableAlias();
        for (MaterializedRelationQuery earlier : earlierRelationQueries) {
            if (!earlier.childPredicateKey().column().tableAlias().equals(parentAlias)) {
                continue;
            }
            for (RowFieldProjection field : earlier.fields()) {
                if (field.alias().equals(relation.parentKey().name())
                        && field.column().equals(relation.parentKey().column())
                        && field.type().equals(relation.parentKey().type())) {
                    return new ParentKeySource(relation.parentKey(), ParentKeySource.Kind.RELATION_FIELD);
                }
            }
            for (RowFieldProjection hiddenKey : earlier.hiddenKeyProjections()) {
                if (hiddenKey.column().equals(relation.parentKey().column())
                        && hiddenKey.type().equals(relation.parentKey().type())) {
                    return new ParentKeySource(relation.parentKey(), ParentKeySource.Kind.RELATION_HIDDEN_KEY);
                }
            }
        }
        throw new IllegalArgumentException(
                "TITAN-E001 row materialization runtime parent-key projection '"
                        + relation.parentKey().name() + "' is missing for nested relation '"
                        + relation.name() + "'.");
    }

    private static String carrierParameterName(RowMaterializationPlan.RelationRows relation) {
        return "p_" + relation.name() + "_parent_key";
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Row materialization runtime " + field + " must not be blank");
        }
        return value;
    }
}
