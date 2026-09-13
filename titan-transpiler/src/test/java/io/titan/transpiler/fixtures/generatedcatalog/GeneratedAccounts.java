package io.titan.transpiler.fixtures.generatedcatalog;

import javax.annotation.processing.Generated;
import titan.dsl.Column;
import titan.dsl.Nullability;
import titan.dsl.PhysicalColumn;
import titan.dsl.PhysicalTable;
import titan.dsl.SQLType;
import titan.dsl.Table;

@Generated("titan-generator")
@PhysicalTable(name = "accounts", schema = "public")
public final class GeneratedAccounts extends Table<Object> {

    public static final GeneratedAccounts ACCOUNTS = new GeneratedAccounts();

    @PhysicalColumn("id")
    public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

    @PhysicalColumn("plan_id")
    public final Column<Integer> PLAN_ID = column("plan_id", SQLType.INTEGER, Nullability.NULLABLE);

    @PhysicalColumn("first_name")
    public final Column<String> FIRST_NAME = column("first_name", SQLType.VARCHAR, Nullability.NULLABLE);

    private GeneratedAccounts() {
        super("accounts", "public");
    }

}
