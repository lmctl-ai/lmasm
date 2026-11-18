package io.lmctl.lmasm;

import io.lmctl.lmcode.Context;
import io.lmctl.lmcode.InvocationResults;
import io.lmctl.lmcode.Program;
import io.lmctl.lmcode.Reports;
import io.lmctl.lmcode.ResultContract;
import io.lmctl.lmcode.VmException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * lmasm host CLI protocol: {@code --version}, {@code --lmcode-schema [--json]},
 * {@code --lmcode-signatures}, {@code --lmcode FILE} (repeatable).
 *
 * <p>All output uses explicit UTF-8 and LF bytes. The schema bytes served here are the exact bytes
 * the compiler hashes into every program this host loads.
 */
public final class Main {
  private static final String VERSION = version();
  private static final String USAGE =
      "usage: --version | --lmcode-schema [--json] | --lmcode-signatures | --lmcode FILE [--lmcode FILE ...]";

  private Main() {}

  /** Build-stamped version (Maven resource filtering); "dev" outside a packaged build. */
  private static String version() {
    try (InputStream input = Main.class.getResourceAsStream("/lmasm-version.txt")) {
      if (input == null) {
        return "dev";
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException error) {
      return "dev";
    }
  }

  private static byte[] readSchema() throws IOException {
    try (InputStream input = Main.class.getResourceAsStream("/lmasm.schema.json")) {
      if (input == null) {
        throw new IOException("missing embedded lmasm.schema.json");
      }
      return input.readAllBytes();
    }
  }

  private static void writeLine(String text) {
    byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
    System.out.write(bytes, 0, bytes.length);
    System.out.flush();
  }

  private static int usage() {
    writeLine(
        "{\"ok\": false, \"phase\": \"cli\", \"code\": \"CLI_USAGE\", \"message\": "
            + Reports.escape(USAGE)
            + "}");
    return 2;
  }

  public static int run(String[] args) {
    try {
      if (args.length == 1 && "--version".equals(args[0])) {
        writeLine("lmasm " + VERSION);
        return 0;
      }
      if ((args.length == 1 || (args.length == 2 && "--json".equals(args[1])))
          && "--lmcode-schema".equals(args[0])) {
        byte[] schema = readSchema();
        System.out.write(schema, 0, schema.length);
        System.out.flush();
        return 0;
      }
      AsmHost host = new AsmHost();
      byte[] schema = readSchema();
      if (args.length == 1 && "--lmcode-signatures".equals(args[0])) {
        for (String signature : host.registry.signatures()) {
          writeLine(signature);
        }
        return 0;
      }
      List<String> files = new ArrayList<>();
      for (int i = 0; i < args.length; i += 2) {
        if (i + 1 >= args.length || !"--lmcode".equals(args[i])) {
          return usage();
        }
        files.add(args[i + 1]);
      }
      if (files.isEmpty()) {
        return usage();
      }
      // Refuse to start when the registry and the schema have drifted apart.
      host.registry.checkSchemaSignatures(schema);
      InvocationResults results = InvocationResults.fromCommand(files);
      ResultContract contract = ResultContract.fromSchema(schema);
      Context context = host.context();
      context.setDry(results.dry());
      boolean failed = false;
      for (InvocationResults.Registration part : results.registrations()) {
        String phase = "load";
        try {
          Program program =
              Program.load(Files.readAllBytes(Path.of(part.file())), schema, host.registry);
          results.select(part.index(), host.registry, contract);
          phase = "run";
          program.run(context, host.registry);
          results.complete(part.index());
        } catch (VmException error) {
          results.fail(part.index(), phase, error);
          failed = true;
          break;
        } catch (Exception error) {
          results.fail(part.index(), "app", new VmException("APP_FAIL", String.valueOf(error)));
          failed = true;
          break;
        }
      }
      return results.writeFrame(System.out);
    } catch (VmException error) {
      return Reports.failureFrame("cli", error);
    } catch (Exception error) {
      return Reports.failureFrame("app", new VmException("APP_FAIL", String.valueOf(error)));
    }
  }

  public static void main(String[] args) {
    int code = run(args);
    if (code != 0) {
      System.exit(code);
    }
  }
}
