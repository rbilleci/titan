package io.titan.transpiler;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RecordDefinitionDiscovery {

    private static final Pattern RECORD_HEADER_PATTERN = Pattern.compile("record\\s+\\w+\\s*\\(([^)]*)\\)");

    public List<DiscoveredRecordDefinition> discover(ParsedSources parsed) {
        List<DiscoveredRecordDefinition> discovered = new ArrayList<>();

        for (CompilationUnitTree unit : parsed.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                // Source-local enclosing-type chain (no package), innermost last. The qualified
                // record identity derives from it (B-2 / TG-BLK-005): generated SQL names must
                // distinguish same-simple-name records declared in different enclosing types.
                private final List<String> enclosingTypes = new ArrayList<>();

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    String simpleName = node.getSimpleName().toString();
                    boolean named = !simpleName.isBlank();
                    if (named) {
                        enclosingTypes.add(simpleName);
                    }
                    try {
                        if (node.getKind() == Tree.Kind.RECORD) {
                            discovered.add(discoverRecord(node, unit, parsed));
                        }
                        return super.visitClass(node, unused);
                    } finally {
                        if (named) {
                            enclosingTypes.remove(enclosingTypes.size() - 1);
                        }
                    }
                }

                private DiscoveredRecordDefinition discoverRecord(ClassTree node, CompilationUnitTree unit, ParsedSources parsed) {
                    List<DiscoveredRecordDefinition.RecordComponentDef> components = parseRecordComponents(node, unit, parsed);

                    long pos = parsed.trees().getSourcePositions().getStartPosition(unit, node);
                    int line = (int) unit.getLineMap().getLineNumber(pos);

                    // The chain already ends with this record's own name (visitClass pushes
                    // before dispatching), so the join is the full source-local qualified name.
                    return new DiscoveredRecordDefinition(
                            String.join(".", enclosingTypes),
                            node.getSimpleName().toString(),
                            List.copyOf(components),
                            unit.getSourceFile().getName(),
                            line);
                }

                private List<DiscoveredRecordDefinition.RecordComponentDef> parseRecordComponents(
                        ClassTree node,
                        CompilationUnitTree unit,
                        ParsedSources parsed
                ) {
                    String text = extractSourceText(node, unit, parsed);
                    Matcher matcher = RECORD_HEADER_PATTERN.matcher(text);
                    if (!matcher.find()) {
                        return List.of();
                    }
                    String header = matcher.group(1).trim();
                    if (header.isEmpty()) {
                        return List.of();
                    }

                    List<DiscoveredRecordDefinition.RecordComponentDef> components = new ArrayList<>();
                    for (String rawPart : header.split(",")) {
                        String part = rawPart.trim();
                        if (part.isEmpty()) {
                            continue;
                        }
                        String[] tokens = part.split("\\s+");
                        if (tokens.length < 2) {
                            continue;
                        }
                        String name = tokens[tokens.length - 1];
                        String typeName = String.join(" ", Arrays.copyOf(tokens, tokens.length - 1));
                        components.add(new DiscoveredRecordDefinition.RecordComponentDef(name, typeName));
                    }
                    return components;
                }

                private String extractSourceText(ClassTree node, CompilationUnitTree unit, ParsedSources parsed) {
                    try {
                        CharSequence source = unit.getSourceFile().getCharContent(true);
                        long start = parsed.trees().getSourcePositions().getStartPosition(unit, node);
                        long end = parsed.trees().getSourcePositions().getEndPosition(unit, node);
                        if (start < 0 || end < 0 || end <= start || end > source.length()) {
                            return node.toString();
                        }
                        return source.subSequence((int) start, (int) end).toString();
                    } catch (Exception ignored) {
                        return node.toString();
                    }
                }
            }.scan(unit, null);
        }

        return List.copyOf(discovered);
    }
}
