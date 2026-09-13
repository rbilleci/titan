// titan-management-routines: the dogfooded management store's MUTATION routines (B-8 / GAP-006,
// Phase B). The @StoredProcedure sources here are transpiled to PostgreSQL + MySQL and packaged
// as a deployment bundle (schema DDL first, then routines) that titan-management ships as a
// resource and applies from its JDBC transaction envelope (Phase B3/B4).
//
// BUILD ARCHITECTURE (the fix). This is a COMMITTED IN-TREE subproject, so it must NOT apply the
// io.titan.gradle plugin: a project cannot includeBuild its own root, which previously forced the
// plugin (and its transitive titan-transpiler) to resolve from mavenLocal — a published, possibly
// STALE artifact. A clean CI checkout then could not `./gradlew build` this module without first
// publishing the plugin to mavenLocal, and it transpiled against the published transpiler rather
// than the in-build source (the previous agent hit a 3-day-stale-mavenLocal G2 bug). Instead, the
// `transpileManagementRoutines` task below invokes the IN-BUILD titan-transpiler's
// TranspilationPipeline.transpile API DIRECTLY (the same call EmitterDeployabilityIT uses), with no
// plugin application and no mavenLocal dependency.
//
// DEPENDENCY RULE (Phase B design, enforced): the ROUTINE sources (src/main/java) depend on
// titan-dsl ONLY. They must NOT depend on titan-management — the SQL is the artifact that flows
// INTO titan-management, never the other way. (The build-only transpile classpath additionally
// carries titan-transpiler; that is generator tooling, not a routine dependency, and lives in a
// separate `generator` source set so it never leaks into the routine compile/runtime classpath.)

plugins {
    java
}

// The in-build transpile classpath: the IN-BUILD titan-transpiler (the pipeline + emitters) plus
// titan-dsl (the surface the routine source is parsed/resolved against). This is the seam that
// replaces the plugin's mavenLocal-resolved transpiler — everything is a project() reference, so a
// clean checkout transpiles against live in-repo source with no publish step.
val transpilerClasspath: Configuration by configurations.creating

// A build-only source set holding the tiny generator main that drives TranspilationPipeline. It is
// compiled against transpilerClasspath and is NEVER packaged in the jar (it is not part of `main`),
// so the routine jar stays free of any transpiler dependency.
val generator: SourceSet by sourceSets.creating

dependencies {
    // The ONLY dependency of the ROUTINE sources: the titan-dsl surface the @StoredProcedure
    // routines are authored against (Table, Column, DSL.*, @StoredProcedure). Resolved from the
    // in-build project so the routines compile against the live DSL, never a stale published jar.
    implementation("io.titan:titan-dsl:0.1.0")

    // The in-build transpiler + the composite-build dsl on the transpile classpath (the plugin-free
    // seam). titan-dsl resolves to the live ../titan-dsl included build, so routines still compile
    // against live DSL source, never a stale published jar.
    transpilerClasspath(project(":titan-transpiler"))
    transpilerClasspath("io.titan:titan-dsl:0.1.0")

    // The generator main needs the transpiler API to compile and run.
    "generatorImplementation"(project(":titan-transpiler"))
    "generatorImplementation"("io.titan:titan-dsl:0.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql.connector.j)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Where the generated SQL bundle lands. The generator writes <dir>/io/titan/management/sql/{dialect}/
// (schema.sql + routines.sql + routines/<method>.sql), so the resource path in the jar is
// io/titan/management/sql/... — what titan-management loads at runtime.
val managementSqlDir = layout.buildDirectory.dir("generated/management-sql")
val ddlSourceDir = layout.projectDirectory.dir("src/main/resources/ddl")

// The transpile classpath is passed to the JavaExec generator via a file (File.pathSeparator
// joined) rather than argv, to avoid command-line length limits and quoting; written by this task.
val transpileClasspathFile = layout.buildDirectory.file("tmp/transpileManagementRoutines/classpath.txt")
val routineSourceFile = layout.projectDirectory.file("src/main/java/io/titan/management/routines/ManagementRoutines.java")

val transpileManagementRoutines = tasks.register<JavaExec>("transpileManagementRoutines") {
    description = "Transpiles the @StoredProcedure management routines to a PG+MySQL SQL bundle using the IN-BUILD titan-transpiler (no plugin, no mavenLocal)."
    group = "build"

    // Run the generator main; its classpath is the build-only generator source set (the main +
    // titan-transpiler + titan-dsl). The transpile INPUTS (the routine source classpath) are passed
    // as program args, NOT this runtime classpath.
    classpath = generator.runtimeClasspath
    mainClass.set("io.titan.management.routines.gen.ManagementRoutinesBundleGenerator")

    // Reproducible-build inputs/outputs (up-to-date checking): the routine source, the hand-authored
    // DDL, and the resolved transpile classpath jars all feed the emitted bundle.
    inputs.file(routineSourceFile)
    inputs.dir(ddlSourceDir)
    inputs.files(transpilerClasspath)
    outputs.dir(managementSqlDir)

    doFirst {
        val classpathText = transpilerClasspath.files.joinToString(File.pathSeparator) { it.absolutePath }
        val cpFile = transpileClasspathFile.get().asFile
        cpFile.parentFile.mkdirs()
        cpFile.writeText(classpathText)
    }

    argumentProviders.add {
        listOf(
            routineSourceFile.asFile.absolutePath,
            transpileClasspathFile.get().asFile.absolutePath,
            ddlSourceDir.asFile.absolutePath,
            managementSqlDir.get().asFile.absolutePath,
        )
    }
}

// Package the generated bundle into the jar at io/titan/management/sql/... (the runtime resource
// path titan-management loads). The hand-authored ddl/ resources are also still packaged (the
// generator copies the same DDL into the bundle as schema.sql; the original ddl/ resources remain
// for the deployability IT, which reads them from src/main/resources/ddl).
tasks.named<ProcessResources>("processResources") {
    from(transpileManagementRoutines)
}

// The no-Docker transpile-inspection test reads the emitted routine SQL from the generated bundle;
// surface its directory as a system property and make `test` depend on the transpile.
tasks.named<Test>("test") {
    dependsOn(transpileManagementRoutines)
    systemProperty(
        "titan.management.routines.sql.dir",
        managementSqlDir.get().asFile.absolutePath
    )
}

// The deployability probe (@Tag("docker")) deploys the schema DDL + the transpiled routine bundle to
// scratch PG+MySQL containers; it reads the generated bundle directory directly.
tasks.named<Test>("integrationTest") {
    dependsOn(transpileManagementRoutines)
    systemProperty(
        "titan.management.routines.sql.dir",
        managementSqlDir.get().asFile.absolutePath
    )
}
