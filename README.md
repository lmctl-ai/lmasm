# lmasm

The ASM bytecode library (asm + asm-tree) exposed as host functions for
[lmcode](https://github.com/lmctl-ai/lmcode), plus the first application built
on it: **dependent analysis** — given custom code (jars or classes folders) and
third-party library jars, report how much of each library the custom code
actually uses, split by test scope.

The analysis policy lives entirely in the lmcode program
(`examples/dep-analysis.lms`), so it is easy to read and change; the Java host
only exposes raw bytecode facts.

## Layout

- `src/main/java/io/lmctl/lmasm/AsmHost.java` — the host registry: ASM tree API
  as lmcode host functions.
- `src/main/java/io/lmctl/lmasm/Main.java` — host CLI protocol
  (`--version`, `--lmcode-schema [--json]`, `--lmcode-signatures`,
  `--lmcode FILE` repeatable).
- `schema/lmasm.schema.json` — the host schema (raw bytes are the compiled
  identity of every program; the registry is checked against it at startup).
- `vendor/lmcode-java/` — vendored lmcode-java SDK (pin in `sdk.lock`).
- `examples/dep-analysis.lms` — the lmcode dependency-analysis application.
- `fixtures/` — sample custom code and third-party libraries used by the
  example and the gate.
- `bin/lmasm` — launcher script (resolves a JDK, runs `target/lmasm.jar`).

## Host functions

| Signature | Meaning |
| --- | --- |
| `fn asm_classes(string) -> StrList` | Dotted class names in a jar, a classes directory, or one `.class` file (`module-info`/`package-info` skipped). |
| `fn asm_refs(string, string) -> StrList` | Dotted names of every class the given class references: the union of its constant-pool `CONSTANT_Class` entries and an asm-tree walk (superclass, interfaces, field/method descriptors, thrown exceptions, instruction operands, invokedynamic bootstrap arguments, annotations). The class itself is excluded. |

`print(string)` and `assert_close(float, float, float)` come from the SDK's
testing registration. Everything else in the registry is the implicit lmcode
standard catalog.

## Dependency analysis

`examples/dep-analysis.lms` takes three input lists at the top of the
`_lmcode_` block:

- `lib_paths` — third-party library jars,
- `main_paths` — custom code in main scope (jars or classes folders),
- `test_paths` — custom code in test scope (jars or classes folders).

Output, one line per third-party jar:

```text
<jar>, <classes used by any custom code (main + test)>, <classes used by test-scope custom code>
```

A library class counts as *used* when any custom class references it (constant
pool or tree walk). The test column is a subset of the used column.

### Run it

```sh
./test.sh        # builds the host + fixtures, runs the example, diffs the output
```

or step by step:

```sh
mvn package                  # needs JDK 21+; produces target/lmasm.jar
./fixtures/build-fixtures.sh # needs javac; compiles with --release 21 for deterministic bytecode
export PATH="$PWD/bin:$PATH"
lmscript examples/dep-analysis.lms
```

Expected output:

```text
fixtures/jars/liba.jar, 3, 2
fixtures/jars/libb.jar, 2, 1
{"ok":true,"result":null}
```

Notes:

- To analyze your own code, edit the three path lists in
  `examples/dep-analysis.lms`.
- A cold `lmscript --check` cannot resolve `cli="lmasm"` (check/dry never start
  an uncached host); run the script once for real discovery first, or pass
  `schema="schema/lmasm.schema.json"` to the block instead of `cli=`.
- Class files must be readable by the bundled ASM 9.11; compile newer inputs
  with `--release 21` if your JDK emits bytecode past its support window.

## Integration test

`mvn verify` runs `io.lmctl.lmasm.IntegrationTest` on any JDK: it compiles the
fixture sources with the system Java compiler and verifies `asm_classes` /
`asm_refs`, the used/test-used analysis arithmetic, and the CLI/schema/registry
consistency. `./test.sh` goes one step further and runs the actual lmcode
program through `lmscript` (needs Node 24 and `@lmctl-ai/lmctl` on PATH, as CI
provides).

## CI and releases

- `.github/workflows/ci.yml` runs on push to `main` and on pull requests: JVM
  build + integration test + lmcode end-to-end on Linux, macOS, and Windows,
  plus a GraalVM native-image smoke job on Linux that runs the whole
  dependency-analysis example against the native binary.
- `.github/workflows/release.yml` runs on `v*` tags: GraalVM Native Image
  builds for linux-x64, linux-arm64, macos-x64, macos-arm64, and windows-x64,
  each smoke-tested and run through the lmcode end-to-end gate, then published
  as a GitHub release with `SHA256SUMS`.

## License

MIT; see `LICENSE`. ASM (bundled) is BSD-3-Clause; see `THIRD_PARTY_NOTICES`.

## Extending

Add a function in `AsmHost` (register it in the constructor), declare it in
`schema/lmasm.schema.json`, rebuild — the host refuses to run when the registry
and schema drift apart. The lmcode program automatically sees the new
declaration on the next compile because the compiler re-queries
`--lmcode-schema --json`.
