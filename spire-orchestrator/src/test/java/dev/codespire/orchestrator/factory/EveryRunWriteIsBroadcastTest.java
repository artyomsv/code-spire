package dev.codespire.orchestrator.factory;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Derives writers from Java's syntax tree, including SQL constants; comments cannot satisfy it. */
class EveryRunWriteIsBroadcastTest {

    private static final Pattern WRITE = Pattern.compile("\\b(?:UPDATE|INSERT\\s+INTO)\\s+factory_run\\b");

    @Test
    void everyRunWriteReachesTheBroadcaster() throws Exception {
        Path source = Path.of(System.getProperty("spire.factoryProjectionSource",
                "src/main/java/dev/codespire/orchestrator/factory/FactoryRunProjection.java"));
        try (var files = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, files, null,
                    List.of("-proc:none"), null, files.getJavaFileObjects(source));
            var unit = task.parse().iterator().next();
            ClassTree projection = (ClassTree) unit.getTypeDecls().getFirst();
            Map<String, Tree> fields = new HashMap<>();
            List<MethodTree> methods = new ArrayList<>();
            for (Tree member : projection.getMembers()) {
                if (member instanceof VariableTree field) fields.put(field.getName().toString(), field.getInitializer());
                if (member instanceof MethodTree method && method.getBody() != null) methods.add(method);
            }
            Map<String, Set<String>> calls = new HashMap<>();
            Set<String> writers = new HashSet<>();
            for (MethodTree method : methods) {
                String name = method.getName().toString();
                calls.computeIfAbsent(name, ignored -> new HashSet<>()).addAll(callsIn(method.getBody()));
                if (writes(method.getBody(), fields, new HashSet<>())) writers.add(name);
            }
            assertFalse(writers.isEmpty(), "the scan must find real writes, not pass on an unreadable source");
            Set<String> missing = new HashSet<>();
            for (String writer : writers) {
                if (!covered(writer, calls, new HashSet<>())) missing.add(writer);
            }
            assertTrue(missing.isEmpty(), "factory_run writers without a broadcast: " + missing);
        }
    }

    private static boolean writes(Tree tree, Map<String, Tree> fields, Set<String> visited) {
        Boolean result = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean visitLiteral(LiteralTree literal, Void unused) {
                return literal.getValue() instanceof String text && WRITE.matcher(text).find();
            }

            @Override
            public Boolean visitIdentifier(IdentifierTree identifier, Void unused) {
                String name = identifier.getName().toString();
                return fields.containsKey(name) && visited.add(name) && writes(fields.get(name), fields, visited);
            }

            @Override
            public Boolean reduce(Boolean left, Boolean right) {
                return Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right);
            }
        }.scan(tree, null);
        return Boolean.TRUE.equals(result);
    }

    private static Set<String> callsIn(Tree body) {
        Set<String> calls = new HashSet<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
                // Local calls only; ps.executeUpdate and other collaborators are not this graph.
                if (invocation.getMethodSelect() instanceof IdentifierTree identifier) {
                    calls.add(identifier.getName().toString());
                }
                return super.visitMethodInvocation(invocation, unused);
            }
        }.scan(body, null);
        return calls;
    }

    private static boolean reachesPush(String method, Map<String, Set<String>> calls, Set<String> seen) {
        if (method.equals("push")) return true;
        if (!seen.add(method)) return false;
        return calls.getOrDefault(method, Set.of()).stream().anyMatch(next -> reachesPush(next, calls, seen));
    }

    private static boolean covered(String method, Map<String, Set<String>> calls, Set<String> seen) {
        if (reachesPush(method, calls, new HashSet<>())) return true;
        if (!seen.add(method)) return false;
        // started/finished/failed are private helpers: every caller must provide the push.
        List<String> callers = calls.keySet().stream().filter(key -> calls.get(key).contains(method)).toList();
        return !callers.isEmpty() && callers.stream().allMatch(caller -> covered(caller, calls, new HashSet<>(seen)));
    }
}
