# lmasm durable memory

Navigation for coding agents working on lmasm: the ASM bytecode library
exposed as host functions for lmcode, and lmcode applications built on it.

## Current state

- Host functions (`asm_classes`, `asm_refs`) live in
  `src/main/java/io/lmctl/lmasm/AsmHost.java`; the CLI protocol is
  `Main.java`; the schema is `schema/lmasm.schema.json` (raw bytes are the
  compiled-program identity — `.gitattributes` pins LF).
- The vendored lmcode-java SDK pin is `sdk.lock`; sync by copying
  `src/main/java/io` from the lmcode-java checkout.
- First application: `examples/dep-analysis.lms` — per third-party jar,
  classes used by custom code and classes used by test-scope custom code.
  Input lists (`lib_paths`, `main_paths`, `test_paths`) are literals at the top
  of the `_lmcode_` block; the lmscript wrapper cannot splice lists into the
  block (strings never become code).
- Reference extraction = constant-pool `CONSTANT_Class` entries (public
  `ClassReader.readConst` in a try/catch; `getItemType` is package-private in
  ASM 9.x) plus an asm-tree walk. Fixtures compile with `--release 21`.

## Gates

- `mvn verify` — JDK-only integration test (`IntegrationTest`): compiles
  fixtures via `javax.tools`, verifies host functions, analysis arithmetic
  (liba=3,2 libb=2,1), schema/registry/CLI consistency.
- `./test.sh` — lmcode end-to-end via `lmscript` (needs Node 24 +
  `@lmctl-ai/lmctl` on PATH); honors `LMASM_SKIP_BUILD=1`; resolves the host
  as `lmasm` from PATH, so CI can point it at the GraalVM native binary.

## CI and releases

- `.github/workflows/ci.yml`: push/PR — JVM+e2e on linux-x64, macos-x64
  (macos-15-intel), windows-x64; native smoke on linux-x64 (linkage policy,
  schema byte compare, e2e on the native binary). Compiler pinned via
  `LMCODE_COMPILER_VERSION` (default 0.2.482, same pin as lmcode-java CI).
- `.github/workflows/release.yml`: `v*` tags — GraalVM Native Image on five
  platforms, each smoke-tested and run through the e2e gate before upload;
  release job publishes assets + SHA256SUMS. v0.1.0 published 2026-11-18.
- Native build: `scripts/build-native.sh` (resources `lmasm[.-].*`, static
  libstdc++/libgcc, dynamic glibc >= 2.35, march=compatibility).

### Windows/macOS CI lessons (cost real iterations)

- `GITHUB_PATH`/`GITHUB_ENV` only affect SUBSEQUENT steps — export inline when
  the same step needs the tool.
- npm's POSIX shim for lmscript is broken in git-bash on Windows; use
  `lmscript.cmd` (lmcode-java does the same).
- lmcode host discovery (`lmscript.ts` `findExecutable`/`boundExecutable`)
  tries the extensionless name before `.CMD`/`.EXE` in EACH PATH directory, and
  the discovery probe spawns with `shell:false` — Windows hosts must be real
  `.exe` binaries (e.g. the GraalVM build); `.cmd` wrappers fail with EINVAL.
- `javac -cp` needs `;` on Windows.
- GraalVM CE on Windows ships `bin\native-image.cmd` (not found by git-bash
  `command -v`, not exec-able by `/usr/bin/timeout`); resolve via `JAVA_HOME`.
- macOS has no `timeout(1)` without coreutils (`brew install coreutils`);
  `timeout_cmd` degrades to no timeout.
- The `macos-14` label was retired ("configuration not supported"); arm64
  macOS builds use `macos-15`.
- `test.sh` appends (not prepends) `bin` to PATH so CI-provided host binaries
  (native e2e) win; it honors `LMSCRIPT` and `LMASM_SKIP_BUILD`.

## License

MIT (`LICENSE`, lmctl-ai like lmcode-java). ASM 9.11 is BSD-3-Clause and
bundled into the shaded jar and native binaries (`THIRD_PARTY_NOTICES`).
