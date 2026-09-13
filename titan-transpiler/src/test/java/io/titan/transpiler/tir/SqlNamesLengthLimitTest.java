package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TG-BLK-011 regression suite for the {@link SqlNames} length-limit authority: every generated
 * record/enum SQL name fits the strictest registered dialect identifier ceiling
 * ({@code DialectCapabilities.NamingRules.identifierMaxLength}: PostgreSQL 63, MySQL 64), via
 * deterministic hash-suffix truncation where the suffix hashes the <em>full</em> untruncated
 * name. Without this, PostgreSQL silently truncates to 63 bytes (member accessors of one long
 * record collapse onto a single name — last body wins or "cannot change return type") and MySQL
 * rejects the CREATE with ER_TOO_LONG_IDENT.
 */
class SqlNamesLengthLimitTest {

    private static final String LONG_QUALIFIED_RECORD =
            "DurableManagementStoreProductStateJournal.ProductStateJournalEntryPageSnapshot";
    private static final String LONG_QUALIFIED_ENUM =
            "DurableManagementStoreProductStateJournal.ProductLifecycleStageClassificationChannel";

    @Test
    void limitIsTheStrictestRegisteredDialectCeiling() {
        int expected = Integer.MAX_VALUE;
        for (DialectId dialect : DialectId.values()) {
            expected = Math.min(expected,
                    DialectCapabilities.forDialect(dialect).namingRules().identifierMaxLength());
        }
        assertEquals(expected, SqlNames.generatedNameMaxLength(),
                "generated-name ceiling must be the minimum NamingRules.identifierMaxLength across dialects");
        // The current registry: PostgreSQL 63 (NAMEDATALEN-1) and MySQL 64.
        assertEquals(63, SqlNames.generatedNameMaxLength());
    }

    @Test
    void shortNamesAreReturnedByteIdentical() {
        assertEquals("__record_projection_retrieval_sort_path",
                SqlNames.recordSqlBaseName("ProjectionRetrieval.SortPath"));
        assertEquals("__record_projection_retrieval_sort_path__depth",
                SqlNames.recordMemberName("ProjectionRetrieval.SortPath", "depth"));
        assertEquals("__enum_projection_retrieval_mode",
                SqlNames.enumSqlBaseName("ProjectionRetrieval.Mode"));
        assertEquals("__enum_projection_retrieval_mode__label",
                SqlNames.enumMemberName("ProjectionRetrieval.Mode", "label"));
    }

    @Test
    void everyGeneratedNameFamilyFitsTheLimitForLongQualifiedNames() {
        int limit = SqlNames.generatedNameMaxLength();
        for (String name : new String[]{
                SqlNames.recordSqlBaseName(LONG_QUALIFIED_RECORD),
                SqlNames.recordMemberName(LONG_QUALIFIED_RECORD, "new"),
                SqlNames.recordMemberName(LONG_QUALIFIED_RECORD, "has_next_page"),
                SqlNames.enumSqlBaseName(LONG_QUALIFIED_ENUM),
                SqlNames.enumMemberName(LONG_QUALIFIED_ENUM, "priority_weight")}) {
            assertTrue(name.length() <= limit,
                    "generated name exceeds dialect ceiling (" + name.length() + " > " + limit + "): " + name);
        }
    }

    @Test
    void truncationIsDeterministicAndPrefixPreserving() {
        String first = SqlNames.recordMemberName(LONG_QUALIFIED_RECORD, "has_next_page");
        String second = SqlNames.recordMemberName(LONG_QUALIFIED_RECORD, "has_next_page");
        assertEquals(first, second, "truncation must be deterministic across calls/builds");
        assertTrue(first.startsWith("__record_durable_management_store_product_state"),
                "truncated name must keep a readable prefix of the full name: " + first);
        assertEquals(SqlNames.generatedNameMaxLength(), first.length(),
                "over-limit names are truncated to exactly the ceiling");
    }

    /**
     * The TG-BLK-011 collision class: on PostgreSQL, every member accessor of a record whose
     * type name is near the ceiling silently truncated to the SAME 63-byte name. With the
     * full-name hash suffix, names differing only past the truncation point stay distinct
     * by construction.
     */
    @Test
    void namesDifferingOnlyPastTheTruncationPointStayDistinct() {
        String hasNext = SqlNames.recordMemberName(LONG_QUALIFIED_RECORD, "has_next_page");
        String hasPrevious = SqlNames.recordMemberName(LONG_QUALIFIED_RECORD, "has_previous_page");
        String comments = SqlNames.recordMemberName(LONG_QUALIFIED_RECORD, "comments");
        String constructor = SqlNames.recordMemberName(LONG_QUALIFIED_RECORD, "new");
        String typeName = SqlNames.recordSqlBaseName(LONG_QUALIFIED_RECORD);

        // All five share the first 54 characters (the truncation prefix) and differ only in
        // the part PostgreSQL used to throw away.
        assertEquals(hasNext.substring(0, 54), hasPrevious.substring(0, 54));
        assertNotEquals(hasNext, hasPrevious);
        assertNotEquals(hasNext, comments);
        assertNotEquals(hasNext, constructor);
        assertNotEquals(hasNext, typeName);
        assertNotEquals(hasPrevious, comments);
        assertNotEquals(constructor, typeName);
    }

    /** Two long type names differing only in their last fragment must not converge either. */
    @Test
    void longSiblingTypeNamesWithSharedPrefixStayDistinct() {
        String alpha = SqlNames.recordSqlBaseName(
                "GeneratedArtifactWorkflowCoordination.ProductStateJournalEntrySnapshotAlpha");
        String beta = SqlNames.recordSqlBaseName(
                "GeneratedArtifactWorkflowCoordination.ProductStateJournalEntrySnapshotBeta");
        assertEquals(alpha.substring(0, 54), beta.substring(0, 54),
                "fixture must collide before the hash suffix to prove the point");
        assertNotEquals(alpha, beta);
        assertTrue(alpha.length() <= SqlNames.generatedNameMaxLength());
        assertTrue(beta.length() <= SqlNames.generatedNameMaxLength());
    }

    /** General-purpose entry used by routine/trigger/event composition points. */
    @Test
    void fitWithinDialectLimitsLeavesShortNamesAloneAndCapsLongOnes() {
        assertEquals("journal_page_weight", SqlNames.fitWithinDialectLimits("journal_page_weight"));
        String longRoutine = "synchronize_durable_management_store_product_state_journal_entries_for_tenant";
        String fitted = SqlNames.fitWithinDialectLimits(longRoutine);
        assertEquals(SqlNames.generatedNameMaxLength(), fitted.length());
        assertEquals(fitted, SqlNames.fitWithinDialectLimits(longRoutine), "deterministic");
        assertNotEquals(fitted, SqlNames.fitWithinDialectLimits(longRoutine + "_v2"),
                "suffix hash covers the full name, so longer variants stay distinct");
    }
}
