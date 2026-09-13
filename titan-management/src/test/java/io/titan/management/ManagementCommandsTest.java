package io.titan.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.management.ManagementCommands.ActorContext;
import io.titan.management.ManagementCommands.ActorRequirement;
import io.titan.management.ManagementCommands.CommandDescriptor;
import io.titan.management.ManagementCommands.CommandInvocation;
import io.titan.management.ManagementCommands.CommandValidation;
import io.titan.management.ManagementCommands.InputField;
import io.titan.management.ManagementCommands.InputType;
import io.titan.management.ManagementCommands.ManagementCommandDescriptors;
import io.titan.management.ManagementCommands.RequestContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagementCommandsTest {
    @Test
    void describesImportModelDocumentWithoutTransportOwnedState() {
        CommandDescriptor descriptor = ManagementCommandDescriptors.importModelDocument();

        assertEquals("management.importModelDocument", descriptor.commandName());
        // Input fields are canonically sorted by name (G-11 fix: sortedInputFields now sorts).
        assertEquals(
                List.of("sourceFormat", "sourceText", "workspaceId"),
                descriptor.requiredInputNames());
        assertEquals("workspace", descriptor.actorRequirement().scope());
        assertEquals(List.of("admin", "operator", "platform"), descriptor.actorRequirement().roles());
        assertTrue(descriptor.requiresIdempotencyKey());
        assertTrue(descriptor.requiresTransaction());
        assertEquals("attempt-and-outcome", descriptor.auditMode());
        assertEquals(List.of("draftId", "validationReportId"), descriptor.resultFieldNames());
        assertFalse(descriptor.stableJson().contains("GraphQL"));
        assertFalse(descriptor.stableJson().contains("resolver"));
    }

    @Test
    void validatesImportInvocationAndProducesCanonicalInputHash() {
        CommandDescriptor descriptor = ManagementCommandDescriptors.importModelDocument();
        CommandInvocation invocation = new CommandInvocation(
                descriptor,
                new ActorContext("actor-platform-001", "platform", "workspace-001", true),
                new RequestContext("request-import-demo-blog-001", "import:workspace-001:demo-blog:v1"),
                Map.of(
                        "workspaceId", "workspace-001",
                        "sourceFormat", "yaml",
                        "sourceText", "model: demo-blog"));

        CommandValidation validation = invocation.validate();

        assertTrue(validation.valid(), validation.stableJson());
        assertEquals(List.of(), validation.errors());
        assertEquals(
                "sha256:9ca465d90ad1dd097c0f5fe56d9be27751de04a20ba95d0474e40fe47f27b70b",
                invocation.canonicalInputHash());
        assertEquals(
                "{\"commandName\":\"management.importModelDocument\","
                        + "\"actorId\":\"actor-platform-001\","
                        + "\"requestId\":\"request-import-demo-blog-001\","
                        + "\"idempotencyKey\":\"import:workspace-001:demo-blog:v1\","
                        + "\"inputHash\":\"sha256:9ca465d90ad1dd097c0f5fe56d9be27751de04a20ba95d0474e40fe47f27b70b\","
                        + "\"valid\":true,\"errors\":[]}",
                validation.stableJson());
    }

    @Test
    void reportsDeterministicValidationErrorsForRequiredContextAndInput() {
        CommandDescriptor descriptor = ManagementCommandDescriptors.importModelDocument();
        CommandValidation validation = new CommandInvocation(
                        descriptor,
                        null,
                        new RequestContext("", ""),
                        Map.of("workspaceId", "workspace-001"))
                .validate();

        // Errors are canonically sorted (G-11 fix: sortedErrors now sorts); the diagnostic-code
        // prefix makes that the stable code order rather than validation execution order.
        assertEquals(
                List.of(
                        "TITAN-MGMT-E001 command 'management.importModelDocument' requires input field 'sourceFormat'",
                        "TITAN-MGMT-E001 command 'management.importModelDocument' requires input field 'sourceText'",
                        "TITAN-MGMT-E002 command 'management.importModelDocument' requires an authenticated actor",
                        "TITAN-MGMT-E003 command 'management.importModelDocument' requires idempotency key",
                        "TITAN-MGMT-E004 command 'management.importModelDocument' requires request id"),
                validation.errors());
        assertFalse(validation.valid());
    }

    @Test
    void rejectsUnsupportedFieldsRolesAndEnumValues() {
        CommandDescriptor descriptor = ManagementCommandDescriptors.importModelDocument();
        CommandValidation validation = new CommandInvocation(
                        descriptor,
                        new ActorContext("actor-viewer-001", "viewer", "workspace-001", true),
                        new RequestContext("request-import-demo-blog-001", "import:workspace-001:demo-blog:v1"),
                        Map.of(
                                "workspaceId", "workspace-001",
                                "sourceFormat", "xml",
                                "sourceText", "model: demo-blog",
                                "unexpectedField", "value"))
                .validate();

        assertEquals(
                List.of(
                        "TITAN-MGMT-E005 command 'management.importModelDocument' actor role 'viewer' is not allowed",
                        "TITAN-MGMT-E006 command 'management.importModelDocument' input field 'sourceFormat' has unsupported value 'xml'",
                        "TITAN-MGMT-E007 command 'management.importModelDocument' does not support input field 'unexpectedField'"),
                validation.errors());
    }

    @Test
    void hashesSourceTextWithoutTreatingPayloadWordsAsCoreMetadata() {
        CommandDescriptor descriptor = ManagementCommandDescriptors.importModelDocument();
        CommandValidation validation = new CommandInvocation(
                        descriptor,
                        new ActorContext("actor-platform-001", "platform", "workspace-001", true),
                        new RequestContext("request-import-demo-blog-001", "import:workspace-001:demo-blog:v1"),
                        Map.of(
                                "workspaceId", "workspace-001",
                                "sourceFormat", "yaml",
                                "sourceText", "model: demo-blog\nnotes: GraphQL preview URL selection workflow"))
                .validate();

        assertTrue(validation.valid(), validation.stableJson());
        assertEquals(
                "sha256:3b5f0d99f6b3596bb0dc8fad3ad9e76fa8f70433a99096f8c2574f0f3f4b0419",
                validation.inputHash());
    }

    @Test
    void rejectsTransportOwnedDescriptorAndInputFieldNamesAtCoreBoundary() {
        IllegalArgumentException descriptorException = assertThrows(
                IllegalArgumentException.class,
                () -> new CommandDescriptor(
                        "management.importModelDocument",
                        "Import model document",
                        List.of(new InputField("graphqlFieldName", InputType.TEXT, true, List.of())),
                        new ActorRequirement(true, List.of("platform"), "workspace"),
                        true,
                        true,
                        "attempt-and-outcome",
                        List.of("draftId")));
        assertTrue(descriptorException.getMessage().contains("TITAN-GAP006-TRANSPORT-METADATA"));

        CommandDescriptor descriptor = ManagementCommandDescriptors.importModelDocument();
        IllegalArgumentException invocationException = assertThrows(
                IllegalArgumentException.class,
                () -> new CommandInvocation(
                        descriptor,
                        new ActorContext("actor-platform-001", "platform", "workspace-001", true),
                        new RequestContext("request-import-demo-blog-001", "import:workspace-001:demo-blog:v1"),
                        Map.of(
                                "workspaceId", "workspace-001",
                                "sourceFormat", "yaml",
                                "sourceText", "model: demo-blog",
                                "resolverPath", "Mutation.import")));
        assertTrue(invocationException.getMessage().contains("resolverPath"));
    }
}
