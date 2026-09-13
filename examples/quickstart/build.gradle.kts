plugins {
    java
    id("io.titan.gradle")
}

group = "example"
version = "0.1.0"

repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation("io.titan:titan-dsl:0.1.0")
    compileOnly("org.jspecify:jspecify:1.0.0")
    titanJdbc("org.postgresql:postgresql:42.7.4")
    titanJdbc("com.mysql:mysql-connector-j:8.4.0")
}

val targetDialect = providers.gradleProperty("targetDialect").orElse("postgresql")
titan {
    database {
        dialect.set(targetDialect)
        schemas.set(listOf("app"))
        ddlDir.set("src/main/resources/db/schema")
        ddlMode.set("parser")
    }
    catalog { targetPackage.set("generated.catalog") }
    transpiler {
        // JDBC SQL is native to one target. Run this example separately for each dialect.
        targets.set(targetDialect.map { listOf(it) })
        sqlSafety.set("strict")
    }
}

tasks.register("verifyExample") {
    group = "verification"
    description = "Checks generated catalog, routine SQL, package metadata, and analysis reports without a database."
    dependsOn("titanPackage", "titanJdbcCompatReport", "titanPermissiveScopesReport")
    val target = targetDialect.get()
    val outputs = layout.buildDirectory
    doLast {
        val root = outputs.get().asFile
        check(root.resolve("generated/sources/titan/generated/catalog/app/tables/Accounts.java").isFile)
        check(root.resolve("generated/sources/titan/generated/catalog/app/tables/Inventory.java").isFile)
        check(root.resolve("generated/sources/titan/generated/catalog/app/tables/Reservations.java").isFile)
        val sqlFiles = root.resolve("generated/sql/titan/$target").walkTopDown()
            .filter { it.isFile && it.extension == "sql" }.toList()
        check(sqlFiles.isNotEmpty()) { "No generated SQL for $target" }
        val sql = sqlFiles.joinToString("\n") { it.readText() }
        check(sql.contains("credit_account", ignoreCase = true)) { "Missing creditAccount procedure" }
        check(sql.contains("add_points", ignoreCase = true)) { "Missing addPoints function" }
        check(sql.contains("reserve_stock", ignoreCase = true)) { "Missing reserveStock procedure" }
        check(sql.contains("reserve_stock_jdbc", ignoreCase = true)) { "Missing JDBC alternative" }
        val reservation = sqlFiles.single { it.name == "example_BusinessRoutines__reserveStock.sql" }.readText()
        check(reservation.contains("FOR UPDATE")) { "DSL reservation must retain its locking read" }
        check(!reservation.contains("EXECUTE '") && !reservation.contains("PREPARE ")) {
            "The DSL reservation should lower to structured SQL, not dynamic JDBC SQL"
        }
        check(sql.contains("monthly_charge_cents", ignoreCase = true)) { "Missing monthlyChargeCents function" }
        check(sql.contains("FOR UPDATE")) { "Inventory read must retain its row lock" }
        check(sql.contains("insufficient stock")) { "Inventory guard must survive transpilation" }
        check(sql.contains("INSERT INTO app.reservations")) { "Missing reservation write" }
        check(sql.contains("UPDATE app.accounts SET balance = balance +", ignoreCase = true))
        check(!sql.contains("java.sql.Connection")) { "Connection must not become a SQL parameter" }
        for (file in listOf("titan/migrations/titan-artifact.json",
                "titan/migrations/titan-install-plan.json",
                "reports/titan/jdbc-compat-report.json", "reports/titan/permissive-scopes.json")) {
            check(root.resolve(file).isFile) { "Missing $file" }
        }
        println("Verified catalog, native $target routines, migration package, and analysis reports.")
    }
}

tasks.named("check") { dependsOn("verifyExample") }
