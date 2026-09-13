package io.titan.intellij;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class TitanDslCompletionContributorTest extends LightJavaCodeInsightFixtureTestCase {

    public void testSuggestsColumnFieldsAfterTableQualifier() {
        myFixture.addClass("""
                package titan.dsl;
                public class Column<T> {}
                """);

        myFixture.addClass("""
                package demo;
                import titan.dsl.Column;
                public final class Accounts {
                    public static final Accounts ACCOUNTS = new Accounts();
                    public final Column<Integer> ID = new Column<>();
                    public final Column<String> EMAIL = new Column<>();
                    public final String NON_COLUMN = "ignored";
                }
                """);

        myFixture.configureByText("Use.java", """
                package demo;
                class Use {
                    void run() {
                        var v = Accounts.ACCOUNTS.<caret>;
                    }
                }
                """);

        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);

        Set<String> names = Arrays.stream(variants)
                .map(LookupElement::getLookupString)
                .collect(Collectors.toSet());

        assertTrue(names.contains("ID"));
        assertTrue(names.contains("EMAIL"));
    }

    public void testOffersColumnComparisonsInsideWhereClause() {
        myFixture.addClass("""
                package titan.dsl;
                public class Column<T> {}
                """);

        myFixture.addClass("""
                package titan.dsl;
                public class SelectBuilder {
                    public SelectBuilder from(Object ignored) { return this; }
                    public SelectBuilder join(Object ignored) { return this; }
                    public SelectBuilder on(Object ignored) { return this; }
                    public SelectBuilder where(Object ignored) { return this; }
                }
                """);

        myFixture.addClass("""
                package titan.dsl;
                public final class DSL {
                    public static SelectBuilder select(Column<?>... cols) { return new SelectBuilder(); }
                }
                """);

        myFixture.addClass("""
                package demo;
                import titan.dsl.Column;
                public final class Accounts {
                    public static final Accounts ACCOUNTS = new Accounts();
                    public final Column<Integer> ID = new Column<>();
                    public final Column<String> EMAIL = new Column<>();
                }
                """);

        myFixture.configureByText("Use.java", """
                package demo;
                import static titan.dsl.DSL.select;
                class Use {
                    void run() {
                        select(Accounts.ACCOUNTS.ID)
                                .from(Accounts.ACCOUNTS)
                                .where(<caret>);
                    }
                }
                """);

        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);

        Set<String> names = Arrays.stream(variants)
                .map(LookupElement::getLookupString)
                .collect(Collectors.toSet());

        assertTrue(names.contains("ID.eq(...)"));
        assertTrue(names.contains("ID.le(...)"));
        assertTrue(names.contains("ID.ge(...)"));
        assertTrue(names.contains("ID.in(...)"));
        assertTrue(names.contains("ID.between(..., ...)"));
        assertTrue(names.contains("EMAIL.like(...)"));
        assertTrue(names.contains("EMAIL.isNull()"));
    }

    public void testOffersColumnComparisonsFromJoinedTablesInsideWhereClause() {
        myFixture.addClass("""
                package titan.dsl;
                public class Column<T> {
                    public Object eqColumn(Object other) { return null; }
                }
                """);

        myFixture.addClass("""
                package titan.dsl;
                public class SelectBuilder {
                    public SelectBuilder from(Object ignored) { return this; }
                    public SelectBuilder fullOuterJoin(Object ignored) { return this; }
                    public SelectBuilder on(Object ignored) { return this; }
                    public SelectBuilder where(Object ignored) { return this; }
                }
                """);

        myFixture.addClass("""
                package titan.dsl;
                public final class DSL {
                    public static SelectBuilder select(Column<?>... cols) { return new SelectBuilder(); }
                }
                """);

        myFixture.addClass("""
                package demo;
                import titan.dsl.Column;
                public final class Accounts {
                    public static final Accounts ACCOUNTS = new Accounts();
                    public final Column<Integer> ID = new Column<>();
                }
                """);

        myFixture.addClass("""
                package demo;
                import titan.dsl.Column;
                public final class Orders {
                    public static final Orders ORDERS = new Orders();
                    public final Column<Integer> ACCOUNT_ID = new Column<>();
                }
                """);

        myFixture.configureByText("UseJoin.java", """
                package demo;
                import static titan.dsl.DSL.select;
                class UseJoin {
                    void run() {
                        select(Accounts.ACCOUNTS.ID)
                                .from(Accounts.ACCOUNTS)
                                .fullOuterJoin(Orders.ORDERS)
                                .on(Orders.ORDERS.ACCOUNT_ID.eqColumn(Accounts.ACCOUNTS.ID))
                                .where(<caret>);
                    }
                }
                """);

        LookupElement[] variants = myFixture.completeBasic();
        assertNotNull(variants);

        Set<String> names = Arrays.stream(variants)
                .map(LookupElement::getLookupString)
                .collect(Collectors.toSet());

        assertTrue(names.contains("ID.eq(...)"));
        assertTrue(names.contains("ACCOUNT_ID.eq(...)"));
    }
}
