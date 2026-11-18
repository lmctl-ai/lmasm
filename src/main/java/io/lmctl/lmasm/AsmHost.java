package io.lmctl.lmasm;

import io.lmctl.lmcode.Context;
import io.lmctl.lmcode.Registry;
import io.lmctl.lmcode.Stdlib;
import io.lmctl.lmcode.Value;
import io.lmctl.lmcode.VmException;
import io.lmctl.lmcode.VmString;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * lmasm host: the ASM bytecode tree API exposed as lmcode host functions.
 *
 * <p>Host functions (declared in lmasm.schema.json):
 *
 * <ul>
 *   <li>{@code asm_classes(path) -> StrList}: dotted class names contained in a jar file, a classes
 *       directory, or a single {@code .class} file. {@code module-info} and {@code package-info}
 *       are skipped.
 *   <li>{@code asm_refs(path, class) -> StrList}: dotted names of every class the given class
 *       references — the union of its constant-pool {@code CONSTANT_Class} entries and an asm-tree
 *       walk (superclass, interfaces, field and method descriptors, thrown exceptions, instruction
 *       operands, invokedynamic bootstrap arguments and annotations). The class itself is excluded.
 * </ul>
 *
 * <p>All analysis policy (what counts as "used", scope split, output shape) lives in the lmcode
 * program, not here.
 */
public final class AsmHost {
  public final Registry registry;

  public AsmHost() {
    registry = new Registry(); // Standard catalog registered by default.
    Stdlib.registerTesting(registry, System.out); // print(string), assert_close(float, float, float).
    registry.function("asm_classes", List.of("string"), "StrList", this::classes);
    registry.function("asm_refs", List.of("string", "string"), "StrList", this::refs);
  }

  /** Per-run execution context; analysis inputs can be large, so host policy sets no VM budget. */
  public Context context() {
    Context context = new Context();
    context.instructionLimit = Long.MAX_VALUE;
    context.memoryLimitBytes = Long.MAX_VALUE;
    return context;
  }

  private Value classes(Context context, Value self, Value[] arguments) {
    Path path = Path.of(arguments[0].asString().toString());
    try {
      List<VmString> names = new ArrayList<>();
      for (String name : listClasses(path)) {
        names.add(VmString.fromString(name));
      }
      return Stdlib.makeStrList(context, names);
    } catch (IOException | RuntimeException error) {
      throw VmException.host("asm_classes: " + error.getMessage());
    }
  }

  private Value refs(Context context, Value self, Value[] arguments) {
    Path path = Path.of(arguments[0].asString().toString());
    String className = arguments[1].asString().toString();
    try {
      byte[] classFile = readClassBytes(path, className);
      List<VmString> names = new ArrayList<>();
      for (String name : referencedClasses(classFile, className)) {
        names.add(VmString.fromString(name));
      }
      return Stdlib.makeStrList(context, names);
    } catch (IOException | RuntimeException error) {
      throw VmException.host("asm_refs: " + error.getMessage());
    }
  }

  /** Dotted class names for every {@code .class} entry in a jar, classes directory, or class file. */
  static List<String> listClasses(Path path) throws IOException {
    if (!Files.exists(path)) {
      throw new IOException("no such path: " + path);
    }
    if (Files.isDirectory(path)) {
      try (var stream = Files.walk(path)) {
        return stream
            .filter(entry -> Files.isRegularFile(entry) && entry.toString().endsWith(".class"))
            .map(entry -> entryToDotted(path.relativize(entry).toString()))
            .filter(name -> name != null)
            .sorted()
            .toList();
      }
    }
    String fileName = path.getFileName().toString();
    if (fileName.endsWith(".jar") || fileName.endsWith(".zip")) {
      try (JarFile jar = new JarFile(path.toFile())) {
        List<String> names = new ArrayList<>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (!entry.isDirectory()) {
            String name = entryToDotted(entry.getName());
            if (name != null) {
              names.add(name);
            }
          }
        }
        return names.stream().sorted().toList();
      }
    }
    if (fileName.endsWith(".class")) {
      String name = entryToDotted(fileName);
      return name == null ? List.of() : List.of(name);
    }
    throw new IOException("not a jar, classes directory, or .class file: " + path);
  }

  /** Raw bytes of one dotted class name inside a jar, classes directory, or standalone class file. */
  static byte[] readClassBytes(Path path, String dottedName) throws IOException {
    String entryName = dottedName.replace('.', '/') + ".class";
    if (Files.isDirectory(path)) {
      Path file = path.resolve(entryName);
      if (!Files.isRegularFile(file)) {
        throw new IOException("class not found: " + dottedName + " in " + path);
      }
      return Files.readAllBytes(file);
    }
    String fileName = path.getFileName().toString();
    if (fileName.endsWith(".jar") || fileName.endsWith(".zip")) {
      try (JarFile jar = new JarFile(path.toFile())) {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) {
          throw new IOException("class not found: " + dottedName + " in " + path);
        }
        try (InputStream input = jar.getInputStream(entry)) {
          return input.readAllBytes();
        }
      }
    }
    if (fileName.endsWith(".class")) {
      String standalone = entryToDotted(fileName);
      if (!dottedName.equals(standalone)) {
        throw new IOException("class not found: " + dottedName + " in " + path);
      }
      return Files.readAllBytes(path);
    }
    throw new IOException("not a jar, classes directory, or .class file: " + path);
  }

  /** Dotted class name from a {@code .class} entry name, or null for module/package descriptors. */
  static String entryToDotted(String entryName) {
    if (!entryName.endsWith(".class")) {
      return null;
    }
    String base = entryName.substring(0, entryName.length() - ".class".length());
    base = base.replace('/', '.').replace('\\', '.');
    if (base.equals("module-info")
        || base.equals("package-info")
        || base.endsWith(".module-info")
        || base.endsWith(".package-info")) {
      return null;
    }
    return base;
  }

  /**
   * Referenced classes of one class file: every {@code CONSTANT_Class} constant-pool entry plus the
   * asm-tree walk, normalized to dotted names, excluding the class itself, sorted.
   */
  static List<String> referencedClasses(byte[] classFile, String selfName) {
    ClassReader reader = new ClassReader(classFile);
    Set<String> refs = new TreeSet<>();
    char[] buffer = new char[reader.getMaxStringLength()];
    for (int i = 1; i < reader.getItemCount(); i++) {
      // readConst is the public constant-pool accessor: class constants come back as Types
      // (object or array); entries it cannot represent throw, so they are skipped.
      try {
        if (reader.readConst(i, buffer) instanceof Type type) {
          addType(refs, type);
        }
      } catch (IllegalArgumentException notAConstant) {
        // MethodHandle, Dynamic, Module, Package and similar entries carry no class reference.
      }
    }
    ClassNode node = new ClassNode();
    reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    addInternal(refs, node.superName);
    for (String iface : node.interfaces) {
      addInternal(refs, iface);
    }
    addAnnotations(refs, node.invisibleAnnotations, node.visibleAnnotations);
    for (FieldNode field : node.fields) {
      addType(refs, Type.getType(field.desc));
      addAnnotations(refs, field.invisibleAnnotations, field.visibleAnnotations);
    }
    for (MethodNode method : node.methods) {
      addMethodType(refs, Type.getMethodType(method.desc));
      for (String exception : method.exceptions) {
        addInternal(refs, exception);
      }
      addAnnotations(refs, method.invisibleAnnotations, method.visibleAnnotations);
      for (AbstractInsnNode instruction : method.instructions) {
        switch (instruction) {
          case TypeInsnNode typeInstruction ->
              addType(refs, Type.getObjectType(typeInstruction.desc));
          case FieldInsnNode fieldInstruction -> {
            addInternal(refs, fieldInstruction.owner);
            addType(refs, Type.getType(fieldInstruction.desc));
          }
          case MethodInsnNode methodInstruction -> {
            addInternal(refs, methodInstruction.owner);
            addMethodType(refs, Type.getMethodType(methodInstruction.desc));
          }
          case LdcInsnNode ldc -> {
            if (ldc.cst instanceof Type type) {
              addType(refs, type);
            }
          }
          case InvokeDynamicInsnNode invokedynamic -> {
            addMethodType(refs, Type.getMethodType(invokedynamic.desc));
            for (Object bootstrapArgument : invokedynamic.bsmArgs) {
              if (bootstrapArgument instanceof Type type) {
                addType(refs, type);
              }
            }
          }
          case MultiANewArrayInsnNode multiArray ->
              addType(refs, Type.getType(multiArray.desc));
          default -> {}
        }
      }
    }
    refs.remove(selfName);
    return new ArrayList<>(refs);
  }

  private static void addAnnotations(
      Set<String> refs,
      List<AnnotationNode> invisible,
      List<AnnotationNode> visible) {
    if (invisible != null) {
      for (AnnotationNode annotation : invisible) {
        addType(refs, Type.getType(annotation.desc));
        addAnnotationValues(refs, annotation.values);
      }
    }
    if (visible != null) {
      for (AnnotationNode annotation : visible) {
        addType(refs, Type.getType(annotation.desc));
        addAnnotationValues(refs, annotation.values);
      }
    }
  }

  private static void addAnnotationValues(Set<String> refs, List<Object> values) {
    if (values == null) {
      return;
    }
    for (Object value : values) {
      if (value instanceof Type type) {
        addType(refs, type);
      } else if (value instanceof List<?> items) {
        for (Object item : items) {
          if (item instanceof Type type) {
            addType(refs, type);
          }
        }
      } else if (value instanceof AnnotationNode nested) {
        addType(refs, Type.getType(nested.desc));
        addAnnotationValues(refs, nested.values);
      }
    }
  }

  private static void addMethodType(Set<String> refs, Type method) {
    for (Type argument : method.getArgumentTypes()) {
      addType(refs, argument);
    }
    addType(refs, method.getReturnType());
  }

  private static void addType(Set<String> refs, Type type) {
    switch (type.getSort()) {
      case Type.OBJECT -> refs.add(type.getClassName());
      case Type.ARRAY -> addType(refs, type.getElementType());
      case Type.METHOD -> addMethodType(refs, type);
      default -> {}
    }
  }

  private static void addInternal(Set<String> refs, String internalName) {
    if (internalName == null) {
      return;
    }
    if (internalName.startsWith("[")) {
      addType(refs, Type.getType(internalName));
    } else {
      refs.add(Type.getObjectType(internalName).getClassName());
    }
  }
}
