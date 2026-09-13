package io.titan.transpiler;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Per-run lowering state: the {@link ParsedSources} plus lazily built, memoized indexes that
 * replace the former quadratic "rescan every compilation unit per lookup" sweeps (audit P2).
 *
 * <p>Each index is built at most once per {@code lower()} (or validation) run:
 * <ul>
 *   <li>{@link #pathFor(Tree)} &mdash; identity index from every source tree node to its
 *       {@link TreePath}, replacing {@code TreePath.getPath(unit, tree)} probes over all
 *       compilation units;</li>
 *   <li>{@link #sourceDeclarationPath(Element)} &mdash; index from class/method/variable
 *       elements to their source declaration path, replacing per-element
 *       {@link TreePathScanner} sweeps over all compilation units.</li>
 * </ul>
 *
 * <p>The accessor names ({@link #trees()}, {@link #types()}, {@link #elements()},
 * {@link #compilationUnits()}) intentionally mirror {@link ParsedSources} so the context can be
 * threaded through the lowering call graph as a drop-in replacement.
 */
public final class LoweringContext {

    /**
     * Memoized contexts keyed weakly on the run's {@link ParsedSources} so existing call sites
     * can reach the shared per-run indexes without threading a new parameter through the whole
     * lowering call graph (audit P2). Values are weak too: the context strongly references its
     * sources, so a strong value would pin the key and the entry would never be reclaimed. A
     * cleared value is rebuilt on demand — a rare CPU cost, never a correctness issue.
     */
    private static final Map<ParsedSources, java.lang.ref.WeakReference<LoweringContext>> SHARED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static LoweringContext forSources(ParsedSources parsedSources) {
        synchronized (SHARED) {
            LoweringContext context = SHARED.get(parsedSources) == null ? null : SHARED.get(parsedSources).get();
            if (context == null) {
                context = new LoweringContext(parsedSources);
                SHARED.put(parsedSources, new java.lang.ref.WeakReference<>(context));
            }
            return context;
        }
    }

    private final ParsedSources parsedSources;
    private Map<Tree, TreePath> treePathsByNode;
    private Map<Element, TreePath> declarationPathsByElement;

    public LoweringContext(ParsedSources parsedSources) {
        if (parsedSources == null) {
            throw new IllegalArgumentException("parsedSources must not be null");
        }
        this.parsedSources = parsedSources;
    }

    public ParsedSources parsedSources() {
        return parsedSources;
    }

    public List<CompilationUnitTree> compilationUnits() {
        return parsedSources.compilationUnits();
    }

    public Trees trees() {
        return parsedSources.trees();
    }

    public Elements elements() {
        return parsedSources.elements();
    }

    public Types types() {
        return parsedSources.types();
    }

    /**
     * Resolves the {@link TreePath} of any node belonging to the parsed sources, or
     * {@code null} when the node is not part of them.
     */
    public TreePath pathFor(Tree tree) {
        if (tree == null) {
            return null;
        }
        if (treePathsByNode == null) {
            treePathsByNode = buildTreePathIndex();
        }
        return treePathsByNode.get(tree);
    }

    /**
     * Declaration path of a source-local class, method or variable element, or {@code null}
     * when the element is not declared in the parsed sources.
     */
    public TreePath sourceDeclarationPath(Element element) {
        if (element == null) {
            return null;
        }
        if (declarationPathsByElement == null) {
            declarationPathsByElement = buildDeclarationIndex();
        }
        return declarationPathsByElement.get(element);
    }

    /** Whether the element's declaration lives in the parsed sources. */
    public boolean isSourceLocal(Element element) {
        return sourceDeclarationPath(element) != null;
    }

    public MethodTree sourceMethodTree(ExecutableElement element) {
        TreePath path = sourceDeclarationPath(element);
        return path != null && path.getLeaf() instanceof MethodTree methodTree ? methodTree : null;
    }

    public VariableTree sourceVariableTree(VariableElement element) {
        TreePath path = sourceDeclarationPath(element);
        return path != null && path.getLeaf() instanceof VariableTree variableTree ? variableTree : null;
    }

    private Map<Tree, TreePath> buildTreePathIndex() {
        Map<Tree, TreePath> index = new IdentityHashMap<>();
        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            TreePath unitPath = new TreePath(unit);
            index.put(unit, unitPath);
            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree tree, Void unused) {
                    if (tree == null) {
                        return null;
                    }
                    TreePath parentPath = getCurrentPath();
                    if (parentPath != null) {
                        index.putIfAbsent(tree, new TreePath(parentPath, tree));
                    }
                    return super.scan(tree, unused);
                }
            }.scan(unitPath, null);
        }
        return index;
    }

    private Map<Element, TreePath> buildDeclarationIndex() {
        Map<Element, TreePath> index = new HashMap<>();
        Trees trees = parsedSources.trees();
        for (CompilationUnitTree unit : parsedSources.compilationUnits()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    record();
                    return super.visitClass(node, unused);
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    record();
                    return super.visitMethod(node, unused);
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    record();
                    return super.visitVariable(node, unused);
                }

                private void record() {
                    Element element = trees.getElement(getCurrentPath());
                    if (element != null) {
                        index.putIfAbsent(element, getCurrentPath());
                    }
                }
            }.scan(unit, null);
        }
        return index;
    }
}
