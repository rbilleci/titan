package io.titan.transpiler.fixtures.generatedcatalog;

import javax.annotation.processing.Generated;
import titan.dsl.Column;
import titan.dsl.Nullability;
import titan.dsl.PhysicalColumn;
import titan.dsl.PhysicalTable;
import titan.dsl.SQLType;
import titan.dsl.Table;

@Generated("titan-generator")
@PhysicalTable(name = "plans", schema = "public")
public final class GeneratedPlans extends Table<Object> {

    public static final GeneratedPlans PLANS = new GeneratedPlans();

    @PhysicalColumn("id")
    public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

    private GeneratedPlans() {
        super("plans", "public");
    }

}
