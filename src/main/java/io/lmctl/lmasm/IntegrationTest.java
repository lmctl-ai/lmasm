package io.lmctl.lmasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Integration test for the lmasm host, runnable with {@code mvn verify} on any JDK.
 *
 * <p>Compiles the fixture libraries and custom code from {@code fixtures/src} with the system
 * Java compiler, then verifies the host contract end to end on the JVM:
 *
 * <ul>
 *   <li>{@code asm_classes} lists jar, directory, and standalone class-file inputs;
 *   <li>{@code asm_refs} extracts main-scope and test-scope library references;
 *   <li>the dependency-analysis arithmetic the lmcode app performs (used / test-used counts);
 *   <li>the registry matches the schema bytes served by the CLI;
 *   <li>the CLI answers {@code --version}, {@code --lmcode-schema --json}, and
 *       {@code --lmcode-signatures} with consistent bytes.
 * </ul>
 *
 * <p>The lmcode application itself is exercised by {@code test.sh} (and CI), which additionally
 * needs the lmscript compiler from {@code @lmctl-ai/lmctl}.
 */
public final class IntegrationTest {
  private IntegrationTest() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
    Path work = Files.createTempDirectory("lmasm-it");
    boolean ok = false;
    try {
      compileFixtures(root, work);
      verifyHostFunctions(work);
      verifyAnalysis(work);
      verifySchemaAndCli();
      ok = true;
    } finally {
      deleteRecursively(work);
    }
    if (!ok) {
      throw new IllegalStateException("integration test failed");
    }
    System.out.println("PASS lmasm integration test");
  }

  /** Compiles fixtures/src the same way fixtures/build-fixtures.sh does. */
  private static void compileFixtures(Path root, Path work) throws IOException {
    Path sources = root.resolve("fixtures/src");
    if (!Files.isDirectory(sources)) {
      throw new IOException("missing fixture sources: " + sources);
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IOException("a JDK (javax.tools) is required to compile fixtures");
    }
    compile(compiler, work.resolve("liba"), List.of(), sources.resolve("com/acme/liba"));
    compile(compiler, work.resolve("libb"), List.of(), sources.resolve("com/acme/libb"));
    compile(
        compiler,
        work.resolve("appmain"),
        List.of(work.resolve("liba"), work.resolve("libb")),
        sources.resolve("com/acme/app"));
    compile(
        compiler,
        work.resolve("apptest"),
        List.of(work.resolve("liba"), work.resolve("libb")),
        sources.resolve("com/acme/app"));
    createJar(
        work.resolve("liba"), work.resolve("jars/liba.jar"),
        List.of("com/acme/liba/A1.java", "com/acme/liba/A2.java", "com/acme/liba/A3.java"),
        sources);
    createJar(
        work.resolve("libb"), work.resolve("jars/libb.jar"),
        List.of("com/acme/libb/B1.java", "com/acme/libb/B2.java"),
        sources);
    createJar(
        work.resolve("apptest"), work.resolve("apptest.jar"),
        List.of("com/acme/app/Test1.java", "com/acme/app/Test2.java"),
        sources);
  }

  private static void compile(
      JavaCompiler compiler, Path output, List<Path> classpath, Path... sourceDirs)
      throws IOException {
    List<String> options = new ArrayList<>(List.of("--release", "21", "-d", output.toString()));
    if (!classpath.isEmpty()) {
      options.add("-cp");
      options.add(joinPathList(classpath));
    }
    List<Path> units = new ArrayList<>();
    for (Path dir : sourceDirs) {
      // Only compile the units that belong to this output (main vs test split).
      String kind = output.getFileName().toString();
      boolean test = kind.equals("apptest");
      try (var stream = Files.list(dir)) {
        for (Path file : stream.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
          String name = file.getFileName().toString();
          if (name.startsWith("Test") == test) {
            units.add(file);
          }
        }
      }
    }
    if (units.isEmpty()) {
      throw new IOException("no fixture sources selected for " + output);
    }
    List<String> arguments = new ArrayList<>(options);
    for (Path unit : units) {
      arguments.add(unit.toString());
    }
    int code = compiler.run(null, null, null, arguments.toArray(new String[0]));
    if (code != 0) {
      throw new IOException("javac exited " + code + " for " + output);
    }
  }

  private static void createJar(Path classes, Path jar, List<String> sourceUnits, Path srcRoot)
      throws IOException {
    Files.createDirectories(jar.getParent() != null ? jar.getParent() : Path.of("."));
    Set<String> entries = new TreeSet<>();
    for (String unit : sourceUnits) {
      entries.add(unit.substring(0, unit.length() - ".java".length()) + ".class");
    }
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file)) {
      for (String entry : entries) {
        Path classFile = classes.resolve(entry);
        if (!Files.isRegularFile(classFile)) {
          throw new IOException("fixture class missing: " + classFile);
        }
        out.putNextEntry(new JarEntry(entry.replace('\\', '/')));
        out.write(Files.readAllBytes(classFile));
        out.closeEntry();
      }
    }
  }

  private static void verifyHostFunctions(Path work) throws IOException {
    assertEquals(
        List.of("com.acme.liba.A1", "com.acme.liba.A2", "com.acme.liba.A3"),
        AsmHost.listClasses(work.resolve("jars/liba.jar")),
        "asm_classes jar listing");
    assertEquals(
        List.of("com.acme.app.Main1", "com.acme.app.Main2"),
        AsmHost.listClasses(work.resolve("appmain")),
        "asm_classes directory listing");

    Set<String> mainRefs =
        new TreeSet<>(
            AsmHost.referencedClasses(
                AsmHost.readClassBytes(work.resolve("appmain"), "com.acme.app.Main1"),
                "com.acme.app.Main1"));
    assertTrue(mainRefs.contains("com.acme.liba.A1"), "Main1 references A1");
    assertTrue(mainRefs.contains("com.acme.liba.A2"), "Main1 references A2");
    assertTrue(!mainRefs.contains("com.acme.liba.A3"), "Main1 does not reference A3");

    Set<String> testRefs =
        new TreeSet<>(
            AsmHost.referencedClasses(
                AsmHost.readClassBytes(work.resolve("apptest.jar"), "com.acme.app.Test1"),
                "com.acme.app.Test1"));
    assertTrue(testRefs.contains("com.acme.liba.A2"), "Test1 references A2");
    assertTrue(testRefs.contains("com.acme.liba.A3"), "Test1 references A3");
    assertTrue(!testRefs.contains("com.acme.libb.B1"), "Test1 does not reference B1");
  }

  /** The same arithmetic the lmcode app performs: used (main+test) and used-by-test per library. */
  private static void verifyAnalysis(Path work) throws IOException {
    List<Path> libs = List.of(work.resolve("jars/liba.jar"), work.resolve("jars/libb.jar"));
    List<Path> mainPaths = List.of(work.resolve("appmain"));
    List<Path> testPaths = List.of(work.resolve("apptest.jar"));
    List<String> expected = List.of("liba.jar=3,2", "libb.jar=2,1");
    List<String> actual = new ArrayList<>();
    for (Path lib : libs) {
      Set<String> libClasses = new TreeSet<>(AsmHost.listClasses(lib));
      Set<String> used = new TreeSet<>();
      Set<String> usedTest = new TreeSet<>();
      for (Path path : mainPaths) {
        scan(path, libClasses, used);
      }
      for (Path path : testPaths) {
        scan(path, libClasses, used);
        scan(path, libClasses, usedTest);
      }
      actual.add(lib.getFileName() + "=" + used.size() + "," + usedTest.size());
    }
    assertEquals(expected, actual, "dependency analysis counts");
  }

  private static void scan(Path path, Set<String> libClasses, Set<String> used)
      throws IOException {
    for (String className : AsmHost.listClasses(path)) {
      byte[] classFile = AsmHost.readClassBytes(path, className);
      for (String ref : AsmHost.referencedClasses(classFile, className)) {
        if (libClasses.contains(ref)) {
          used.add(ref);
        }
      }
    }
  }

  private static void verifySchemaAndCli() throws IOException {
    byte[] schema;
    try (InputStream input = Main.class.getResourceAsStream("/lmasm.schema.json")) {
      if (input == null) {
        throw new IOException("missing embedded lmasm.schema.json");
      }
      schema = input.readAllBytes();
    }
    new AsmHost().registry.checkSchemaSignatures(schema);

    assertEquals(0, Main.run(new String[] {"--version"}), "CLI --version exit");
    assertEquals(0, Main.run(new String[] {"--lmcode-schema", "--json"}), "CLI schema exit");
    assertEquals(0, Main.run(new String[] {"--lmcode-signatures"}), "CLI signatures exit");
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new IllegalStateException(
          label + ": expected " + expected + " but found " + actual);
    }
  }

  private static void assertTrue(boolean condition, String label) {
    if (!condition) {
      throw new IllegalStateException(label);
    }
  }

  private static String joinPathList(List<Path> paths) {
    StringBuilder joined = new StringBuilder();
    for (Path path : paths) {
      if (joined.length() > 0) {
        joined.append(java.io.File.pathSeparator);
      }
      joined.append(path);
    }
    return joined.toString();
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var stream = Files.walk(root)) {
      for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
