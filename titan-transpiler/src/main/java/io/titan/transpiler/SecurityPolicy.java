package io.titan.transpiler;

import java.util.List;

/**
 * ATG-017b: the deployment-privilege policy declared on a {@code @SecurityDefiner} routine —
 * owner role, least-privilege execute grants, and whether PUBLIC is revoked. Emitted as reviewable
 * {@code ALTER … OWNER} / {@code REVOKE ALL … FROM PUBLIC} / {@code GRANT EXECUTE} artifacts separate
 * from the routine body. {@link #NONE} means no privilege policy was declared (only the default
 * {@code SECURITY DEFINER} + auto-pinned {@code search_path} of ATG-017a apply).
 */
public record SecurityPolicy(String ownerRole, List<String> executeRoles, boolean revokePublic) {

    public static final SecurityPolicy NONE = new SecurityPolicy("", List.of(), false);

    public SecurityPolicy {
        ownerRole = ownerRole == null ? "" : ownerRole;
        executeRoles = executeRoles == null ? List.of() : List.copyOf(executeRoles);
    }

    /** True when any privilege artifact must be emitted (owner change, PUBLIC revoke, or a grant). */
    public boolean hasAny() {
        return !ownerRole.isBlank() || !executeRoles.isEmpty() || revokePublic;
    }
}
