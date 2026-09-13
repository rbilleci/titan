package io.titan.transpiler.tir;

import io.titan.catalog.CatalogGenerator;
import io.titan.introspect.DdlSchemaParser;
import io.titan.introspect.Dialect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Regenerates the example catalog from checked-in DDL; never relies on a prior quickstart build. */
final class ReadmeBusinessExampleSupport {
    static final Path EXAMPLE = Path.of("../examples/quickstart");
    static final Path SOURCE = EXAMPLE.resolve("src/main/java/example/BusinessRoutines.java");

    private ReadmeBusinessExampleSupport() {}

    static List<TranspilationPipeline.GeneratedSql> transpile(String dialect, Path generatedDir)
            throws IOException {
        String ddl = Files.readString(EXAMPLE.resolve("src/main/resources/db/schema/inventory.sql"));
        var schema = new DdlSchemaParser().parse(ddl,
                dialect.equals("postgresql") ? Dialect.POSTGRESQL : Dialect.MYSQL, "app");
        var sources = new ArrayList<Path>();
        sources.add(SOURCE);
        for (var entry : new CatalogGenerator().generate(schema, "generated.catalog").entrySet()) {
            Path file = generatedDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            sources.add(file);
        }
        return new TranspilationPipeline().transpile(sources, List.of(), List.of(dialect), List.of("app"), true);
    }
}
