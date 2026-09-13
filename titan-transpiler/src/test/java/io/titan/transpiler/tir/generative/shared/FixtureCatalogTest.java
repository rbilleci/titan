package io.titan.transpiler.tir.generative.shared;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FixtureCatalogTest {

    @Test
    void accountsFixtureIsDeterministicAndCoversInitialValueFamilies() {
        FixtureCatalog.FixtureTable fixture = FixtureCatalog.accountsFixture();
        FixtureCatalog.FixtureTable secondFixture = FixtureCatalog.accountsFixture();

        assertEquals("accounts_fixture", fixture.sourceTableId());
        assertEquals(5, fixture.columns().size());
        assertEquals(6, fixture.rows().size());
        assertEquals(fixture.toStableJson(), secondFixture.toStableJson(), "fixture serialization should be deterministic");

        assertTrue(fixture.columns().stream().anyMatch(column -> column.name().equals("id") && column.valueTypeId().equals("integer")));
        assertTrue(fixture.columns().stream().anyMatch(column -> column.name().equals("active") && column.valueTypeId().equals("boolean")));
        assertTrue(fixture.columns().stream().anyMatch(column -> column.name().equals("email") && column.valueTypeId().equals("text")));

        assertTrue(fixture.rows().stream().anyMatch(row -> row.values().get("email") == null), "expected null text coverage");
        assertTrue(fixture.rows().stream().anyMatch(row -> row.values().get("active") == null), "expected null boolean coverage");
        assertTrue(fixture.rows().stream().anyMatch(row -> row.values().get("login_count") == null), "expected null integer coverage");
        assertTrue(fixture.rows().stream().anyMatch(row -> Integer.valueOf(12).equals(row.values().get("login_count"))), "expected duplicate-ish integer/selectivity coverage");
        assertTrue(fixture.rows().stream().anyMatch(row -> "pro".equals(row.values().get("plan_code"))), "expected repeated text/selectivity coverage");
    }
}
