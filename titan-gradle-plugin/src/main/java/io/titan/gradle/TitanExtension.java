package io.titan.gradle;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public abstract class TitanExtension extends TitanCodegenExtension {

    private final Transpiler transpiler;
    private final Deployment deployment;
    private final Verification verification;

    @Inject
    public TitanExtension(ObjectFactory objects) {
        super(objects);
        this.transpiler = objects.newInstance(Transpiler.class);
        this.deployment = objects.newInstance(Deployment.class);
        this.verification = objects.newInstance(Verification.class);
    }

    public Transpiler getTranspiler() {
        return transpiler;
    }

    public void transpiler(Action<? super Transpiler> action) {
        action.execute(transpiler);
    }

    public Deployment getDeployment() {
        return deployment;
    }

    public void deployment(Action<? super Deployment> action) {
        action.execute(deployment);
    }

    public Verification getVerification() {
        return verification;
    }

    public void verification(Action<? super Verification> action) {
        action.execute(verification);
    }

    public abstract static class Transpiler {
        public abstract ListProperty<String> getTargets();
        public abstract Property<String> getOutputDir();

        /**
         * @deprecated No-op since Phase 0.8 (audit B-6 / TG-BLK-002). Transpile-time validation
         *     (dialect feasibility, name-collision and null-safety checks) is unconditional, so
         *     this flag controls nothing — the pipeline never reads it. A real consumer shipped
         *     {@code strictMode.set(true)} believing it tightened validation; it did not. Retained
         *     only so existing build scripts continue to compile; scheduled for removal. Remove the
         *     {@code transpiler.strictMode} line from your build — it has no effect either way.
         */
        @Deprecated(forRemoval = true)
        public abstract Property<Boolean> getStrictMode();

        /**
         * Enables the transpiler's 32-bit wraparound emulation (audit B-7 / TG-BLK-001, plan 2.4 /
         * audit E-11). When {@code true}, int-typed {@code +}/{@code -}/{@code *} on provably-int
         * operands are rewritten to the {@code java_int_add/sub/mul} runtime helpers so Java's
         * silent 32-bit overflow wrap is reproduced bit-exactly in the generated SQL.
         *
         * <p>Defaults to {@code false} ("fail-loud parity": integer overflow raises on both
         * dialects instead of silently wrapping), matching the pipeline default. Integer
         * division/modulo Java parity (audit E-7) is always on regardless of this flag.</p>
         */
        public abstract Property<Boolean> getStrictWraparound();

        public abstract Property<Boolean> getObservability();
        public abstract Property<Boolean> getDebugMode();
        public abstract ListProperty<String> getSensitiveColumns();

        /**
         * Build-level raw-SQL safety mode for the JDBC front-end / {@code titanJdbcCompatReport}
         * (WS-C; {@code docs/transpilable-jdbc-subset.md} §4). {@code "strict"} (default) rejects
         * non-constant SQL text spliced into {@code prepareStatement}/{@code execute};
         * {@code "permissive"} routes it to a single-dialect {@code RawSql} passthrough instead.
         *
         * <p>This is the <b>non-greppable</b>, whole-build surface for bulk legacy migration; prefer
         * the surgical {@code @SqlSafety(PERMISSIVE)} method/class annotation, which overrides this
         * (narrowest scope wins). Any value other than {@code "permissive"} resolves to strict.</p>
         */
        public abstract Property<String> getSqlSafety();
    }

    /**
     * Where {@code titanVerifyInstall} installs and verifies the packaged artifacts
     * (plan 4.4, audit G-10).
     *
     * <p>When {@code jdbcUrl} is set the packaged SQL is applied to that database (drivers come
     * from the {@code titanJdbc} configuration; {@code dialect} names the target dialect).
     * When unset — the default — one scratch Testcontainers database per packaged dialect is
     * provisioned and discarded, so verification never touches a real environment.</p>
     */
    public abstract static class Verification {
        public abstract Property<String> getJdbcUrl();
        public abstract Property<String> getUsername();
        public abstract Property<String> getPassword();
        public abstract Property<String> getDialect();

        /** Fail the build when verification reports drift or diagnostics (default {@code true}). */
        public abstract Property<Boolean> getFailOnVerificationError();
    }

    public abstract static class Deployment {
        public abstract Property<String> getMode();

        /**
         * Directory the packaged migration artifacts are written to.
         *
         * <p>Defaults to {@code build/titan/migrations} so the build never mutates the source
         * tree. Writing migrations into the source tree (for example
         * {@code src/main/resources/db/migration} so Flyway picks them up from resources) is an
         * explicit opt-in: set this property to that path and commit the generated files
         * deliberately.</p>
         */
        public abstract Property<String> getMigrationsDir();
    }
}
