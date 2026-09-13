import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

// Shared conventions for the five core modules.
//
// - Repositories live in settings.gradle.kts (dependencyResolutionManagement); the legacy
//   allprojects { repositories { ... } } block is gone.
// - titan-intellij-plugin is a separate standalone build with Java 17 / JUnit 4
//   conventions. It is not included by the root settings.
// - Docker split: plain `test` excludes @Tag("docker"); the `integrationTest` task added per
//   java module runs exactly those tests. `check` intentionally does NOT depend on
//   integrationTest so `./gradlew build`/`test` stay green without Docker.

plugins {
    alias(libs.plugins.errorprone) apply false
}

allprojects {
    group = "io.titan"
    version = providers.gradleProperty("titanVersion").get()
}

subprojects {
    apply(plugin = "net.ltgt.errorprone")

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set("https://github.com/rbilleci/titan")
                    licenses {
                        license {
                            name.set("GNU General Public License, version 3 only (GPL-3.0-only)")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                            distribution.set("repo")
                        }
                    }
                    scm {
                        url.set("https://github.com/rbilleci/titan")
                        connection.set("scm:git:https://github.com/rbilleci/titan.git")
                        developerConnection.set("scm:git:ssh://git@github.com/rbilleci/titan.git")
                    }
                    issueManagement {
                        system.set("GitHub")
                        url.set("https://github.com/rbilleci/titan/issues")
                    }
                }
            }
        }
    }

    dependencies {
        "errorprone"(rootProject.libs.errorprone.core)
    }

    plugins.withId("java") {
        dependencies {
            constraints {
                val scope = if (project.name in listOf("titan-management", "titan-management-routines"))
                    "testImplementation" else "implementation"
                add(scope, rootProject.libs.commons.compress) {
                    because("Avoid vulnerable archive handling pulled transitively by Testcontainers")
                }
            }
        }
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            withSourcesJar()
            withJavadocJar()
        }

        // Gradle 9 no longer places the JUnit Platform launcher on the test runtime classpath
        // implicitly (it did through Gradle 8), so every module must declare it explicitly. The
        // version is managed by the JUnit BOM, keeping it aligned with junit-jupiter.
        dependencies {
            "testRuntimeOnly"(platform(rootProject.libs.junit.bom))
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }

        val sourceSets = extensions.getByType<SourceSetContainer>()

        // Forward titan.* system properties from the Gradle invocation into every test JVM.
        // The generative replay/sweep protocol (-Dtitan.phaseb.seed=..., sweep tiers) and the
        // golden-file regeneration flag (-Dtitan.golden.update=true) are read by the tests via
        // System.getProperty; without this forwarding the properties stop at the Gradle daemon
        // JVM and the replay tests silently assumption-skip (tests=1 skipped=1) — every replay
        // loop in scripts/ and CI was a no-op before this block. systemPropertiesPrefixedBy is
        // the configuration-cache-safe accessor: the property set is tracked as a build input.
        val titanTestProperties = providers.systemPropertiesPrefixedBy("titan.").get()
        tasks.withType<Test>().configureEach {
            titanTestProperties.forEach { (key, value) -> systemProperty(key, value) }
        }

        tasks.named<Test>("test") {
            useJUnitPlatform {
                excludeTags("docker")
            }
        }

        tasks.register<Test>("integrationTest") {
            description = "Runs Docker-dependent (Testcontainers) tests tagged @Tag(\"docker\")."
            group = "verification"
            testClassesDirs = sourceSets["test"].output.classesDirs
            classpath = sourceSets["test"].runtimeClasspath
            useJUnitPlatform {
                includeTags("docker")
            }
            shouldRunAfter(tasks.named("test"))
        }

        tasks.withType<JavaCompile>().configureEach {
            options.errorprone {
                // Generated sources follow their template, not the checker; analysing them would
                // only ever flag the template anyway. (The titan-dsl arity ladder that motivated
                // this moved to its own repo, which carries an equivalent exclusion.)
                excludedPaths.set(".*/build/generated/.*")
                disableWarningsInGeneratedCode.set(true)

                // Disabled bug patterns — each conflicts with an established codebase idiom.
                // Everything else runs at default severity (ERROR-level checks fail the build).
                //
                // VoidUsed: the lowerer/discovery code is built on com.sun.source TreeScanner
                // visitors instantiated with Void type parameters; "use literal null" rewrites
                // add nothing and fight the compiler-API idiom (51 sites).
                disable("VoidUsed")
                // PreferInstanceofOverGetKind: Tree.getKind() switch dispatch is the
                // deliberate lowering idiom (kept exhaustive on the javac Tree kinds);
                // converting to instanceof chains would lose that shape (11 sites).
                disable("PreferInstanceofOverGetKind")
                // StringCaseLocaleUsage: case folding here is applied to ASCII SQL
                // identifiers/keywords where locale cannot matter; threading Locale.ROOT
                // through ~30 sites is churn without a behavior change. Revisit if any
                // user-facing text is ever case-folded.
                disable("StringCaseLocaleUsage")
                // StringSplitter: the suggested replacement is Guava's Splitter, which the
                // project-wide no-framework-dependencies rule forbids; the flagged
                // String.split calls parse Titan-controlled formats (12 sites).
                disable("StringSplitter")
                // ImmutableEnumChecker: enum List<String> fields hold List.of(...) immutables;
                // the checker cannot see through the interface type, and annotating with
                // @Immutable would add the errorprone-annotations artifact as a compile
                // dependency of the modules (disallowed) (7 sites).
                disable("ImmutableEnumChecker")
                // InjectOnConstructorOfAbstractClass: @Inject on abstract task/extension
                // constructors is the canonical Gradle plugin pattern (titan-gradle-plugin).
                disable("InjectOnConstructorOfAbstractClass")
            }
        }
    }

    tasks.withType<Jar>().configureEach {
        from(rootProject.files("LICENSE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
        manifest {
            attributes("Implementation-Title" to project.name, "Implementation-Version" to project.version)
        }
    }
}
