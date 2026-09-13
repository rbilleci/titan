package io.titan.gradle;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitanObjectInventoryTest {

    @Test
    void serializesDeterministicJsonWithCreateOrderAndDependencies() {
        TitanObjectInventory inventory = sampleInventory(List.of(
                object("postgresql.public.visible_lesson_title.function", "function", "visible_lesson_title", 20,
                        List.of("postgresql.public.lesson_descriptor.type")),
                object("postgresql.public.lesson_descriptor.type", "type", "lesson_descriptor", 10, List.of())));
        TitanObjectInventory reordered = sampleInventory(List.of(
                object("postgresql.public.lesson_descriptor.type", "type", "lesson_descriptor", 10, List.of()),
                object("postgresql.public.visible_lesson_title.function", "function", "visible_lesson_title", 20,
                        List.of("postgresql.public.lesson_descriptor.type"))));

        assertEquals(inventory.toJson(), reordered.toJson());

        String json = inventory.toJson();
        assertTrue(json.indexOf("\"name\": \"lesson_descriptor\"")
                < json.indexOf("\"name\": \"visible_lesson_title\""), json);
        assertTrue(json.contains("\"schemaVersion\": \"titan.object-inventory.v1\""));
        assertTrue(json.contains("\"createOrder\": 10"));
        assertTrue(json.contains("\"dependsOn\": [\"postgresql.public.lesson_descriptor.type\"]"));
        assertEquals(json, new String(inventory.toJsonBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void canonicalContentHashCoversInventoryFieldsExceptSelfHash() {
        TitanObjectInventory inventory = sampleInventory(List.of(
                object("postgresql.public.visible_lesson_title.function", "function", "visible_lesson_title", 20,
                        List.of())));
        TitanObjectInventory changedObject = sampleInventory(List.of(
                object("postgresql.public.visible_lesson_title.function", "function", "visible_lesson_title", 21,
                        List.of())));

        assertEquals(
                inventory.canonicalContentHash(),
                inventory.withInventoryContentSha256(sha("different self hash")).canonicalContentHash());
        assertTrue(!inventory.canonicalContentHash().equals(changedObject.canonicalContentHash()));

        TitanObjectInventory finalized = inventory.withInventoryContentSha256(inventory.canonicalContentHash());
        assertTrue(finalized.toJson().contains("\"inventoryContentSha256\": \""
                + inventory.canonicalContentHash() + "\""));
    }

    @Test
    void rejectsMissingObjectFieldsBeforePublishingIncompleteInventory() {
        IllegalArgumentException missingName = assertThrows(
                IllegalArgumentException.class,
                () -> object("postgresql.public.visible_lesson_title.function", "function", "", 20, List.of()));
        assertTrue(missingName.getMessage().contains("generated object name"));

        IllegalArgumentException badHash = assertThrows(
                IllegalArgumentException.class,
                () -> new TitanObjectInventory.GeneratedObject(
                        "postgresql.public.visible_lesson_title.function",
                        "postgresql",
                        "function",
                        "public",
                        "visible_lesson_title",
                        "(course_id bigint)",
                        "postgresql/visible.sql",
                        "CourseBrowseKernel.visibleLessonTitle(long)",
                        "invoker",
                        20,
                        List.of(),
                        "not-a-hash"));
        assertTrue(badHash.getMessage().contains("64-character lowercase hex"));

        IllegalArgumentException duplicateId = assertThrows(
                IllegalArgumentException.class,
                () -> sampleInventory(List.of(
                        object("postgresql.public.visible_lesson_title.function", "function", "visible_lesson_title", 20, List.of()),
                        object("postgresql.public.visible_lesson_title.function", "function", "visible_lesson_title", 21, List.of()))));
        assertTrue(duplicateId.getMessage().contains("unique generated object ids"));
    }

    private static TitanObjectInventory sampleInventory(List<TitanObjectInventory.GeneratedObject> objects) {
        return new TitanObjectInventory(
                TitanObjectInventory.CURRENT_SCHEMA_VERSION,
                "titan.generated-sql.test",
                objects,
                new TitanObjectInventory.InventoryHashes(sha("inventory")));
    }

    private static TitanObjectInventory.GeneratedObject object(
            String id,
            String kind,
            String name,
            int createOrder,
            List<String> dependsOn
    ) {
        return new TitanObjectInventory.GeneratedObject(
                id,
                "postgresql",
                kind,
                "public",
                name,
                kind.equals("function") ? "(course_id bigint)" : "()",
                "postgresql/demo_CourseBrowseKernel__visibleLessonTitle.sql",
                "demo.CourseBrowseKernel.visibleLessonTitle()",
                "invoker",
                createOrder,
                dependsOn,
                sha(id + createOrder));
    }

    private static String sha(String value) {
        return TitanArtifactManifest.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }
}
