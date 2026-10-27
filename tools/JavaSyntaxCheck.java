import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Parses project Java sources with the JDK compiler without resolving Minecraft dependencies. */
public final class JavaSyntaxCheck {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java JavaSyntaxCheck.java <source-directory>");
            System.exit(2);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("A full JDK is required (no system Java compiler found).");
            System.exit(2);
        }
        Path sourceRoot = Path.of(args[0]);
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            paths = stream.filter(path -> path.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(paths);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                List.of("-proc:none", "-source", "21", "-target", "21"), null, units);
            for (CompilationUnitTree ignored : task.parse()) {
                // Parsing only: symbol resolution needs the Minecraft/NeoForge classpath.
            }
        }
        List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) errors.add(diagnostic);
        }
        if (!errors.isEmpty()) {
            for (Diagnostic<? extends JavaFileObject> error : errors) {
                System.err.printf("%s:%d:%d: %s%n", error.getSource() == null ? "<unknown>" : Path.of(error.getSource().toUri()).getFileName(),
                    error.getLineNumber(), error.getColumnNumber(), error.getMessage(null));
            }
            System.exit(1);
        }
        System.out.println("Java syntax parse passed for " + paths.size() + " source files.");
    }
}
