package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Plan 3.1 / 1.3: the compile-time dialect-capability model. */
class DialectCapabilitiesTest {

    @Test
    void postgresSupportsEveryModeledCapability() {
        DialectCapabilities postgres = DialectCapabilities.forDialect(DialectId.POSTGRESQL);
        assertFalse(postgres.updateDeleteReturning().isUnsupported());
        assertFalse(postgres.fullOuterJoin().isUnsupported());
        assertFalse(postgres.stringFormat().isUnsupported());
        assertFalse(postgres.intersectExcept().isUnsupported());
        assertFalse(postgres.intersectExcept().isVersionGated());
    }

    @Test
    void postgresModelsTheSecurityInvokerViewFloorAtFifteen() {
        // WITH (security_invoker = true) exists only from PostgreSQL 15 onward — the documented
        // compatibility floor (plan Phase 6) is version-gated, not silently implicit.
        DialectCapabilities postgres = DialectCapabilities.forDialect(DialectId.POSTGRESQL);
        assertTrue(postgres.securityInvokerViews().isVersionGated());
        assertEquals("15", postgres.securityInvokerViews().minServerVersion());

        // MySQL's SQL SECURITY INVOKER clause is supported on every targeted version: no gate.
        DialectCapabilities mysql = DialectCapabilities.forDialect(DialectId.MYSQL);
        assertFalse(mysql.securityInvokerViews().isUnsupported());
        assertFalse(mysql.securityInvokerViews().isVersionGated());
    }

    @Test
    void mySqlModelsTheKnownGapsWithSuggestionsAndTheIntersectVersionFloor() {
        DialectCapabilities mysql = DialectCapabilities.forDialect(DialectId.MYSQL);

        assertTrue(mysql.updateDeleteReturning().isUnsupported());
        assertTrue(mysql.fullOuterJoin().isUnsupported());
        assertTrue(mysql.stringFormat().isUnsupported());
        // Every hard rejection must carry a rewrite suggestion for the diagnostic.
        assertFalse(mysql.updateDeleteReturning().suggestion().isBlank());
        assertFalse(mysql.fullOuterJoin().suggestion().isBlank());
        assertFalse(mysql.stringFormat().suggestion().isBlank());

        // INTERSECT/EXCEPT exist from MySQL 8.0.31 onward: version-gated, not unsupported.
        assertTrue(mysql.intersectExcept().isVersionGated());
        assertEquals("8.0.31", mysql.intersectExcept().minServerVersion());
    }

    @Test
    void capabilityFactoriesEnforceTheirInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new DialectCapabilities.Capability(
                DialectCapabilities.Capability.Support.SUPPORTED_SINCE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new DialectCapabilities.Capability(
                DialectCapabilities.Capability.Support.UNSUPPORTED, null, " "));
    }

    @Test
    void dialectProvidersExposeTheCanonicalCapabilities() {
        assertEquals(DialectId.MYSQL, new MySqlDialectProvider().capabilities().dialect());
        assertEquals(DialectId.POSTGRESQL, new PostgreSqlDialectProvider().capabilities().dialect());
        assertEquals("MySQL", new MySqlDialectProvider().capabilities().displayName());
        assertEquals("PostgreSQL", new PostgreSqlDialectProvider().capabilities().displayName());
    }
}
