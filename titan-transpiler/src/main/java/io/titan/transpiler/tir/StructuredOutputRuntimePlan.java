package io.titan.transpiler.tir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record StructuredOutputRuntimePlan(
        StructuredOutputPlan output,
        RowMaterializationRuntimePlan rows
) {
    public StructuredOutputRuntimePlan {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rows, "rows");
    }

    public static StructuredOutputRuntimePlan from(
            StructuredOutputPlan output,
            RowMaterializationRuntimePlan rows
    ) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rows, "rows");
        Map<RowMaterializationRuntimePlan.MaterializedRelationQuery, String> relationPaths =
                relationPaths(rows.relationQueries());
        if (rows.relationQueries().size() > 2) {
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation = rows.relationQueries().get(2);
            throw new IllegalArgumentException(
                    "TITAN-E001 structured output runtime supports one nested relation graph level, but relation '"
                            + relation.name() + "' at path '" + relationPath(relationPaths, relation)
                            + "' requires recursive relation graph assembly.");
        }
        if (rows.relationQueries().size() == 2) {
            RowMaterializationRuntimePlan.MaterializedRelationQuery child = rows.relationQueries().getFirst();
            RowMaterializationRuntimePlan.MaterializedRelationQuery grandchild = rows.relationQueries().get(1);
            if (grandchild.parentKeySource().kind() != RowMaterializationRuntimePlan.ParentKeySource.Kind.RELATION_FIELD
                    && grandchild.parentKeySource().kind()
                    != RowMaterializationRuntimePlan.ParentKeySource.Kind.RELATION_HIDDEN_KEY) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime nested relation '" + grandchild.name()
                                + "' at path '" + relationPath(relationPaths, grandchild)
                                + "' must collect parent keys from relation '" + child.name() + "'.");
            }
        }
        return new StructuredOutputRuntimePlan(output, rows);
    }

    public Map<String, Object> assemble(
            Map<String, ?> rootRow,
            List<? extends Map<String, ?>> relationRows
    ) {
        Objects.requireNonNull(rootRow, "rootRow");
        Objects.requireNonNull(relationRows, "relationRows");
        if (rows.relationQueries().size() > 1) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured output runtime nested relation graphs must use assembleNestedBatch.");
        }
        if (rows.relationQueries().isEmpty()) {
            if (!relationRows.isEmpty()) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime received relation rows for a root-only output plan.");
            }
        } else {
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation = rows.relationQueries().getFirst();
            StructuredOutputPlan.ObjectValue child = relationOutput(output.root(), relation.name());
            validateGroupedRowsBelongToRoot(rootRow, relationRows, relation, child);
        }
        return assembleObject(output.root(), rootRow, relationRows);
    }

    public List<Map<String, Object>> assembleBatch(
            List<? extends Map<String, ?>> rootRows,
            List<? extends Map<String, ?>> relationRows
    ) {
        Objects.requireNonNull(rootRows, "rootRows");
        Objects.requireNonNull(relationRows, "relationRows");
        if (rows.relationQueries().size() > 1) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured output runtime nested relation graphs must use assembleNestedBatch.");
        }
        if (rows.relationQueries().isEmpty()) {
            if (!relationRows.isEmpty()) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime received relation rows for a root-only output plan.");
            }
            return rootRows.stream()
                    .map(rootRow -> assembleObject(output.root(), rootRow, List.of()))
                    .toList();
        }
        RowMaterializationRuntimePlan.MaterializedRelationQuery relation = rows.relationQueries().getFirst();
        StructuredOutputPlan.ObjectValue child = relationOutput(output.root(), relation.name());
        RowMaterializationRuntimePlan.CollectedParentKeys parentKeys =
                RowMaterializationRuntimePlan.collectParentKeys(relation, castRows(rootRows));
        Map<Object, ArrayList<Map<String, ?>>> grouped = groupRelationRows(relationRows, relation, child);
        rejectUnrequestedRows(grouped, parentKeys, relation);
        ArrayList<Map<String, Object>> assembled = new ArrayList<>();
        for (int index = 0; index < rootRows.size(); index++) {
            Object parentKey = parentKeys.rowKeys().get(index);
            List<Map<String, ?>> childRows = grouped.getOrDefault(parentKey, new ArrayList<>());
            assembled.add(assemble(rootRows.get(index), childRows));
        }
        return List.copyOf(assembled);
    }

    public List<Map<String, Object>> assembleNestedBatch(
            List<? extends Map<String, ?>> rootRows,
            Map<String, ? extends List<? extends Map<String, ?>>> relationRowsByName
    ) {
        Objects.requireNonNull(rootRows, "rootRows");
        Objects.requireNonNull(relationRowsByName, "relationRowsByName");
        if (rows.relationQueries().size() != 2) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured output runtime nested batch assembly requires exactly two relation levels.");
        }
        RowMaterializationRuntimePlan.MaterializedRelationQuery childRelation = rows.relationQueries().getFirst();
        RowMaterializationRuntimePlan.MaterializedRelationQuery grandchildRelation = rows.relationQueries().get(1);
        List<? extends Map<String, ?>> childRows = relationRows(relationRowsByName, childRelation.name());
        List<? extends Map<String, ?>> grandchildRows =
                relationRows(relationRowsByName, grandchildRelation.name());

        StructuredOutputPlan.ObjectValue childOutput = relationOutput(output.root(), childRelation.name());
        StructuredOutputPlan.ObjectValue grandchildOutput = relationOutput(childOutput, grandchildRelation.name());
        RowMaterializationRuntimePlan.CollectedParentKeys rootParentKeys =
                RowMaterializationRuntimePlan.collectParentKeys(childRelation, castRows(rootRows));
        Map<Object, ArrayList<Map<String, ?>>> groupedChildren =
                groupRelationRows(childRows, childRelation, childOutput);
        rejectUnrequestedRows(groupedChildren, rootParentKeys, childRelation);

        RowMaterializationRuntimePlan.CollectedParentKeys childParentKeys =
                RowMaterializationRuntimePlan.collectParentKeys(grandchildRelation, castRows(childRows));
        Map<Object, ArrayList<Map<String, ?>>> groupedGrandchildren =
                groupRelationRows(grandchildRows, grandchildRelation, grandchildOutput);
        rejectUnrequestedRows(groupedGrandchildren, childParentKeys, grandchildRelation);

        ArrayList<Map<String, Object>> assembled = new ArrayList<>();
        for (int index = 0; index < rootRows.size(); index++) {
            Object parentKey = rootParentKeys.rowKeys().get(index);
            List<Map<String, ?>> children = groupedChildren.getOrDefault(parentKey, new ArrayList<>());
            assembled.add(assembleNestedRoot(
                    rootRows.get(index),
                    children,
                    childRelation,
                    childOutput,
                    grandchildRelation,
                    grandchildOutput,
                    groupedGrandchildren));
        }
        return List.copyOf(assembled);
    }

    private Map<String, Object> assembleObject(
            StructuredOutputPlan.ObjectValue object,
            Map<String, ?> rootRow,
            List<? extends Map<String, ?>> relationRows
    ) {
        LinkedHashMap<String, Object> assembled = new LinkedHashMap<>();
        for (StructuredOutputPlan.Entry entry : object.entries()) {
            assembled.put(entry.key().value(), assembleValue(entry.key().value(), entry.value(), rootRow, relationRows));
        }
        return assembled;
    }

    private Object assembleValue(
            String outputKey,
            StructuredOutputPlan.Value value,
            Map<String, ?> rootRow,
            List<? extends Map<String, ?>> relationRows
    ) {
        if (value instanceof StructuredOutputPlan.ScalarValue scalar) {
            return rootRow.get(scalar.fieldName());
        }
        if (value instanceof StructuredOutputPlan.NullValue) {
            return null;
        }
        if (value instanceof StructuredOutputPlan.JsonNullValue) {
            return null;
        }
        if (value instanceof StructuredOutputPlan.TextLiteralValue literal) {
            return literal.value();
        }
        if (value instanceof StructuredOutputPlan.IntegerLiteralValue literal) {
            return literal.value();
        }
        if (value instanceof StructuredOutputPlan.BooleanLiteralValue literal) {
            return literal.value();
        }
        if (value instanceof StructuredOutputPlan.ArrayLiteralValue array) {
            ArrayList<Object> elements = new ArrayList<>();
            for (StructuredOutputPlan.Value element : array.elements()) {
                elements.add(assembleValue(outputKey, element, rootRow, relationRows));
            }
            return Collections.unmodifiableList(elements);
        }
        if (value instanceof StructuredOutputPlan.ArrayValue array) {
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation = requireRelation();
            requireRelationKey(outputKey, relation);
            if (relation.cardinality() != QueryTemplatePlan.RelationCardinality.MANY) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime relation '" + relation.name()
                                + "' is not a many relation and cannot assemble as an array.");
            }
            StructuredOutputPlan.ObjectValue child = requireRelationObject(array.element(), relation.name());
            ArrayList<Map<String, Object>> children = new ArrayList<>();
            for (Map<String, ?> relationRow : relationRows) {
                children.add(assembleRelationObject(child, relationRow));
            }
            return List.copyOf(children);
        }
        if (value instanceof StructuredOutputPlan.ObjectValue child) {
            if (rows.relationQueries().isEmpty()) {
                return assembleObject(child, rootRow, relationRows);
            }
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation = requireRelation();
            if (!outputKey.equals(relation.name())) {
                return assembleObject(child, rootRow, relationRows);
            }
            return switch (relation.cardinality()) {
                case ZERO_OR_ONE -> switch (relationRows.size()) {
                    case 0 -> null;
                    case 1 -> assembleRelationObject(child, relationRows.getFirst());
                    default -> throw relationCardinalityViolation(relation, relationRows.size());
                };
                case ONE -> {
                    if (relationRows.size() != 1) {
                        throw relationCardinalityViolation(relation, relationRows.size());
                    }
                    yield assembleRelationObject(child, relationRows.getFirst());
                }
                case MANY -> throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime relation '" + relation.name()
                                + "' is a many relation and must assemble as an array.");
            };
        }
        throw new IllegalArgumentException(
                "TITAN-E001 structured output runtime value shape is not supported for grouped assembly.");
    }

    private Map<String, Object> assembleRelationObject(
            StructuredOutputPlan.ObjectValue object,
            Map<String, ?> relationRow
    ) {
        LinkedHashMap<String, Object> assembled = new LinkedHashMap<>();
        for (StructuredOutputPlan.Entry entry : object.entries()) {
            if (entry.value() instanceof StructuredOutputPlan.ScalarValue scalar) {
                assembled.put(entry.key().value(), relationRow.get(scalar.fieldName()));
            } else if (entry.value() instanceof StructuredOutputPlan.NullValue
                    || entry.value() instanceof StructuredOutputPlan.JsonNullValue) {
                assembled.put(entry.key().value(), null);
            } else if (entry.value() instanceof StructuredOutputPlan.TextLiteralValue literal) {
                assembled.put(entry.key().value(), literal.value());
            } else if (entry.value() instanceof StructuredOutputPlan.IntegerLiteralValue literal) {
                assembled.put(entry.key().value(), literal.value());
            } else if (entry.value() instanceof StructuredOutputPlan.BooleanLiteralValue literal) {
                assembled.put(entry.key().value(), literal.value());
            } else {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime relation objects support scalar, literal, and null fields only.");
            }
        }
        return assembled;
    }

    private void validateGroupedRowsBelongToRoot(
            Map<String, ?> rootRow,
            List<? extends Map<String, ?>> relationRows,
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation,
            StructuredOutputPlan.ObjectValue child
    ) {
        if (relationRows.isEmpty()) {
            return;
        }
        Object parentValue = rowKeyValue(rootRow, relation.parentKeySource().key(), output.root());
        for (Map<String, ?> relationRow : relationRows) {
            Object childParentValue = rowKeyValue(relationRow, relation.childKey(), child);
            if (!Objects.equals(parentValue, childParentValue)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime relation '" + relation.name()
                                + "' at path '" + relationPath(relation)
                                + "' row does not match the materialized root parent key.");
            }
        }
    }

    private Object rowKeyValue(
            Map<String, ?> row,
            RowMaterializationPlan.RowKey key,
            StructuredOutputPlan.ObjectValue object
    ) {
        if (row.containsKey(key.name())) {
            return row.get(key.name());
        }
        Optional<Object> nestedValue = rowKeyValueFromOutput(row, key, object);
        if (nestedValue.isPresent()) {
            return nestedValue.get();
        }
        throw new IllegalArgumentException(
                "TITAN-E001 structured output runtime row key '" + key.name()
                        + "' must be present in the materialized row for grouped assembly.");
    }

    private Optional<Object> rowKeyValueFromOutput(
            Map<String, ?> row,
            RowMaterializationPlan.RowKey key,
            StructuredOutputPlan.ObjectValue object
    ) {
        for (StructuredOutputPlan.Entry entry : object.entries()) {
            if (entry.value() instanceof StructuredOutputPlan.ScalarValue scalar
                    && scalar.column().equals(key.column())) {
                return Optional.ofNullable(row.get(scalar.fieldName()));
            }
            if (entry.value() instanceof StructuredOutputPlan.ObjectValue child) {
                Optional<Object> nested = rowKeyValueFromOutput(row, key, child);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private RowMaterializationRuntimePlan.MaterializedRelationQuery requireRelation() {
        if (rows.relationQueries().isEmpty()) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured output runtime relation output requires materialized relation rows.");
        }
        return rows.relationQueries().getFirst();
    }

    private Map<Object, ArrayList<Map<String, ?>>> groupRelationRows(
            List<? extends Map<String, ?>> relationRows,
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation,
            StructuredOutputPlan.ObjectValue child
    ) {
        LinkedHashMap<Object, ArrayList<Map<String, ?>>> grouped = new LinkedHashMap<>();
        for (Map<String, ?> relationRow : relationRows) {
            Object childParentValue = rowKeyValue(relationRow, relation.childKey(), child);
            grouped.computeIfAbsent(childParentValue, ignored -> new ArrayList<>()).add(relationRow);
        }
        return grouped;
    }

    private List<Map<String, Object>> castRows(List<? extends Map<String, ?>> rootRows) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, ?> rootRow : rootRows) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : rootRow.entrySet()) {
                copy.put(entry.getKey(), entry.getValue());
            }
            rows.add(copy);
        }
        return rows;
    }

    private void rejectUnrequestedRows(
            Map<Object, ArrayList<Map<String, ?>>> grouped,
            RowMaterializationRuntimePlan.CollectedParentKeys parentKeys,
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation
    ) {
        for (Object childParentKey : grouped.keySet()) {
            if (!parentKeys.queryKeys().contains(childParentKey)) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime relation '" + relation.name()
                                + "' at path '" + relationPath(relation)
                                + "' row was not requested by the materialized parent-key carrier.");
            }
        }
    }

    private List<? extends Map<String, ?>> relationRows(
            Map<String, ? extends List<? extends Map<String, ?>>> relationRowsByName,
            String relation
    ) {
        List<? extends Map<String, ?>> rows = relationRowsByName.get(relation);
        return rows == null ? List.of() : rows;
    }

    private Map<String, Object> assembleNestedRoot(
            Map<String, ?> rootRow,
            List<? extends Map<String, ?>> childRows,
            RowMaterializationRuntimePlan.MaterializedRelationQuery childRelation,
            StructuredOutputPlan.ObjectValue childOutput,
            RowMaterializationRuntimePlan.MaterializedRelationQuery grandchildRelation,
            StructuredOutputPlan.ObjectValue grandchildOutput,
            Map<Object, ArrayList<Map<String, ?>>> groupedGrandchildren
    ) {
        return assembleNestedObject(
                output.root(),
                rootRow,
                childRows,
                childRelation,
                childOutput,
                grandchildRelation,
                grandchildOutput,
                groupedGrandchildren);
    }

    private Map<String, Object> assembleNestedObject(
            StructuredOutputPlan.ObjectValue object,
            Map<String, ?> rootRow,
            List<? extends Map<String, ?>> childRows,
            RowMaterializationRuntimePlan.MaterializedRelationQuery childRelation,
            StructuredOutputPlan.ObjectValue childOutput,
            RowMaterializationRuntimePlan.MaterializedRelationQuery grandchildRelation,
            StructuredOutputPlan.ObjectValue grandchildOutput,
            Map<Object, ArrayList<Map<String, ?>>> groupedGrandchildren
    ) {
        LinkedHashMap<String, Object> assembled = new LinkedHashMap<>();
        for (StructuredOutputPlan.Entry entry : object.entries()) {
            String key = entry.key().value();
            if (key.equals(childRelation.name())) {
                assembled.put(key, assembleNestedRelationValue(
                        childRelation,
                        entry.value(),
                        childOutput,
                        childRows,
                        grandchildRelation,
                        grandchildOutput,
                        groupedGrandchildren));
            } else if (entry.value() instanceof StructuredOutputPlan.ScalarValue scalar) {
                assembled.put(key, rootRow.get(scalar.fieldName()));
            } else if (entry.value() instanceof StructuredOutputPlan.NullValue
                    || entry.value() instanceof StructuredOutputPlan.JsonNullValue) {
                assembled.put(key, null);
            } else if (entry.value() instanceof StructuredOutputPlan.TextLiteralValue literal) {
                assembled.put(key, literal.value());
            } else if (entry.value() instanceof StructuredOutputPlan.IntegerLiteralValue literal) {
                assembled.put(key, literal.value());
            } else if (entry.value() instanceof StructuredOutputPlan.BooleanLiteralValue literal) {
                assembled.put(key, literal.value());
            } else if (entry.value() instanceof StructuredOutputPlan.ObjectValue nested) {
                assembled.put(key, assembleNestedObject(
                        nested,
                        rootRow,
                        childRows,
                        childRelation,
                        childOutput,
                        grandchildRelation,
                        grandchildOutput,
                        groupedGrandchildren));
            } else {
                assembled.put(key, assembleValue(key, entry.value(), rootRow, List.of()));
            }
        }
        return assembled;
    }

    private Object assembleNestedRelationValue(
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation,
            StructuredOutputPlan.Value value,
            StructuredOutputPlan.ObjectValue childOutput,
            List<? extends Map<String, ?>> relationRows,
            RowMaterializationRuntimePlan.MaterializedRelationQuery grandchildRelation,
            StructuredOutputPlan.ObjectValue grandchildOutput,
            Map<Object, ArrayList<Map<String, ?>>> groupedGrandchildren
    ) {
        if (value instanceof StructuredOutputPlan.ArrayValue) {
            if (relation.cardinality() != QueryTemplatePlan.RelationCardinality.MANY) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime relation '" + relation.name()
                                + "' is not a many relation and cannot assemble as an array.");
            }
            ArrayList<Map<String, Object>> children = new ArrayList<>();
            for (Map<String, ?> relationRow : relationRows) {
                children.add(assembleNestedRelationObject(
                        childOutput,
                        relationRow,
                        grandchildRelation,
                        grandchildOutput,
                        groupedGrandchildren));
            }
            return List.copyOf(children);
        }
        return switch (relation.cardinality()) {
            case ZERO_OR_ONE -> switch (relationRows.size()) {
                case 0 -> null;
                case 1 -> assembleNestedRelationObject(
                        childOutput,
                        relationRows.getFirst(),
                        grandchildRelation,
                        grandchildOutput,
                        groupedGrandchildren);
                default -> throw relationCardinalityViolation(relation, relationRows.size());
            };
            case ONE -> {
                if (relationRows.size() != 1) {
                    throw relationCardinalityViolation(relation, relationRows.size());
                }
                yield assembleNestedRelationObject(
                        childOutput,
                        relationRows.getFirst(),
                        grandchildRelation,
                        grandchildOutput,
                        groupedGrandchildren);
            }
            case MANY -> throw new IllegalArgumentException(
                    "TITAN-E001 structured output runtime relation '" + relation.name()
                            + "' is a many relation and must assemble as an array.");
        };
    }

    private Map<String, Object> assembleNestedRelationObject(
            StructuredOutputPlan.ObjectValue object,
            Map<String, ?> relationRow,
            RowMaterializationRuntimePlan.MaterializedRelationQuery grandchildRelation,
            StructuredOutputPlan.ObjectValue grandchildOutput,
            Map<Object, ArrayList<Map<String, ?>>> groupedGrandchildren
    ) {
        LinkedHashMap<String, Object> assembled = new LinkedHashMap<>();
        Object grandchildParentKey = rowKeyValue(relationRow, grandchildRelation.parentKeySource().key(), object);
        List<Map<String, ?>> grandchildren = groupedGrandchildren.getOrDefault(grandchildParentKey, new ArrayList<>());
        for (StructuredOutputPlan.Entry entry : object.entries()) {
            String key = entry.key().value();
            if (key.equals(grandchildRelation.name())) {
                assembled.put(key, assembleRelationValue(
                        grandchildRelation,
                        entry.value(),
                        grandchildOutput,
                        grandchildren));
            } else if (entry.value() instanceof StructuredOutputPlan.ScalarValue scalar) {
                assembled.put(key, relationRow.get(scalar.fieldName()));
            } else if (entry.value() instanceof StructuredOutputPlan.NullValue
                    || entry.value() instanceof StructuredOutputPlan.JsonNullValue) {
                assembled.put(key, null);
            } else if (entry.value() instanceof StructuredOutputPlan.TextLiteralValue literal) {
                assembled.put(key, literal.value());
            } else if (entry.value() instanceof StructuredOutputPlan.IntegerLiteralValue literal) {
                assembled.put(key, literal.value());
            } else if (entry.value() instanceof StructuredOutputPlan.BooleanLiteralValue literal) {
                assembled.put(key, literal.value());
            } else {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime nested relation objects support scalar, literal, null,"
                                + " and one child relation field.");
            }
        }
        return assembled;
    }

    private Object assembleRelationValue(
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation,
            StructuredOutputPlan.Value value,
            StructuredOutputPlan.ObjectValue child,
            List<? extends Map<String, ?>> relationRows
    ) {
        if (value instanceof StructuredOutputPlan.ArrayValue) {
            if (relation.cardinality() != QueryTemplatePlan.RelationCardinality.MANY) {
                throw new IllegalArgumentException(
                        "TITAN-E001 structured output runtime relation '" + relation.name()
                                + "' is not a many relation and cannot assemble as an array.");
            }
            ArrayList<Map<String, Object>> children = new ArrayList<>();
            for (Map<String, ?> relationRow : relationRows) {
                children.add(assembleRelationObject(child, relationRow));
            }
            return List.copyOf(children);
        }
        return switch (relation.cardinality()) {
            case ZERO_OR_ONE -> switch (relationRows.size()) {
                case 0 -> null;
                case 1 -> assembleRelationObject(child, relationRows.getFirst());
                default -> throw relationCardinalityViolation(relation, relationRows.size());
            };
            case ONE -> {
                if (relationRows.size() != 1) {
                    throw relationCardinalityViolation(relation, relationRows.size());
                }
                yield assembleRelationObject(child, relationRows.getFirst());
            }
            case MANY -> throw new IllegalArgumentException(
                    "TITAN-E001 structured output runtime relation '" + relation.name()
                            + "' is a many relation and must assemble as an array.");
        };
    }

    private void requireRelationKey(
            String outputKey,
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation
    ) {
        if (!outputKey.equals(relation.name())) {
            throw new IllegalArgumentException(
                    "TITAN-E001 structured output runtime array output '" + outputKey
                            + "' must match grouped relation '" + relation.name() + "'.");
        }
    }

    private StructuredOutputPlan.ObjectValue requireRelationObject(
            StructuredOutputPlan.Value value,
            String relation
    ) {
        if (value instanceof StructuredOutputPlan.ObjectValue object) {
            return object;
        }
        throw new IllegalArgumentException(
                "TITAN-E001 structured output runtime relation '" + relation
                        + "' must assemble object-shaped child rows.");
    }

    private StructuredOutputPlan.ObjectValue relationOutput(
            StructuredOutputPlan.ObjectValue parent,
            String relation
    ) {
        for (StructuredOutputPlan.Entry entry : parent.entries()) {
            if (!entry.key().value().equals(relation)) {
                continue;
            }
            if (entry.value() instanceof StructuredOutputPlan.ArrayValue array) {
                return requireRelationObject(array.element(), relation);
            }
            if (entry.value() instanceof StructuredOutputPlan.ObjectValue object) {
                return object;
            }
            break;
        }
        for (StructuredOutputPlan.Entry entry : parent.entries()) {
            if (entry.key().value().equals("data")
                    && entry.value() instanceof StructuredOutputPlan.ObjectValue data) {
                return relationOutput(data, relation);
            }
        }
        throw new IllegalArgumentException(
                "TITAN-E001 structured output runtime relation '" + relation
                        + "' must be present in the output shape.");
    }

    private IllegalArgumentException relationCardinalityViolation(
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation,
            int rowCount
    ) {
        return new RelationCardinalityViolationException(
                relation.name(),
                relationPath(relation),
                relation.cardinality(),
                rowCount);
    }

    private String relationPath(RowMaterializationRuntimePlan.MaterializedRelationQuery relation) {
        return relationPath(relationPaths(rows.relationQueries()), relation);
    }

    private static String relationPath(
            Map<RowMaterializationRuntimePlan.MaterializedRelationQuery, String> relationPaths,
            RowMaterializationRuntimePlan.MaterializedRelationQuery relation
    ) {
        return relationPaths.getOrDefault(relation, relation.name());
    }

    private static Map<RowMaterializationRuntimePlan.MaterializedRelationQuery, String> relationPaths(
            List<RowMaterializationRuntimePlan.MaterializedRelationQuery> relations
    ) {
        LinkedHashMap<String, String> pathsByChildAlias = new LinkedHashMap<>();
        LinkedHashMap<RowMaterializationRuntimePlan.MaterializedRelationQuery, String> pathsByRelation =
                new LinkedHashMap<>();
        for (RowMaterializationRuntimePlan.MaterializedRelationQuery relation : relations) {
            String parentAlias = relation.parentKeySource().key().column().tableAlias();
            String parentPath = pathsByChildAlias.get(parentAlias);
            String path = parentPath == null ? relation.name() : parentPath + "." + relation.name();
            pathsByChildAlias.put(relation.childKey().column().tableAlias(), path);
            pathsByRelation.put(relation, path);
        }
        return Map.copyOf(pathsByRelation);
    }

    public static final class RelationCardinalityViolationException extends IllegalArgumentException {
        private final String relationName;
        private final String relationPath;
        private final QueryTemplatePlan.RelationCardinality cardinality;
        private final int rowCount;

        private RelationCardinalityViolationException(
                String relationName,
                String relationPath,
                QueryTemplatePlan.RelationCardinality cardinality,
                int rowCount
        ) {
            super("TITAN-E001 structured output runtime relation '" + relationName
                    + "' at path '" + relationPath
                    + "' cardinality " + cardinality
                    + " expected a compatible child row count but received " + rowCount + ".");
            this.relationName = Objects.requireNonNull(relationName, "relationName");
            this.relationPath = Objects.requireNonNull(relationPath, "relationPath");
            this.cardinality = Objects.requireNonNull(cardinality, "cardinality");
            this.rowCount = rowCount;
        }

        public String relationName() {
            return relationName;
        }

        public String relationPath() {
            return relationPath;
        }

        public QueryTemplatePlan.RelationCardinality cardinality() {
            return cardinality;
        }

        public int rowCount() {
            return rowCount;
        }
    }
}
