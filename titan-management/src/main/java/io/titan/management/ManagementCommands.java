package io.titan.management;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class ManagementCommands {
    private static final Pattern STABLE_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[-_.:][a-z0-9]+)*");
    private static final Pattern COMMAND_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:[._:-][A-Za-z0-9]+)*");
    private static final Pattern FIELD_NAME =
            Pattern.compile("[a-z][A-Za-z0-9]*(?:[._:-][A-Za-z0-9]+)*");
    private static final Set<String> TRANSPORT_OWNED_TERMS = Set.of(
            "fieldname",
            "graphql",
            "mutationfield",
            "preview",
            "productworkflow",
            "resolver",
            "selection",
            "transport",
            "uistate",
            "url",
            "workflow");

    private ManagementCommands() {
    }

    public record CommandDescriptor(
            String commandName,
            String description,
            List<InputField> inputFields,
            ActorRequirement actorRequirement,
            boolean requiresIdempotencyKey,
            boolean requiresTransaction,
            String auditMode,
            List<String> resultFieldNames
    ) implements StableCommandRecord {
        public CommandDescriptor {
            requireCommandName(commandName, "command name");
            requireText(description, "command description");
            rejectTransportOwned(description, "command description");
            inputFields = sortedInputFields(inputFields);
            Objects.requireNonNull(actorRequirement, "actor requirement");
            requireStableId(auditMode, "audit mode");
            resultFieldNames = sortedFieldNames(resultFieldNames, "result field");
            if (inputFields.isEmpty()) {
                throw new IllegalArgumentException("TITAN-GAP006-COMMAND: command descriptor requires input fields");
            }
        }

        public List<String> requiredInputNames() {
            return inputFields.stream()
                    .filter(InputField::required)
                    .map(InputField::name)
                    .toList();
        }

        InputField inputField(String name) {
            for (InputField field : inputFields) {
                if (field.name().equals(name)) {
                    return field;
                }
            }
            return null;
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("commandName", commandName)
                    .field("description", description)
                    .rawField("inputFields", jsonArray(inputFields.stream()
                            .map(InputField::stableJson)
                            .toList()))
                    .rawField("actorRequirement", actorRequirement.stableJson())
                    .field("requiresIdempotencyKey", requiresIdempotencyKey)
                    .field("requiresTransaction", requiresTransaction)
                    .field("auditMode", auditMode)
                    .rawField("resultFieldNames", stringArray(resultFieldNames))
                    .toJson();
        }
    }

    public record InputField(
            String name,
            InputType type,
            boolean required,
            List<String> allowedValues
    ) implements StableCommandRecord {
        public InputField {
            requireFieldName(name, "input field name");
            Objects.requireNonNull(type, "input field type");
            allowedValues = sortedFieldValues(allowedValues, "input field value");
            if (type != InputType.ENUM && !allowedValues.isEmpty()) {
                throw new IllegalArgumentException(
                        "TITAN-GAP006-COMMAND: allowed values are only supported for enum input fields");
            }
            if (type == InputType.ENUM && allowedValues.isEmpty()) {
                throw new IllegalArgumentException("TITAN-GAP006-COMMAND: enum input fields require allowed values");
            }
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("name", name)
                    .field("type", type.id())
                    .field("required", required)
                    .rawField("allowedValues", stringArray(allowedValues))
                    .toJson();
        }
    }

    public record ActorRequirement(
            boolean authenticated,
            List<String> roles,
            String scope
    ) implements StableCommandRecord {
        public ActorRequirement {
            roles = sortedFieldValues(roles, "actor role");
            requireStableId(scope, "actor scope");
            if (authenticated && roles.isEmpty()) {
                throw new IllegalArgumentException(
                        "TITAN-GAP006-COMMAND: authenticated actor requirement needs at least one role");
            }
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("authenticated", authenticated)
                    .rawField("roles", stringArray(roles))
                    .field("scope", scope)
                    .toJson();
        }
    }

    public record ActorContext(
            String actorId,
            String role,
            String scope,
            boolean authenticated
    ) {
        public ActorContext {
            if (actorId != null && !actorId.isBlank()) {
                requireStableId(actorId, "actor id");
            }
            if (role != null && !role.isBlank()) {
                requireStableId(role, "actor role");
            }
            if (scope != null && !scope.isBlank()) {
                requireStableId(scope, "actor scope");
            }
        }
    }

    public record RequestContext(
            String requestId,
            String idempotencyKey
    ) {
        public RequestContext {
            if (requestId != null && !requestId.isBlank()) {
                requireStableId(requestId, "request id");
            }
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                requireStableId(idempotencyKey, "idempotency key");
            }
        }
    }

    public record CommandInvocation(
            CommandDescriptor descriptor,
            ActorContext actor,
            RequestContext request,
            Map<String, String> input
    ) {
        public CommandInvocation {
            Objects.requireNonNull(descriptor, "command descriptor");
            input = immutableSortedInput(input);
        }

        public CommandValidation validate() {
            List<String> errors = new ArrayList<>();
            validateActor(errors);
            validateRequest(errors);
            validateInput(errors);
            return new CommandValidation(
                    descriptor.commandName(),
                    actor == null ? "" : nullToEmpty(actor.actorId()),
                    request == null ? "" : nullToEmpty(request.requestId()),
                    request == null ? "" : nullToEmpty(request.idempotencyKey()),
                    canonicalInputHash(),
                    List.copyOf(errors));
        }

        public String canonicalInputHash() {
            return sha256(canonicalInputJson());
        }

        private String canonicalInputJson() {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, String> entry : input.entrySet()) {
                json.field(entry.getKey(), entry.getValue());
            }
            return json.toJson();
        }

        private void validateActor(List<String> errors) {
            ActorRequirement requirement = descriptor.actorRequirement();
            if (requirement.authenticated()
                    && (actor == null || !actor.authenticated() || isBlank(actor.actorId()))) {
                errors.add(error("TITAN-MGMT-E002", "requires an authenticated actor"));
                return;
            }
            if (actor == null) {
                return;
            }
            if (!requirement.roles().isEmpty() && !requirement.roles().contains(actor.role())) {
                errors.add(error(
                        "TITAN-MGMT-E005",
                        "actor role '" + nullToEmpty(actor.role()) + "' is not allowed"));
            }
            if (!actorScopeMatches(requirement.scope(), actor.scope())) {
                errors.add(error(
                        "TITAN-MGMT-E008",
                        "actor scope '" + nullToEmpty(actor.scope()) + "' is not allowed"));
            }
        }

        private void validateRequest(List<String> errors) {
            if (request == null || isBlank(request.requestId())) {
                errors.add(error("TITAN-MGMT-E004", "requires request id"));
            }
            if (descriptor.requiresIdempotencyKey() && (request == null || isBlank(request.idempotencyKey()))) {
                errors.add(error("TITAN-MGMT-E003", "requires idempotency key"));
            }
        }

        private void validateInput(List<String> errors) {
            for (InputField field : descriptor.inputFields()) {
                String value = input.get(field.name());
                if (field.required() && isBlank(value)) {
                    errors.add(error(
                            "TITAN-MGMT-E001",
                            "requires input field '" + field.name() + "'"));
                    continue;
                }
                if (field.type() == InputType.ENUM && value != null && !field.allowedValues().contains(value)) {
                    errors.add(error(
                            "TITAN-MGMT-E006",
                            "input field '" + field.name() + "' has unsupported value '" + value + "'"));
                }
            }
            for (String fieldName : input.keySet()) {
                if (descriptor.inputField(fieldName) == null) {
                    errors.add(error(
                            "TITAN-MGMT-E007",
                            "does not support input field '" + fieldName + "'"));
                }
            }
        }

        private String error(String code, String message) {
            return code + " command '" + descriptor.commandName() + "' " + message;
        }
    }

    public record CommandValidation(
            String commandName,
            String actorId,
            String requestId,
            String idempotencyKey,
            String inputHash,
            List<String> errors
    ) implements StableCommandRecord {
        public CommandValidation {
            requireCommandName(commandName, "validation command name");
            actorId = nullToEmpty(actorId);
            requestId = nullToEmpty(requestId);
            idempotencyKey = nullToEmpty(idempotencyKey);
            requireSha256(inputHash, "validation input hash");
            errors = sortedErrors(errors);
        }

        public boolean valid() {
            return errors.isEmpty();
        }

        @Override
        public String stableJson() {
            return new JsonObject()
                    .field("commandName", commandName)
                    .field("actorId", actorId)
                    .field("requestId", requestId)
                    .field("idempotencyKey", idempotencyKey)
                    .field("inputHash", inputHash)
                    .field("valid", valid())
                    .rawField("errors", stringArray(errors))
                    .toJson();
        }
    }

    public enum InputType {
        ID("id"),
        ENUM("enum"),
        TEXT("text");

        private final String id;

        InputType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public interface StableCommandRecord {
        String stableJson();
    }

    public static final class ManagementCommandDescriptors {
        private ManagementCommandDescriptors() {
        }

        public static CommandDescriptor importModelDocument() {
            return new CommandDescriptor(
                    "management.importModelDocument",
                    "Import a model document draft",
                    List.of(
                            new InputField("workspaceId", InputType.ID, true, List.of()),
                            new InputField("sourceFormat", InputType.ENUM, true, List.of("yaml", "json")),
                            new InputField("sourceText", InputType.TEXT, true, List.of())),
                    new ActorRequirement(true, List.of("operator", "admin", "platform"), "workspace"),
                    true,
                    true,
                    "attempt-and-outcome",
                    List.of("draftId", "validationReportId"));
        }
    }

    private static List<InputField> sortedInputFields(List<InputField> inputFields) {
        Objects.requireNonNull(inputFields, "input fields");
        ArrayList<InputField> copy = new ArrayList<>(inputFields);
        for (InputField field : copy) {
            Objects.requireNonNull(field, "input field");
        }
        // Audit G-11 defect fix: this helper claimed to sort but returned declaration order,
        // making CommandDescriptor.stableJson() sensitive to declaration shuffles.
        copy.sort(Comparator.comparing(InputField::name));
        return List.copyOf(copy);
    }

    private static List<String> sortedFieldNames(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        ArrayList<String> sorted = new ArrayList<>(values);
        for (String value : sorted) {
            requireFieldName(value, field);
        }
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static List<String> sortedFieldValues(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        ArrayList<String> sorted = new ArrayList<>(values);
        for (String value : sorted) {
            requireStableId(value, field);
        }
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static List<String> sortedErrors(List<String> errors) {
        Objects.requireNonNull(errors, "validation errors");
        ArrayList<String> copy = new ArrayList<>(errors);
        for (String error : copy) {
            requireText(error, "validation error");
            rejectTransportOwned(error, "validation error");
        }
        // Audit G-11 defect fix: this helper claimed to sort but returned generation order,
        // making CommandValidation.stableJson() (and the first-error audit code) dependent on
        // validation execution order. Errors are prefixed with their diagnostic code, so the
        // natural ordering is the stable code ordering.
        copy.sort(String::compareTo);
        return List.copyOf(copy);
    }

    private static Map<String, String> immutableSortedInput(Map<String, String> input) {
        Objects.requireNonNull(input, "command input");
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            requireFieldName(entry.getKey(), "input field name");
            requireText(entry.getValue(), "input value");
            sorted.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TITAN-GAP006-COMMAND: management command requires " + field);
        }
    }

    private static void requireStableId(String value, String field) {
        requireText(value, field);
        rejectTransportOwned(value, field);
        if (!STABLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-COMMAND: " + field + " must be a stable id");
        }
    }

    private static void requireCommandName(String value, String field) {
        requireText(value, field);
        rejectTransportOwned(value, field);
        if (!COMMAND_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-COMMAND: " + field + " must be a stable command name");
        }
    }

    private static void requireFieldName(String value, String field) {
        requireText(value, field);
        rejectTransportOwned(value, field);
        if (!FIELD_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("TITAN-GAP006-COMMAND: " + field + " must be a stable field name");
        }
    }

    private static void requireSha256(String value, String field) {
        requireText(value, field);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("TITAN-GAP006-COMMAND: " + field + " must use sha256 identity");
        }
    }

    // Exact-token matching (audit G-11 defect fix): substring matching rejected legitimate ids
    // such as 'curl-team' (contains 'url'). See TransportOwnedTerms.
    private static void rejectTransportOwned(String value, String field) {
        if (TransportOwnedTerms.containsTerm(TRANSPORT_OWNED_TERMS, value)) {
            throw new IllegalArgumentException(
                    "TITAN-GAP006-TRANSPORT-METADATA: " + field + " is transport-owned: " + value);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean actorScopeMatches(String requiredScope, String actorScope) {
        if (actorScope == null) {
            return false;
        }
        return actorScope.equals(requiredScope) || actorScope.startsWith(requiredScope + "-");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String jsonArray(List<String> jsonValues) {
        return "[" + String.join(",", jsonValues) + "]";
    }

    private static String stringArray(List<String> values) {
        return jsonArray(values.stream().map(JsonObject::quoted).toList());
    }

    private static final class JsonObject {
        private final List<String> fields = new ArrayList<>();

        JsonObject field(String key, String value) {
            fields.add(quoted(key) + ":" + quoted(value));
            return this;
        }

        JsonObject field(String key, boolean value) {
            fields.add(quoted(key) + ":" + value);
            return this;
        }

        JsonObject rawField(String key, String jsonValue) {
            fields.add(quoted(key) + ":" + jsonValue);
            return this;
        }

        String toJson() {
            return "{" + String.join(",", fields) + "}";
        }

        private static String quoted(String value) {
            StringBuilder escaped = new StringBuilder("\"");
            for (int index = 0; index < value.length(); index++) {
                char ch = value.charAt(index);
                switch (ch) {
                    case '\\' -> escaped.append("\\\\");
                    case '"' -> escaped.append("\\\"");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> escaped.append(ch);
                }
            }
            escaped.append('"');
            return escaped.toString();
        }
    }
}
